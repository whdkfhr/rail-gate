package com.railgate.reservation.infra.saleevent;

/**
 * 조건부 상태 전이 UPDATE 의 결과.
 *
 * <p>{@code boolean} 대신 이름을 붙인 이유는 {@code SeatHoldOutcome} 과 같다 —
 * 호출부에서 {@code if (repository.open(id))} 가 무엇을 뜻하는지 읽히지 않는다.
 *
 * <p><b>{@link #NOT_APPLIED} 는 오류가 아니다.</b> 아직 오픈 시각이 아니거나, 이미 그 상태이거나,
 * 다른 요청이 먼저 전이시켰다는 뜻이다. 원인을 구분하지 않는 이유는 구분하려면
 * 다시 조회해야 하고 그 사이 상태가 또 바뀔 수 있기 때문이다 — 갱신을 수행한 그 문장이
 * 몇 행을 바꿨는지가 그 시점의 유일하게 신뢰할 수 있는 사실이다.
 */
public enum SaleEventTransitionOutcome {

    /** 이 요청이 전이를 수행했다. {@code affected_rows = 1}. */
    APPLIED,

    /** 전이가 일어나지 않았다. 정상 동작일 수 있다. */
    NOT_APPLIED
}
