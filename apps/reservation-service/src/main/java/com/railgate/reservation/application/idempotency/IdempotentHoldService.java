package com.railgate.reservation.application.idempotency;

import com.railgate.reservation.application.hold.HoldSeatsCommand;
import com.railgate.reservation.application.hold.HoldSeatsResult;
import com.railgate.reservation.application.hold.HoldSeatsService;
import com.railgate.reservation.idempotency.IdempotencyKey;
import com.railgate.reservation.idempotency.IdempotencyKeyPort;
import com.railgate.reservation.idempotency.IdempotencyRecord;
import com.railgate.reservation.idempotency.IdempotencyScope;
import com.railgate.reservation.idempotency.KeyOwnership;
import com.railgate.reservation.idempotency.KeyWriteOutcome;
import com.railgate.reservation.idempotency.RequestFingerprint;
import com.railgate.reservation.idempotency.StoredHoldOutcome.Rejected;
import com.railgate.reservation.idempotency.StoredHoldOutcome.Succeeded;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ★ 선점 요청의 멱등성 조정자 (ADR-009 후보 B + generation·fence, CLAUDE.md 규칙 16).
 *
 * <pre>
 *   TX0 (REQUIRES_NEW)  키 INSERT, 이미 있으면 TX0 를 끝내고 새 트랜잭션에서 조회 → 판정 → (만료면) 조건부 인수
 *   TX1 (REQUIRED)      HoldSeatsService.hold  →  성공 결과 기록 (세대·fence 조건)
 *                       └ NOT_OWNER 면 TX1 전체(좌석·quota) 롤백 → 소유권 상실
 *   TX2 (REQUIRES_NEW)  TX1 롤백이 확인된 업무 실패만 기록 (세대·fence 조건)
 *   장애                결과를 저장하지 않고 조건부 리스 포기(REQUIRES_NEW) 후 원래 예외 전파
 * </pre>
 *
 * <h2>무엇을 보장하는가</h2>
 *
 * <ul>
 *   <li><b>선점 커밋 ⇔ 성공 결과 기록</b> — 같은 트랜잭션이다. TX1 직후 응답이 유실돼도 재요청은 최초 결과를 재생한다</li>
 *   <li><b>소유권을 잃은 시도의 선점은 남지 않는다</b> — 성공 기록이 거절되면 TX1 을 롤백한다</li>
 *   <li><b>업무 실패는 TX1 롤백이 확인된 뒤에만 기록한다</b> — {@link TransactionTemplate} 은 롤백이 끝난 뒤에야
 *       원래 예외를 다시 던진다. 롤백 자체가 실패하면 롤백 예외가 대신 나오고, 그것은 업무 예외가 아니므로 장애로 다룬다</li>
 *   <li><b>장애 뒤 리스 포기는 완료된 행을 바꾸지 않는다</b> — 포기는 {@code state='IN_PROGRESS'} 조건이다. 커밋 결과가
 *       불명이어도, 실제로 커밋됐다면 행은 이미 완료라 포기가 0 건이다</li>
 * </ul>
 *
 * <h2>무엇을 하지 않는가</h2>
 *
 * <ul>
 *   <li><b>인증</b> — 사용자는 {@link HoldSeatsCommand#userId()} 에서 온다. 인증된 주체와의 대조가 없으므로
 *       남의 사용자 id 로 키 범위를 쓸 수 있다는 한계가 남는다</li>
 *   <li>HTTP 응답·상태 코드(매핑은 ADR-009 C3 채택 — {@code docs/HOLD_HTTP_CONTRACT.md}), 응답 직렬화, 보존 기간 삭제,
 *       다른 유스케이스</li>
 * </ul>
 *
 * <h2>잠금 순서와 커넥션</h2>
 *
 * <p>TX1 은 {@code quota → seat(PK 오름차순) → 키 행} 순으로 잠근다 — 선점 서비스의 순서를 그대로 두고 키 행을 마지막에
 * 붙인다. TX0·TX1·TX2 는 순차라 요청 하나가 동시에 쥐는 커넥션은 최대 1개다. 그래서 <b>바깥 트랜잭션 안에서 부르면
 * 키를 건드리기 전에 거부한다</b> — {@code REQUIRES_NEW} 가 바깥 커넥션을 쥔 채 두 번째 커넥션을 잡기 때문이다.
 *
 * <h2>TX0 의 MySQL 데드락(1213)만 제한적으로 재시도한다 (PR #32)</h2>
 *
 * <p>같은 키를 지우는 트랜잭션과 겹치거나 삭제 표시만 남은 같은 키 행 위로 동시 INSERT 가 몰리면, INSERT 들이 그 행에
 * S 잠금을 함께 얻은 뒤 서로의 S 때문에 X 를 얻지 못해 InnoDB 가 하나를 1213 으로 롤백한다 (TASK-002J-B §7 에서 관측).
 * TX0 의 INSERT·인수는 실패하면 아무것도 남기지 않으므로 <b>그 트랜잭션이 롤백으로 끝나 커넥션을 돌려준 뒤 새 TX0 에서</b>
 * 같은 문장을 다시 한다. 대기하지 않는다 — 이긴 쪽은 한 문장짜리 TX0 라 곧 커밋되고, 재시도는 DB 잠금 대기로 그 커밋을
 * 기다린다.
 *
 * <ul>
 *   <li><b>대상</b> — 원인 사슬에 vendor code {@value #MYSQL_DEADLOCK} 인 {@link SQLException} 이 있는 예외만.
 *       {@code CannotAcquireLockException} 이라는 상위 타입만으로는 재시도하지 않는다. 잠금 대기 초과(1205)·연결 장애는 그대로 전파한다</li>
 *   <li><b>범위</b> — TX0 의 INSERT 와 인수 UPDATE. 조회는 잠그지 않는 일관 읽기라 데드락 희생자가 될 수 없어 감싸지 않는다(나면 그대로 전파).
 *       TX1·TX2·리스 포기는 재시도하지 않는다 — 본 작업 재실행이나 커밋 결과 불명 처리를 바꾸지 않는다</li>
 *   <li><b>상한</b> — 호출 하나당 재시도 {@value #TX0_DEADLOCK_RETRY_BUDGET} 회 (여러 TX0 문장이 나눠 쓴다)</li>
 *   <li><b>소진</b> — 마지막 데드락 예외를 그대로 던지고 앞의 것을 suppressed 로 남긴다. 중복·진행 중·업무 실패로 바꾸지 않는다.
 *       재시도 중 다른 장애가 나면 그 장애를 던지고 앞의 데드락을 함께 남긴다</li>
 * </ul>
 *
 * <h2>{@link Error} 는 잡지 않는다</h2>
 *
 * <p>프로세스가 그 자리에서 죽은 것과 같게 다룬다. 열린 트랜잭션은 롤백되고 키는 리스 만료 뒤 인수된다.
 *
 * <h2>메트릭 (규칙 35)</h2>
 *
 * <p>태그는 고정 집합의 값만 쓴다. 사용자 id·멱등키·holdId 같은 고유값은 태그에도 로그에도 남기지 않는다.
 *
 * <ul>
 *   <li><b>{@value #ATTEMPTS}</b> — TX1 을 열기 직전에 증가하는 <b>본 작업 실행 시도</b> 수</li>
 *   <li><b>{@value #COMMITTED}</b> — 성공 결과(TX1) 또는 업무 실패 결과(TX2)의 기록 트랜잭션이 <b>정상 반환돼 조정자가
 *       커밋 완료를 확인한</b> 수. DB 에 실제로 반영된 커밋을 모두 세지는 않는다 — 커밋이 반영된 뒤 실패로 보고되면
 *       증가하지 않는다</li>
 * </ul>
 *
 * <p><b>{@code attempts − committed} 는 TX1 롤백 수가 아니다.</b> 업무 실패는 TX1 이 롤백되고 TX2 기록이 성공해
 * {@code attempts=1, committed=1}(차이 0)이고, TX1 이 실제로 커밋됐는데 호출자에게 실패로 보고되면
 * {@code attempts=1, committed=0}(차이 1)이다.
 *
 * <p>{@value #ABANDONS}{@code {outcome=not_owner}} 도 커밋 성공을 확정하지 않는다. 포기 시점에 이 소유권으로 바꿀
 * 진행 중 행이 없었다는 뜻일 뿐이며, 이미 완료된 경우와 다른 작업자가 인수한 경우를 구분하지 않는다.
 */
public class IdempotentHoldService {

    static final String CALLS = "railgate.idempotency.hold.calls";
    static final String ATTEMPTS = "railgate.idempotency.hold.attempts";
    static final String COMMITTED = "railgate.idempotency.hold.committed";
    static final String TAKEOVERS = "railgate.idempotency.hold.takeovers";
    static final String NOT_OWNER = "railgate.idempotency.hold.not_owner";
    static final String ABANDONS = "railgate.idempotency.hold.abandons";

    /**
     * 삽입·조회·인수 사이에 행이 바뀌면 처음부터 다시 본다. 그 횟수의 상한.
     * 소진하면 "진행 중" 으로 답한다 — 실행하지 않는 쪽으로 물러선다. DB 장애는 이 라운드와 무관하다 — TX0 의 MySQL
     * 데드락만 {@link #TX0_DEADLOCK_RETRY_BUDGET} 안에서 새 트랜잭션으로 다시 하고, 그 밖의 DB 장애는 그대로 전파한다.
     */
    static final int MAX_ACQUIRE_ROUNDS = 3;

    /** 호출 하나당 TX0(INSERT·인수) 데드락 재시도 횟수의 상한. 최초 시도를 빼고 센다. */
    static final int TX0_DEADLOCK_RETRY_BUDGET = 3;

    static final int MYSQL_DEADLOCK = 1213;

    static final String TX0_DEADLOCKS = "railgate.idempotency.hold.tx0_deadlocks";

    private static final Logger log = LoggerFactory.getLogger(IdempotentHoldService.class);

    /** 본 작업. 운영에서는 트랜잭션 프록시가 붙은 {@link HoldSeatsService} 다. */
    @FunctionalInterface
    interface HoldExecutor {
        HoldSeatsResult hold(HoldSeatsCommand command);
    }

    /** TX1 안에서 성공 기록이 거절됐다 — TX1 을 롤백시키기 위해 던진다. */
    private static final class OwnershipLostException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        OwnershipLostException() {
            super("성공 기록이 거절됐다 — 이 시도는 더 이상 키의 소유자가 아니다", null, false, false);
        }
    }

    private sealed interface Acquired {
        record Owned(KeyOwnership ownership) implements Acquired { }
        record Answered(IdempotentHoldResult result) implements Acquired { }
    }

    private final IdempotencyKeyPort keys;
    private final HoldExecutor holdSeats;
    private final TransactionTemplate requiresNew;
    private final TransactionTemplate tx1;
    private final Duration lease;
    private final MeterRegistry meters;

    public IdempotentHoldService(
            IdempotencyKeyPort keys,
            HoldSeatsService holdSeats,
            PlatformTransactionManager transactionManager,
            Duration lease,
            MeterRegistry meters) {
        this(keys, Objects.requireNonNull(holdSeats, "holdSeats")::hold, transactionManager, lease, meters);
    }

    IdempotentHoldService(
            IdempotencyKeyPort keys,
            HoldExecutor holdSeats,
            PlatformTransactionManager transactionManager,
            Duration lease,
            MeterRegistry meters) {
        this.keys = Objects.requireNonNull(keys, "keys");
        this.holdSeats = Objects.requireNonNull(holdSeats, "holdSeats");
        Objects.requireNonNull(transactionManager, "transactionManager");
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.tx1 = new TransactionTemplate(transactionManager);
        this.tx1.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.lease = requireStorableLease(lease);
        this.meters = Objects.requireNonNull(meters, "meters");
    }

    /**
     * 같은 (사용자, 선점, 키) 의 요청을 한 번만 실행하고 이후에는 최초 결과를 재생한다.
     *
     * @throws IllegalStateException 바깥 트랜잭션 안에서 부른 경우 (키를 건드리기 전)
     * @throws RuntimeException      업무 실패가 아닌 장애. 결과는 저장되지 않았고 리스 포기를 시도했다
     */
    public IdempotentHoldResult hold(IdempotencyKey key, HoldSeatsCommand command) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(command, "command");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "멱등 선점은 바깥 트랜잭션 안에서 부를 수 없다 — TX0·TX2 가 커넥션을 하나 더 잡는다");
        }

        IdempotencyScope scope = IdempotencyScope.holdSeats(command.userId(), key);
        RequestFingerprint fingerprint = HoldRequestFingerprints.of(command);

        IdempotentHoldResult result;
        try {
            Acquired acquired = acquire(scope, fingerprint);
            result = switch (acquired) {
                case Acquired.Answered answered -> answered.result();
                case Acquired.Owned owned -> execute(scope, owned.ownership(), command);
            };
        } catch (RuntimeException failure) {
            count(CALLS, "result", "failed");
            throw failure;
        }
        count(CALLS, "result", resultTag(result));
        return result;
    }

    // ------------------------------------------------------------------ TX0

    private Acquired acquire(IdempotencyScope scope, RequestFingerprint fingerprint) {
        DeadlockBudget budget = new DeadlockBudget();
        for (int round = 0; round < MAX_ACQUIRE_ROUNDS; round++) {
            Optional<KeyOwnership> inserted = tx0("insert", budget,
                    status -> keys.tryInsert(scope, fingerprint, lease));
            if (inserted != null && inserted.isPresent()) {
                return new Acquired.Owned(inserted.get());
            }
            // 중복이었다. 그 TX0 는 끝났다 — 같은 트랜잭션에서 읽지 않고 새 트랜잭션에서 읽는다 (규칙 16).
            Optional<IdempotencyRecord> found = requiresNew.execute(status -> keys.find(scope));
            if (found == null || found.isEmpty()) {
                continue; // 보존 기간 삭제 등으로 사라졌다. 다시 넣어 본다.
            }
            IdempotencyRecord current = found.get();
            switch (HoldIdempotencyDecision.decide(current, fingerprint)) {
                case MISMATCH -> {
                    return new Acquired.Answered(new IdempotentHoldResult.FingerprintMismatch());
                }
                case REPLAY -> {
                    return new Acquired.Answered(new IdempotentHoldResult.Replayed(current.outcome().orElseThrow()));
                }
                case IN_PROGRESS -> {
                    return new Acquired.Answered(new IdempotentHoldResult.InProgress());
                }
                case TAKE_OVER -> {
                    Optional<KeyOwnership> taken = tx0("takeover", budget,
                            status -> keys.takeOver(scope, current.ownership(), lease));
                    if (taken != null && taken.isPresent()) {
                        count(TAKEOVERS);
                        return new Acquired.Owned(taken.get());
                    }
                    // 남이 먼저 인수했거나, 그사이 완료됐거나, 세대가 바뀌었다. 다시 본다.
                    count(NOT_OWNER, "write", "takeover");
                }
            }
        }
        return new Acquired.Answered(new IdempotentHoldResult.InProgress());
    }

    /** 호출 하나의 데드락 재시도 예산. */
    private static final class DeadlockBudget {
        private int remaining = TX0_DEADLOCK_RETRY_BUDGET;
    }

    /**
     * TX0 문장 하나를 새 트랜잭션에서 실행하고, MySQL 데드락이면 그 트랜잭션이 롤백으로 끝난 뒤 새 트랜잭션에서 다시 한다.
     * {@link TransactionTemplate} 은 예외를 다시 던지기 전에 롤백과 커넥션 반환을 마친다 — 재시도와 커넥션이 겹치지 않는다.
     */
    private <T> T tx0(String statement, DeadlockBudget budget, TransactionCallback<T> work) {
        List<RuntimeException> deadlocks = new ArrayList<>();
        while (true) {
            try {
                return requiresNew.execute(work);
            } catch (RuntimeException failure) {
                if (!isMySqlDeadlock(failure)) {
                    deadlocks.forEach(failure::addSuppressed);
                    throw failure;
                }
                if (budget.remaining == 0) {
                    deadlocks.forEach(failure::addSuppressed);
                    count(TX0_DEADLOCKS, "statement", statement, "outcome", "exhausted");
                    log.warn("멱등 선점 TX0 의 데드락 재시도를 모두 썼다 — 장애로 전파한다");
                    throw failure;
                }
                budget.remaining--;
                deadlocks.add(failure);
                count(TX0_DEADLOCKS, "statement", statement, "outcome", "retried");
            }
        }
    }

    /** 원인 사슬에 MySQL vendor code 1213 이 있는가. 상위 예외 타입만 보고 판단하지 않는다. */
    static boolean isMySqlDeadlock(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getErrorCode() == MYSQL_DEADLOCK) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ TX1 · TX2

    private IdempotentHoldResult execute(IdempotencyScope scope, KeyOwnership mine, HoldSeatsCommand command) {
        count(ATTEMPTS);
        Succeeded succeeded;
        try {
            succeeded = tx1.execute(status -> {
                HoldSeatsResult held = holdSeats.hold(command);
                Succeeded outcome = new Succeeded(held.holdId(), held.saleEventId(), held.seatCount());
                // 키 행을 마지막에 잠근다: quota → seat → key.
                if (keys.completeSucceeded(scope, mine, outcome) != KeyWriteOutcome.APPLIED) {
                    throw new OwnershipLostException();
                }
                return outcome;
            });
        } catch (OwnershipLostException lost) {
            // TX1 은 롤백됐다. 좌석·quota 변경도 남지 않았다.
            count(NOT_OWNER, "write", "succeeded");
            log.warn("멱등 선점의 성공 기록이 거절돼 본 작업을 롤백했다 — 리스를 빼앗긴 시도");
            return new IdempotentHoldResult.OwnershipLost();
        } catch (RuntimeException failure) {
            Optional<Rejected> rejected = HoldRejectionClassifier.classify(failure);
            if (rejected.isEmpty()) {
                throw afterAbandoning(scope, mine, failure);
            }
            // TransactionTemplate 은 롤백을 마친 뒤 원래 예외를 다시 던진다 — 상태 변경이 없다는 것이 확인됐다.
            return recordRejection(scope, mine, rejected.orElseThrow());
        }
        count(COMMITTED, "outcome", "succeeded");
        return new IdempotentHoldResult.Executed(Objects.requireNonNull(succeeded));
    }

    private IdempotentHoldResult recordRejection(IdempotencyScope scope, KeyOwnership mine, Rejected rejected) {
        KeyWriteOutcome written;
        try {
            written = requiresNew.execute(status -> keys.completeRejected(scope, mine, rejected));
        } catch (RuntimeException tx2Failure) {
            throw afterAbandoning(scope, mine, tx2Failure);
        }
        if (written != KeyWriteOutcome.APPLIED) {
            count(NOT_OWNER, "write", "rejected");
            return new IdempotentHoldResult.OwnershipLost();
        }
        count(COMMITTED, "outcome", rejected.code().name().toLowerCase(Locale.ROOT));
        return new IdempotentHoldResult.Executed(rejected);
    }

    /**
     * 결과를 저장하지 않고 리스를 즉시 만료시킨 뒤 원래 장애를 돌려준다(호출자가 던진다). 포기마저 실패하면 원래 장애에 붙여 남긴다.
     * 포기가 {@code NOT_OWNER} 면 이미 완료됐거나(커밋 결과 불명이었지만 실제로는 커밋됨) 남이 인수한 것이다.
     */
    private RuntimeException afterAbandoning(IdempotencyScope scope, KeyOwnership mine, RuntimeException original) {
        try {
            KeyWriteOutcome abandoned = requiresNew.execute(status -> keys.abandon(scope, mine));
            count(ABANDONS, "outcome", abandoned == KeyWriteOutcome.APPLIED ? "applied" : "not_owner");
        } catch (RuntimeException abandonFailure) {
            original.addSuppressed(abandonFailure);
            count(ABANDONS, "outcome", "failed");
            log.warn("멱등 선점 장애 뒤 리스 포기에도 실패했다 — 키는 리스 만료 뒤 인수된다");
        }
        return original;
    }

    // ------------------------------------------------------------------ 보조

    /** 저장소의 정밀도 계약 — 1ms 이상, 밀리초 단위 ({@code DATETIME(3)}). 기동 단계에서 확인한다. */
    private static Duration requireStorableLease(Duration lease) {
        Objects.requireNonNull(lease, "lease");
        if (lease.compareTo(Duration.ofMillis(1)) < 0 || lease.toNanosPart() % 1_000_000 != 0) {
            throw new IllegalArgumentException("리스는 1ms 이상 밀리초 단위여야 한다: " + lease);
        }
        return lease;
    }

    private static String resultTag(IdempotentHoldResult result) {
        return switch (result) {
            case IdempotentHoldResult.Executed executed -> "executed";
            case IdempotentHoldResult.Replayed replayed -> "replayed";
            case IdempotentHoldResult.InProgress inProgress -> "in_progress";
            case IdempotentHoldResult.FingerprintMismatch mismatch -> "mismatch";
            case IdempotentHoldResult.OwnershipLost lost -> "ownership_lost";
        };
    }

    private void count(String name, String... tags) {
        meters.counter(name, tags).increment();
    }
}
