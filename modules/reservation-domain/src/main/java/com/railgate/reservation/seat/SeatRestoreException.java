package com.railgate.reservation.seat;

/**
 * 저장된 좌석 데이터가 도메인이 만들 수 없는 조합이라 복원을 거부했다.
 *
 * <p>이 예외를 {@link SeatAlreadyHeldException}(경합, 409) 이나
 * {@code IllegalArgumentException}(잘못된 사용자 입력, 400) 과 구분하는 이유는
 * <b>원인과 대응이 다르기 때문이다.</b> 이것은 사용자 입력 문제가 아니라
 * 저장소에 정합성이 깨진 행이 존재한다는 신호다.
 *
 * <p>예를 들어 {@code SOLD} 인데 {@code reservation_id} 가 없는 행은
 * INVARIANTS.md 의 정합성 검증 쿼리 V-5(고아 상태)가 잡아내는 데이터다.
 * 배치가 아니라 조회 시점에 즉시 드러나게 하는 것이 이 예외의 목적이다.
 *
 * <p>앱 계층은 이 예외를 5xx 로 매핑하고 알람 대상으로 삼는다.
 */
public class SeatRestoreException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SeatRestoreException(String message) {
        super(message);
    }
}
