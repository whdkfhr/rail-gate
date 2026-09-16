package com.railgate.reservation.application.hold;

import com.railgate.reservation.UserId;
import com.railgate.reservation.hold.SeatReleasePort;
import com.railgate.reservation.quota.QuotaReleaseOutcome;
import com.railgate.reservation.quota.UserHoldQuotaPort;
import com.railgate.reservation.saleevent.SaleEventId;
import com.railgate.reservation.saleevent.SaleEventScopeException;
import com.railgate.reservation.saleevent.SaleEventScopePort;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 홀드 자발적 해제 유스케이스 (FR-2.5). <b>이 클래스가 트랜잭션을 소유한다.</b>
 *
 * <h2>흐름</h2>
 *
 * <ol>
 *   <li><b>회차 해석</b> — 활성 홀드의 좌석으로부터. <b>잠금 없는 조회</b>이며 스냅숏이다</li>
 *   <li><b>quota 행 잠금</b> — {@code SELECT ... FOR UPDATE}. 좌석보다 먼저다</li>
 *   <li><b>좌석 해제</b> — 저장소가 후보를 다시 읽고 PK 오름차순 조건부 UPDATE 로 푼다</li>
 *   <li><b>quota 감소</b> — 3 의 <b>실제 {@code affected_rows}</b> 만큼. 0 이면 호출하지 않는다</li>
 * </ol>
 *
 * <h2>★ 잠금 순서 — quota → seat</h2>
 *
 * <p>선점 경로(TASK-002G-B, 2H-A)와 같다. 1 이 잠금 없는 조회여야 하는 이유가 여기 있다 —
 * 회차를 알기 위해 좌석을 먼저 잠그면 순서가 뒤집혀 선점 경로와 교차한다.
 * 좌석들 <b>사이</b>의 순서는 저장소가 PK 오름차순으로 유지한다 (규칙 2).
 *
 * <h2>★ 스냅숏과 최종 판단은 다르다</h2>
 *
 * <p>1 의 결과는 그 시점의 사실이다. 그 뒤 quota 잠금을 기다리는 동안 확정·만료·재선점이
 * 일어날 수 있다. 그래서 <b>감소량은 1 의 좌석 수가 아니라 3 의 실제 변경 수</b>다
 * (TASK-002G-C). 스냅숏에 3석이 있었는데 1석이 그 사이 SOLD 가 됐다면 2석만 풀리고
 * quota 도 2만 준다 — 후보 수와 해제 수가 다른 것은 오류가 아니다.
 *
 * <p>같은 이유로 <b>전부-또는-전무가 아니다.</b> 선점(I-9)과 반대다. 후보 하나가 stale 하다고
 * 나머지를 되돌리면 사용자는 아무것도 놓지 못한다.
 *
 * <h2>★ 감소 경로는 행을 만들지도 고치지도 않는다</h2>
 *
 * <p>{@code ensureRow} 를 부르지 않는다. 좌석을 실제로 풀었는데 카운터가 없거나 감소가
 * 거절되면 {@link QuotaInconsistencyException} 으로 전파해 <b>좌석 해제까지 롤백</b>시킨다.
 * 조용히 넘기면 drift 의 증거가 사라진다. 예외를 삼키거나 독립 커밋하지 않는다.
 *
 * <h2>★ 반환값은 아직 최종 커밋이 아닐 수 있다</h2>
 *
 * <p>{@code REQUIRED} 로 참여하므로 바깥 트랜잭션이 있으면 그 안의 결과다.
 * 바깥이 롤백하면 해제도 감소도 함께 사라진다. {@link ReleaseHoldResult} 참고.
 *
 * <h2>이 서비스가 아직 하지 않는 것</h2>
 *
 * <ul>
 *   <li><b>확정·만료 경로의 quota 감소</b> — 여전히 미연동. <b>따라서 I-12 는 아직
 *       운영 활성화 전이다.</b> 선점·해제 두 경로만 연결됐다</li>
 *   <li><b>REST API</b>(규칙 18 의 204 매핑), <b>멱등키</b>, <b>감사 로그</b>(규칙 32),
 *       <b>메트릭</b>(규칙 35)</li>
 * </ul>
 */
@Service
public class ReleaseHoldService {

    private final SaleEventScopePort saleEventScope;
    private final UserHoldQuotaPort quota;
    private final SeatReleasePort seats;

    public ReleaseHoldService(
            SaleEventScopePort saleEventScope, UserHoldQuotaPort quota, SeatReleasePort seats) {
        this.saleEventScope = Objects.requireNonNull(saleEventScope, "saleEventScope");
        this.quota = Objects.requireNonNull(quota, "quota");
        this.seats = Objects.requireNonNull(seats, "seats");
    }

    /**
     * 홀드를 해제하고 quota 를 같은 트랜잭션에서 줄인다.
     *
     * @return 실제로 해제된 좌석 수. <b>0 은 성공 no-op 이며 원인을 구분하지 않는다</b>
     * @throws SaleEventScopeException     활성 좌석이 둘 이상의 회차에 걸친 비정상 데이터.
     *                                     쓰기 전에 거절한다
     * @throws QuotaInconsistencyException 좌석을 풀었는데 카운터가 없거나 감소가 거절됨.
     *                                     좌석 해제까지 롤백된다
     * @throws IllegalStateException       저장소가 트랜잭션에 참여하지 못한 경우 (배선 실수)
     */
    @Transactional
    public ReleaseHoldResult release(ReleaseHoldCommand command) {
        Objects.requireNonNull(command, "command");
        UserId userId = command.userId();

        // 1. 잠금 없는 스냅숏. 회차 혼합은 여기서 거절된다 — 아무것도 쓰기 전이다.
        Optional<SaleEventId> scope = saleEventScope.resolveActiveHold(command.holdId(), userId);
        if (scope.isEmpty()) {
            // 활성 좌석이 없다. quota 를 잠그지도 줄이지도 않는다.
            return ReleaseHoldResult.NONE;
        }
        SaleEventId saleEventId = scope.get();

        // 2. quota 를 좌석보다 먼저 잠근다. 행이 없어도 여기서는 판단하지 않는다 —
        //    실제로 풀린 좌석이 있을 때만 정합성 문제가 된다.
        Optional<Integer> counter = quota.lockRow(saleEventId, userId);

        // 3. 저장소가 후보를 다시 읽고 조건부 UPDATE 로 푼다. 반환값이 실제 변경 수다.
        int released = seats.releaseAll(command.holdId(), userId);
        if (released == 0) {
            // 스냅숏과 달리 지금은 풀 것이 없다. 감소도 없다.
            return ReleaseHoldResult.NONE;
        }

        // 4. 실제 변경 수만큼만 줄인다. 행을 만들거나 값을 보정하지 않는다.
        if (counter.isEmpty()) {
            throw new QuotaInconsistencyException(
                    "사용자 %d 의 홀드 %s 에서 %d석을 해제했는데 판매 회차 %d 의 quota 행이 없다. 롤백한다"
                            .formatted(userId.value(), command.holdId().asString(),
                                    released, saleEventId.value()));
        }
        if (quota.release(saleEventId, userId, released) != QuotaReleaseOutcome.RELEASED) {
            throw new QuotaInconsistencyException(
                    "사용자 %d 의 홀드 %s 에서 %d석을 해제했는데 quota 감소가 거절됐다 (현재 %d). 롤백한다"
                            .formatted(userId.value(), command.holdId().asString(),
                                    released, counter.get()));
        }
        return new ReleaseHoldResult(released);
    }
}
