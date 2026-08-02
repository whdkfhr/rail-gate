package com.railgate.reservation.infra.seat;

/**
 * 좌석 선점 시도의 결과.
 *
 * <p><b>왜 예외가 아니라 반환값인가.</b>
 * 경합 실패는 오류가 아니라 정상 동작이다. 명절 예매처럼 경합이 심한 상황에서는
 * 대부분의 요청이 여기에 해당하며, 앱 계층은 이를 409 로 매핑하고
 * 오류율이나 ERROR 로그에 포함하지 않는다 (CLAUDE.md 규칙 21).
 * 정상 흐름을 예외로 표현하면 지표가 거짓이 되고, 스택 트레이스 생성 비용도 헛되이 든다.
 */
public enum SeatHoldOutcome {

    /** 선점에 성공했다. 조건부 UPDATE 의 {@code affected_rows} 가 1 이었다. */
    HELD,

    /**
     * 다른 요청이 먼저 가져갔거나 판매 가능한 상태가 아니다.
     * {@code affected_rows} 가 0 이었다는 뜻이며, 좌석이 없는 경우도 여기에 포함된다.
     */
    ALREADY_HELD
}
