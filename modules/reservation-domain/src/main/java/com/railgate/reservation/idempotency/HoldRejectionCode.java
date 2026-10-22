package com.railgate.reservation.idempotency;

/**
 * 선점의 <b>저장할 업무 실패</b> 종류. 재생 대상이다 (IDEMPOTENCY_CONTRACT.md §4).
 *
 * <p>예외를 이 값으로 분류하는 일은 애플리케이션 계층의 몫이다. 잠금 대기 초과·연결 실패 같은
 * 재시도 가능한 장애는 여기 없다 — 저장하지 않는다. 저장 값은 {@link #name()} 이다.
 */
public enum HoldRejectionCode {
    /** 좌석 경합. 정상 흐름 (규칙 21). */
    SEAT_UNAVAILABLE,
    /** 1인당 상한 초과 (I-12). */
    QUOTA_EXCEEDED,
    /** 없는 좌석·서로 다른 회차 혼합. */
    SALE_EVENT_SCOPE_REJECTED
}
