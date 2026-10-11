package com.railgate.reservation.application.hold.idempotency;

import com.railgate.reservation.application.hold.HoldSeatsCommand;
import com.railgate.reservation.application.hold.HoldSeatsResult;
import com.railgate.reservation.application.hold.HoldSeatsService;
import com.railgate.reservation.application.hold.idempotency.StoredHoldOutcome.Rejected;
import com.railgate.reservation.application.hold.idempotency.StoredHoldOutcome.Succeeded;
import com.railgate.reservation.application.hold.idempotency.Task2iaFaultPoints.Point;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ★ <b>선택한 대안 (ADR-009 후보 B) — 성공 결과를 본 작업과 같은 트랜잭션(TX1)에서 fence 조건부로 기록한다.</b>
 *
 * <pre>
 *   TX0 키 확보(REQUIRES_NEW)
 *   TX1 본 작업 + 키 행 완료 기록(UPDATE ... WHERE state='IN_PROGRESS' AND fence=:mine)
 *       └ affected=0 이면 리스를 빼앗긴 것 → TX1 전체 롤백 (좌석·quota 변경도 함께 사라진다)
 *   TX2 업무 실패 기록(REQUIRES_NEW, fence 조건부) — TX1 이 롤백된 경우에만 연다
 * </pre>
 *
 * <h2>무엇을 보장하는가 (구조적 논증)</h2>
 *
 * <ul>
 *   <li><b>"선점 커밋" ⇔ "키 COMPLETED(성공 결과)"</b> — 같은 트랜잭션의 두 쓰기다. TX1 커밋 직후 죽어도
 *       재요청은 리스 만료를 기다릴 필요 없이 저장된 {@code holdId} 를 재생한다</li>
 *   <li><b>한 키에서 선점을 커밋하는 시도는 많아야 하나다</b> — 커밋하려면 키 행을 {@code IN_PROGRESS → COMPLETED}
 *       로 바꿔야 하고, 그 UPDATE 는 InnoDB 행 잠금으로 직렬화되며 자기 fence 가 현재 fence 일 때만 성공한다.
 *       인수(fence+1)가 먼저 커밋되면 구 작업자의 완료 UPDATE 가 0 이 되어 TX1 이 롤백되고,
 *       완료가 먼저면 인수 UPDATE 가 {@code state='IN_PROGRESS'} 조건에서 0 이 된다</li>
 *   <li><b>재시도 가능한 장애에서 키를 넘겨줘도 안전하다</b> — 커밋 여부가 불확실해도 성공했다면 이미
 *       {@code COMPLETED} 라 포기 UPDATE 가 0 건이고, 실패했다면 상태 변경도 없다</li>
 * </ul>
 *
 * <h2>잠금 순서와 커넥션</h2>
 *
 * <ul>
 *   <li>TX1: <b>quota → seat(PK 오름차순) → 키 행</b>. 키 행을 마지막에 잠근다. 키 행만 잠그는
 *       TX0(삽입·인수)·TX2 는 다른 잠금을 쥔 채 기다리지 않으므로 순환이 없다</li>
 *   <li>TX0·TX1·TX2 는 <b>순차</b>다. 요청 하나가 동시에 쥐는 커넥션은 최대 1개다.
 *       바깥 트랜잭션 안에서 부르면 거부한다 — {@code REQUIRES_NEW} 가 두 번째 커넥션을 잡기 때문이다</li>
 * </ul>
 *
 * <h2>규칙 16 과의 충돌</h2>
 *
 * <p>규칙 16 은 결과 기록을 TX2(REQUIRES_NEW)로 고정한다. 이 구현은 <b>성공 결과를 TX1 에서</b> 기록한다.
 * 규칙 16 의 근거(키 INSERT 와 본 작업을 한 트랜잭션에 넣으면 중복 키 뒤 기존 응답을 못 읽는다)는
 * TX0 분리로 그대로 지킨다. 그러나 문구와 다르므로 <b>규칙을 바꾸지 않고 ADR-009 에 결정 대기로 기록했다.</b>
 *
 * <p>운영 코드가 아니다. 테스트 전용 테이블을 쓴다.
 */
final class Task2iaAtomicCompletionIdempotentHold {

    /** TX1 의 완료 기록이 0 건 — 이 시도는 더 이상 키의 소유자가 아니다. TX1 을 롤백시키기 위해 던진다. */
    static final class FencedOutException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        FencedOutException(long fence) {
            super("fence " + fence + " 는 더 이상 키를 소유하지 않는다");
        }
    }

    private final HoldSeatsService holdSeats;
    private final Task2iaIdempotencyStore store;
    private final Task2iaKeyAcquisition acquisition;
    private final TransactionTemplate tx1;
    private final TransactionTemplate tx2;
    private final Task2iaFaultPoints faults;

    Task2iaAtomicCompletionIdempotentHold(
            HoldSeatsService holdSeats,
            Task2iaIdempotencyStore store,
            PlatformTransactionManager transactionManager,
            Duration lease,
            Task2iaFaultPoints faults) {
        this.holdSeats = Objects.requireNonNull(holdSeats, "holdSeats");
        this.store = Objects.requireNonNull(store, "store");
        this.acquisition = new Task2iaKeyAcquisition(store, transactionManager, lease);
        this.tx1 = new TransactionTemplate(transactionManager);
        this.tx2 = new TransactionTemplate(transactionManager);
        this.tx2.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.faults = Objects.requireNonNull(faults, "faults");
    }

    Task2iaHoldResponse hold(IdempotencyKey key, HoldSeatsCommand command) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(command, "command");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "바깥 트랜잭션 안에서 부를 수 없다 — TX0·TX2 의 REQUIRES_NEW 가 커넥션을 하나 더 잡는다");
        }
        // 사용자는 인증된 주체에서 와야 한다. 인증이 없으므로 명령의 값을 쓴다 (계약만 있음).
        IdempotencyScope scope = IdempotencyScope.holdSeats(command.userId(), key);
        String fingerprint = HoldRequestFingerprint.of(command);

        Task2iaKeyAcquisition.Result acquired = acquisition.acquire(scope, fingerprint);
        if (!(acquired instanceof Task2iaKeyAcquisition.Result.Owned owned)) {
            return respondWithoutExecuting(acquired);
        }
        long fence = owned.fence();
        faults.at(Point.AFTER_TX0);

        try {
            HoldSeatsResult result = tx1.execute(status -> {
                faults.at(Point.TX1_BEFORE_WORK);
                HoldSeatsResult held = holdSeats.hold(command);
                // 키 행을 마지막에 잠근다: quota → seat → key.
                if (store.completeSucceeded(scope, fence, Succeeded.from(held)) != 1) {
                    throw new FencedOutException(fence);
                }
                faults.at(Point.TX1_BEFORE_COMMIT);
                return held;
            });
            faults.at(Point.AFTER_TX1_COMMIT);
            return new Task2iaHoldResponse.Executed(Succeeded.from(Objects.requireNonNull(result)));
        } catch (FencedOutException fencedOut) {
            return new Task2iaHoldResponse.Superseded();
        } catch (RuntimeException failure) {
            Optional<Rejected> rejected = StoredHoldOutcome.rejectionOf(failure);
            if (rejected.isPresent()) {
                return recordRejection(scope, fence, rejected.get());
            }
            abandonQuietly(scope, fence, failure);
            throw failure;
        }
    }

    /**
     * TX2. TX1 이 롤백돼 상태 변경이 없다는 것이 확실한 업무 실패만 기록한다.
     * 기록이 0 건이면 그사이 인수자가 생긴 것이다 — <b>늦은 기록이 새 결과를 덮지 않는다.</b>
     */
    private Task2iaHoldResponse recordRejection(IdempotencyScope scope, long fence, Rejected rejected) {
        faults.at(Point.BEFORE_TX2);
        Integer affected = tx2.execute(status -> {
            faults.at(Point.IN_TX2);
            return store.completeRejected(scope, fence, rejected);
        });
        if (affected == null || affected != 1) {
            return new Task2iaHoldResponse.Superseded();
        }
        return new Task2iaHoldResponse.Executed(rejected);
    }

    /**
     * 재시도 가능한 장애 — 결과를 저장하지 않고 리스를 즉시 만료시킨다. 커밋 여부가 불확실해도 안전하다
     * (클래스 주석). 포기 기록마저 실패하면 리스 만료를 기다리게 될 뿐이다.
     */
    private void abandonQuietly(IdempotencyScope scope, long fence, RuntimeException original) {
        try {
            tx2.executeWithoutResult(status -> store.abandon(scope, fence));
        } catch (RuntimeException abandonFailure) {
            original.addSuppressed(abandonFailure);
        }
    }

    static Task2iaHoldResponse respondWithoutExecuting(Task2iaKeyAcquisition.Result acquired) {
        return switch (acquired) {
            case Task2iaKeyAcquisition.Result.Replay replay -> new Task2iaHoldResponse.Replayed(replay.outcome());
            case Task2iaKeyAcquisition.Result.InProgress inProgress -> new Task2iaHoldResponse.InProgress();
            case Task2iaKeyAcquisition.Result.Mismatch mismatch -> new Task2iaHoldResponse.FingerprintMismatch();
            case Task2iaKeyAcquisition.Result.Owned owned ->
                    throw new IllegalArgumentException("소유한 경우는 실행해야 한다: " + owned);
        };
    }
}
