package com.railgate.reservation.saleevent;

/**
 * 전이표에 정의되지 않은 판매 회차 상태 전이를 시도했다.
 *
 * <p>{@code SeatStateTransitionException} 과 같은 성격이다. <b>규칙 위반이며 대개 버그다.</b>
 * 좌석 경합처럼 정상 흐름에서 발생하는 실패가 아니므로 앱 계층에서 5xx 로 매핑하고
 * 알람 대상으로 삼는다 (409 로 매핑하지 않는다).
 */
public class SaleEventStateTransitionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SaleEventStateTransitionException(String message) {
        super(message);
    }

    static SaleEventStateTransitionException of(SaleEventId id, SaleEventStatus from, String action) {
        return new SaleEventStateTransitionException(
                "판매 회차 %d 는 %s 상태에서 %s 할 수 없다".formatted(id.value(), from, action));
    }
}
