package com.railgate.reservation.application.hold.idempotency;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.application.hold.HoldSeatsResult;
import com.railgate.reservation.application.hold.QuotaExceededException;
import com.railgate.reservation.saleevent.SaleEventId;
import com.railgate.reservation.saleevent.SaleEventScopeException;
import com.railgate.reservation.seat.SeatUnavailableException;
import java.util.Objects;
import java.util.Optional;

/**
 * 멱등키에 <b>저장해 재생하는</b> 선점 결과. 성공과 업무 실패 두 가지뿐이다.
 *
 * <h2>무엇을 저장하고 무엇을 저장하지 않는가</h2>
 *
 * <ul>
 *   <li><b>성공</b> — {@code holdId}·판매 회차·좌석 수. 재요청에 그대로 돌려준다 (규칙 17)</li>
 *   <li><b>업무 실패</b> — 좌석 경합·상한 초과·회차 해석 거부. 본 작업 트랜잭션이 롤백돼
 *       <b>상태 변경이 없다는 것이 확실한</b> 결정적 결과다. 같은 키의 재요청은 다시 실행하지 않고
 *       같은 실패를 받는다. 좌석이 나중에 비더라도 마찬가지다 — 다시 시도하려면 새 키를 쓴다</li>
 *   <li><b>재시도 가능한 장애</b>(잠금 대기 초과·데드락·연결 실패·알 수 없는 예외) — 저장하지
 *       않는다. 커밋 여부가 불확실할 수 있기 때문이다. 키는 리스를 즉시 만료시켜 넘겨준다</li>
 * </ul>
 *
 * <p>HTTP 상태 코드와 응답 본문 직렬화는 <b>계약일 뿐 구현이 없다</b> (HTTP 계층 미구현).
 */
sealed interface StoredHoldOutcome {

    record Succeeded(HoldId holdId, SaleEventId saleEventId, int seatCount)
            implements StoredHoldOutcome {

        public Succeeded {
            Objects.requireNonNull(holdId, "holdId");
            Objects.requireNonNull(saleEventId, "saleEventId");
        }

        static Succeeded from(HoldSeatsResult result) {
            return new Succeeded(result.holdId(), result.saleEventId(), result.seatCount());
        }
    }

    record Rejected(RejectionCode code) implements StoredHoldOutcome {

        public Rejected {
            Objects.requireNonNull(code, "code");
        }
    }

    enum RejectionCode {
        /** 좌석 경합. 정상 흐름 (규칙 21). */
        SEAT_UNAVAILABLE,
        /** 1인당 상한 초과 (I-12). */
        QUOTA_EXCEEDED,
        /** 없는 좌석·서로 다른 회차 혼합. */
        SALE_EVENT_SCOPE_REJECTED
    }

    /**
     * 본 작업이 던진 예외 중 <b>저장할 업무 실패</b>만 고른다. 나머지는 빈 값 — 재시도 가능한 장애다.
     */
    static Optional<Rejected> rejectionOf(RuntimeException failure) {
        if (failure instanceof SeatUnavailableException) {
            return Optional.of(new Rejected(RejectionCode.SEAT_UNAVAILABLE));
        }
        if (failure instanceof QuotaExceededException) {
            return Optional.of(new Rejected(RejectionCode.QUOTA_EXCEEDED));
        }
        if (failure instanceof SaleEventScopeException) {
            return Optional.of(new Rejected(RejectionCode.SALE_EVENT_SCOPE_REJECTED));
        }
        return Optional.empty();
    }
}
