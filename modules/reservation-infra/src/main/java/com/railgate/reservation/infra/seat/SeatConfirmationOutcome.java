package com.railgate.reservation.infra.seat;

/**
 * 좌석 확정({@code PAYING → SOLD}) 시도의 결과.
 *
 * <p><b>이 결과는 예약이 유효하다는 뜻이 아니다.</b> 좌석 재고 행이 이 예약에 귀속되었다는
 * 사실만 말한다. 결제 승인 검증(I-15)은 {@code payment} 테이블과 PG 연동이 붙어야 성립하며
 * 후속 Task 다.
 */
public enum SeatConfirmationOutcome {

    /** 좌석이 이 예약으로 확정됐다. {@code affected_rows} 가 1 이었다. */
    CONFIRMED,

    /**
     * 확정하지 못했다. {@code affected_rows} 가 0 이었다는 뜻이며, 원인은 셋 중 하나다.
     * <ul>
     *   <li>좌석이 {@code PAYING} 이 아니다 (아직 결제를 시작하지 않았거나 이미 팔렸거나 만료됐다)</li>
     *   <li>{@code hold_id} 가 다르다 (남의 홀드다)</li>
     *   <li>좌석이 존재하지 않는다</li>
     * </ul>
     *
     * <p><b>이미 확정된 좌석에 같은 요청을 다시 보내도 이 값이 돌아온다.</b>
     * 이는 저장소 CAS 의 결과일 뿐 API 멱등성이 아니다. 재요청에 최초 응답을 그대로
     * 돌려주는 것은 멱등성 키 인프라의 책임이다 (CLAUDE.md 규칙 17). 여기서 보장하는 것은
     * "이미 팔린 좌석의 주인이 바뀌지 않는다" 하나다.
     */
    NOT_CONFIRMED
}
