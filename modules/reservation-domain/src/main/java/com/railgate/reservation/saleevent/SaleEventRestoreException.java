package com.railgate.reservation.saleevent;

/**
 * 저장된 판매 회차 데이터가 도메인이 만들 수 없는 조합이라 복원을 거부했다.
 *
 * <p>{@code SeatRestoreException} 과 같은 이유로 {@code IllegalArgumentException}
 * (잘못된 사용자 입력, 400) 과 구분한다. <b>이것은 입력 문제가 아니라 저장소에
 * 정합성이 깨진 행이 있다는 신호다.</b> 배치가 아니라 조회 시점에 즉시 드러나게 한다.
 *
 * <p>잘못된 회차는 quota 의 의미까지 흔든다. I-12 의 범위가 이 식별자 단위이기 때문이다.
 */
public class SaleEventRestoreException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SaleEventRestoreException(String message) {
        super(message);
    }
}
