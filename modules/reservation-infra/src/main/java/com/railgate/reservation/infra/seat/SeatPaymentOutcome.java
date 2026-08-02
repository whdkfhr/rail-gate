package com.railgate.reservation.infra.seat;

/**
 * 결제 시작({@code HELD → PAYING}) 시도의 결과.
 *
 * <p>예외가 아니라 반환값인 이유는 {@link SeatHoldOutcome} 과 같다.
 * 실패는 오류가 아니라 정상 동작이며 앱 계층이 409 로 매핑한다 (CLAUDE.md 규칙 21).
 */
public enum SeatPaymentOutcome {

    /** 결제 시작에 성공했다. {@code affected_rows} 가 1 이었다. */
    STARTED,

    /**
     * 결제를 시작하지 못했다. {@code affected_rows} 가 0 이었다는 뜻이며,
     * 원인은 넷 중 하나다.
     * <ul>
     *   <li>좌석이 {@code HELD} 가 아니다 (이미 결제 중이거나 팔렸거나 판매 가능하다)</li>
     *   <li>{@code hold_id} 가 다르다 (남의 홀드다)</li>
     *   <li>선점이 이미 만료됐다</li>
     *   <li>좌석이 존재하지 않는다</li>
     * </ul>
     * 어느 쪽인지 구분하려면 추가 조회가 필요하지만, 그 조회는 또 하나의 창을 만들 뿐이므로
     * 하지 않는다. 호출자에게 필요한 것은 "내 것이 되었는가" 하나다.
     */
    NOT_STARTED
}
