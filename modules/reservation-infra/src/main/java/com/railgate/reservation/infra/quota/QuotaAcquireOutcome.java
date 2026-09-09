package com.railgate.reservation.infra.quota;

/**
 * quota 확보(증가) 시도의 결과.
 *
 * <p>{@code boolean} 대신 이름을 붙이는 이유는 {@code SeatHoldOutcome} 과 같다 —
 * 호출부에서 {@code if (quota.tryAcquire(...))} 가 무엇을 뜻하는지 읽히지 않는다.
 */
public enum QuotaAcquireOutcome {

    /** 이 요청이 좌석 몫을 확보했다. {@code affected_rows = 1}. */
    ACQUIRED,

    /**
     * 확보되지 않았다. <b>오류가 아니라 정상 동작이다</b> (429 로 매핑될 자리, CLAUDE.md 규칙 22).
     *
     * <p>두 경우를 함께 뜻한다.
     * <ul>
     *   <li>상한(P-2: 4석)을 넘는 요청</li>
     *   <li>카운터 행이 없는 경우 — <b>자동 생성하지 않는다</b></li>
     * </ul>
     *
     * <p>둘을 구분하려면 {@link JdbcUserHoldQuotaRepository#lockRow} 를 먼저 부른다.
     * 잠금 순서상(quota → seat) 어차피 먼저다.
     */
    LIMIT_EXCEEDED
}
