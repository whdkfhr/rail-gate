package com.railgate.reservation.quota;

/**
 * quota 확보(증가) 시도의 결과.
 *
 * <p>{@code boolean} 대신 이름을 붙이는 이유는 {@code SeatHoldOutcome} 과 같다 —
 * 호출부에서 {@code if (quota.tryAcquire(...))} 가 무엇을 뜻하는지 읽히지 않는다.
 *
 * <p><b>이 타입은 HTTP 를 모른다.</b> 응답 코드는 API 계층이 결정할 문제이고,
 * 그 계층은 아직 없다. 포트의 반환 타입에 특정 상태 코드를 적어 두면
 * 결정하지 않은 것을 결정한 것처럼 보이게 된다.
 */
public enum QuotaAcquireOutcome {

    /** 이 요청이 좌석 몫을 확보했다. {@code affected_rows = 1}. */
    ACQUIRED,

    /**
     * 확보되지 않았다. <b>오류가 아니라 정상 동작이다.</b>
     *
     * <p><b>두 경우를 함께 뜻한다. 이 타입만으로는 구분되지 않는다.</b>
     * <ul>
     *   <li>상한(P-2: 4석)을 넘는 요청</li>
     *   <li>카운터 행이 없는 경우 — <b>확보 연산은 행을 자동 생성하지 않는다</b></li>
     * </ul>
     *
     * <p>둘을 구분해야 하는 호출자는 확보를 시도하기 전에 행의 존재를 스스로 확정해야 한다.
     * 예를 들어 같은 트랜잭션에서 {@link UserHoldQuotaPort#ensureRow} 를 먼저 부르면
     * 행이 있음이 보장되므로, 이 결과를 <b>상한 초과로 해석할 전제</b>가 마련된다.
     */
    LIMIT_EXCEEDED
}
