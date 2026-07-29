package com.railgate.reservation.seat;

/**
 * 다른 사용자가 이미 좌석을 잡고 있어 선점에 실패했다.
 *
 * <p><b>이것은 정상 동작이다.</b> 경합이 심한 예매에서는 대부분의 요청이 이 예외를 만난다.
 * 앱 계층에서 409 로 매핑하며 ERROR 로그 대상이 아니다 (CLAUDE.md 규칙 21).
 */
public class SeatAlreadyHeldException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SeatAlreadyHeldException(String message) {
        super(message);
    }
}
