package com.railgate.reservation.application.idempotency;

import com.railgate.reservation.application.hold.QuotaExceededException;
import com.railgate.reservation.idempotency.HoldRejectionCode;
import com.railgate.reservation.idempotency.StoredHoldOutcome.Rejected;
import com.railgate.reservation.saleevent.SaleEventScopeException;
import com.railgate.reservation.seat.SeatUnavailableException;
import java.util.Objects;
import java.util.Optional;

/**
 * 선점 예외를 <b>저장할 업무 실패</b>로 바꾼다. 이 책임은 애플리케이션 계층에 있다 — 도메인·저장소는 예외를 모른다.
 *
 * <h2>★ 계약의 세 예외만, 던져진 예외 자체만 본다</h2>
 *
 * <p>업무 실패로 저장하면 <b>같은 키로는 다시 실행되지 않는다</b> (최종 결과). 그러므로 잘못 분류하는 비용이 크다.
 *
 * <ul>
 *   <li>{@link SeatUnavailableException} → {@code SEAT_UNAVAILABLE}</li>
 *   <li>{@link QuotaExceededException} → {@code QUOTA_EXCEEDED}</li>
 *   <li>{@link SaleEventScopeException} → {@code SALE_EVENT_SCOPE_REJECTED}</li>
 * </ul>
 *
 * <p><b>원인 사슬을 뒤지지 않는다.</b> DB 오류(잠금 대기 초과·연결 장애 등)에 업무 예외가 원인으로 붙어 있어도
 * 그것은 장애다 — 커밋 여부가 불확실할 수 있으므로 결과로 굳히지 않는다. 나머지는 모두 빈 값이고, 호출자는
 * 결과를 저장하지 않고 리스를 포기한다.
 */
final class HoldRejectionClassifier {

    private HoldRejectionClassifier() {
    }

    static Optional<Rejected> classify(RuntimeException failure) {
        Objects.requireNonNull(failure, "failure");
        if (failure instanceof SeatUnavailableException) {
            return Optional.of(new Rejected(HoldRejectionCode.SEAT_UNAVAILABLE));
        }
        if (failure instanceof QuotaExceededException) {
            return Optional.of(new Rejected(HoldRejectionCode.QUOTA_EXCEEDED));
        }
        if (failure instanceof SaleEventScopeException) {
            return Optional.of(new Rejected(HoldRejectionCode.SALE_EVENT_SCOPE_REJECTED));
        }
        return Optional.empty();
    }
}
