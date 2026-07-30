package com.railgate.reservation.seat;

/**
 * 전이표에 정의되지 않은 상태 전이를 시도했다 (I-14).
 *
 * <p><b>이것은 규칙 위반이며 대개 버그다.</b> 경합 실패인 {@link SeatAlreadyHeldException} 와 달리
 * 정상 흐름에서 발생해서는 안 된다. 앱 계층에서 5xx 로 매핑하고 알람 대상으로 삼는다.
 */
public class SeatStateTransitionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SeatStateTransitionException(String message) {
        super(message);
    }

    static SeatStateTransitionException of(SeatId seatId, SeatStatus from, String action) {
        return new SeatStateTransitionException(
                "좌석 %d 는 %s 상태에서 %s 할 수 없다".formatted(seatId.value(), from, action));
    }
}
