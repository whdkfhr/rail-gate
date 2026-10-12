package com.railgate.reservation.application.hold.idempotency;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * TX0 — 키 확보. 순진한 구현과 선택 구현이 <b>똑같이</b> 쓴다. 두 구현의 차이는 TX1·TX2 에만 있다.
 *
 * <p>각 단계가 독립된 {@code REQUIRES_NEW} 트랜잭션이다 (규칙 16). 중복 키 예외가 난 트랜잭션에서
 * 기존 행을 읽지 않는다 — 실패한 INSERT 의 트랜잭션은 롤백하고, 새 트랜잭션에서 읽는다.
 *
 * <p>호출자는 <b>바깥 트랜잭션 없이</b> 불러야 한다. 바깥 트랜잭션이 있으면 {@code REQUIRES_NEW} 가
 * 그 커넥션을 쥔 채 두 번째 커넥션을 잡는다 — 풀 고갈 시 서로를 기다리는 구조가 된다.
 */
final class Task2iaKeyAcquisition {

    /** 확보 결과. */
    sealed interface Result {
        /** 이 시도가 키를 소유한다. 세대와 fence 를 TX1·TX2 의 모든 기록에 그대로 넘긴다. */
        record Owned(KeyOwnership ownership) implements Result { }
        record Replay(StoredHoldOutcome outcome) implements Result { }
        record InProgress() implements Result { }
        record Mismatch() implements Result { }
    }

    /**
     * 삽입·조회·인수 사이에 행이 바뀌면 처음부터 다시 본다. 그 횟수의 상한.
     * 넘으면 "진행 중" 으로 답한다 — 실행하지 않는 쪽으로 실패한다.
     */
    private static final int MAX_ROUNDS = 3;

    private final Task2iaIdempotencyStore store;
    private final TransactionTemplate requiresNew;
    private final Duration lease;

    Task2iaKeyAcquisition(
            Task2iaIdempotencyStore store, PlatformTransactionManager transactionManager, Duration lease) {
        this.store = Objects.requireNonNull(store, "store");
        this.requiresNew = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.lease = Objects.requireNonNull(lease, "lease");
    }

    Result acquire(IdempotencyScope scope, String fingerprint) {
        for (int round = 0; round < MAX_ROUNDS; round++) {
            Optional<KeyOwnership> inserted = tryInsert(scope, fingerprint);
            if (inserted.isPresent()) {
                return new Result.Owned(inserted.get());
            }
            Optional<IdempotencyRecord> existing = requiresNew.execute(status -> store.find(scope));
            if (existing == null || existing.isEmpty()) {
                continue; // 보존 기간 삭제 등으로 사라졌다. 다시 넣어 본다.
            }
            IdempotencyRecord current = existing.get();
            switch (IdempotencyDecision.decide(current, fingerprint)) {
                case MISMATCH -> {
                    return new Result.Mismatch();
                }
                case REPLAY -> {
                    return new Result.Replay(current.outcome());
                }
                case IN_PROGRESS -> {
                    return new Result.InProgress();
                }
                case TAKE_OVER -> {
                    Optional<KeyOwnership> taken = requiresNew.execute(
                            status -> store.takeOver(scope, current.ownership(), lease));
                    if (taken != null && taken.isPresent()) {
                        return new Result.Owned(taken.get());
                    }
                    // 남이 먼저 인수했거나, 그사이 완료됐거나, 삭제·재생성돼 세대가 바뀌었다. 다시 읽는다.
                }
            }
        }
        return new Result.InProgress();
    }

    private Optional<KeyOwnership> tryInsert(IdempotencyScope scope, String fingerprint) {
        try {
            return Optional.ofNullable(requiresNew.execute(status -> store.insert(scope, fingerprint, lease)));
        } catch (DuplicateKeyException alreadyExists) {
            return Optional.empty();
        }
    }
}
