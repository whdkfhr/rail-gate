package com.railgate.reservation.seat;

/**
 * 요청한 좌석 집합 중 <b>하나 이상을 선점할 수 없다.</b>
 *
 * <p>I-9 에 따라 요청은 전부-또는-전무다. 하나가 불가능하면 <b>요청 전체가 실패</b>하며,
 * 요청자는 어떤 좌석도 점유하지 않는다. "3석 중 2석 성공" 같은 결과는 존재하지 않는다.
 *
 * <h2>오류가 아니라 예상 가능한 좌석 경합이다</h2>
 *
 * <p>같은 좌석을 여러 사람이 동시에 원하는 것은 이 도메인의 정상 상태다.
 * 경합이 심하면 대부분의 요청이 이렇게 끝난다. 실패율이 높다는 것이 곧 결함을 뜻하지 않는다.
 *
 * <h2>원인을 좌석별로 구분하지 않는다</h2>
 *
 * <p>"존재하지 않는 좌석", "이미 남이 잡은 좌석", "그 사이 상태가 바뀐 좌석" 을
 * 구분해서 알려주지 않는다. 요청자에게는 셋 다 같은 결과이며, 구분해 노출하면
 * <b>특정 좌석의 점유 여부를 탐색하는 수단</b>이 된다.
 */
public class SeatUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SeatUnavailableException(String message) {
        super(message);
    }
}
