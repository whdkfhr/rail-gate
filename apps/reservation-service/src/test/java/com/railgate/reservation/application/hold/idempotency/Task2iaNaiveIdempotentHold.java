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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ★ <b>RED 대조군 — 규칙 16 을 글자 그대로 구현한 순진한 3단 트랜잭션.</b>
 *
 * <pre>
 *   TX0 키 확보(REQUIRES_NEW) → TX1 본 작업 → TX2 결과 기록(REQUIRES_NEW, fence 검사)
 * </pre>
 *
 * <p>fencing token 은 <b>TX2 에서만</b> 확인한다. 이 구현이 깨지는 곳:
 *
 * <ul>
 *   <li><b>TX1 커밋 후 TX2 전에 죽으면</b> 선점은 남았는데 키는 {@code IN_PROGRESS} 다. 리스가 만료되면
 *       인수자가 본 작업을 <b>다시 실행한다.</b> 좌석이 자신의 홀드로 잡혀 있으면 경합 거절이
 *       "최초 결과" 로 기록되고(결과 유실), 그사이 홀드가 풀렸으면 <b>두 번째 선점이 커밋된다</b>
 *       (상태 변경 2회, I-18 위반).</li>
 *   <li><b>TX2 의 fence 검사는 TX1 의 중복 실행을 막지 못한다.</b> 리스를 빼앗긴 구 작업자의 TX1 은
 *       아무 검사 없이 커밋되고, TX2 에서야 거절된다. 그때는 상태 변경이 이미 남았다.</li>
 * </ul>
 *
 * <p>운영 코드가 아니다. 선택 구현은 {@link Task2iaAtomicCompletionIdempotentHold}.
 */
final class Task2iaNaiveIdempotentHold {

    private final HoldSeatsService holdSeats;
    private final Task2iaIdempotencyStore store;
    private final Task2iaKeyAcquisition acquisition;
    private final TransactionTemplate tx1;
    private final TransactionTemplate tx2;
    private final Task2iaFaultPoints faults;

    Task2iaNaiveIdempotentHold(
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
        IdempotencyScope scope = IdempotencyScope.holdSeats(command.userId(), key);
        String fingerprint = HoldRequestFingerprint.of(command);

        Task2iaKeyAcquisition.Result acquired = acquisition.acquire(scope, fingerprint);
        if (!(acquired instanceof Task2iaKeyAcquisition.Result.Owned owned)) {
            return Task2iaAtomicCompletionIdempotentHold.respondWithoutExecuting(acquired);
        }
        faults.at(Point.AFTER_TX0);

        StoredHoldOutcome outcome;
        try {
            HoldSeatsResult result = tx1.execute(status -> {
                faults.at(Point.TX1_BEFORE_WORK);
                HoldSeatsResult held = holdSeats.hold(command);
                faults.at(Point.TX1_BEFORE_COMMIT);
                return held;
            });
            outcome = Succeeded.from(Objects.requireNonNull(result));
        } catch (RuntimeException failure) {
            Optional<Rejected> rejected = StoredHoldOutcome.rejectionOf(failure);
            if (rejected.isEmpty()) {
                throw failure;
            }
            outcome = rejected.get();
        }
        faults.at(Point.AFTER_TX1_COMMIT);

        faults.at(Point.BEFORE_TX2);
        StoredHoldOutcome recorded = outcome;
        tx2.executeWithoutResult(status -> {
            faults.at(Point.IN_TX2);
            // fence 를 여기서만 본다. 0 이어도 TX1 은 이미 커밋됐다 — 순진한 구현은 그것을 모른 채 응답한다.
            if (recorded instanceof Succeeded succeeded) {
                store.completeSucceeded(scope, owned.fence(), succeeded);
            } else {
                store.completeRejected(scope, owned.fence(), (Rejected) recorded);
            }
        });
        return new Task2iaHoldResponse.Executed(outcome);
    }
}
