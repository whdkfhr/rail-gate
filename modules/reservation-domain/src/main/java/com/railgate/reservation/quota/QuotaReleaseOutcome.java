package com.railgate.reservation.quota;

/**
 * quota 감소 시도의 결과.
 *
 * <p>감소량은 요청 좌석 수가 아니라 <b>실제로 변경된 좌석 행 수</b>여야 한다
 * (TASK-002G-C). 후보 수로 줄이면 stale 후보만큼 카운터가 과소해진다.
 *
 * <p><b>이 타입은 HTTP 를 모른다.</b> 응답 코드는 API 계층이 결정할 문제이고 그 계층은 아직 없다.
 */
public enum QuotaReleaseOutcome {

    /** 카운터가 그만큼 줄었다. */
    RELEASED,

    /**
     * 줄이지 못했다. <b>정상 흐름에서는 일어나지 않으며 drift 신호다.</b>
     *
     * <p><b>두 경우를 함께 뜻한다. 이 타입만으로는 구분되지 않는다.</b>
     * <ul>
     *   <li>카운터 행이 없다 — 좌석은 그 사용자 것인데 카운터가 없다</li>
     *   <li>음수가 될 요청이다 — 이중 감소이거나 카운터가 실제보다 작다</li>
     * </ul>
     *
     * <p>둘을 구분해야 하는 호출자는 감소 전에 같은 트랜잭션에서
     * {@link UserHoldQuotaPort#lockRow} 로 행의 존재와 값을 먼저 확정한다.
     * 한 번의 조건부 UPDATE 로는 구분할 수 없고, 구분하려고 다시 조회하면 그 사이가 또 열린다.
     */
    REJECTED
}
