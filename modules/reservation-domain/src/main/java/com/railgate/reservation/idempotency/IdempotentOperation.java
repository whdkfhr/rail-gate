package com.railgate.reservation.idempotency;

/**
 * 멱등키 범위의 연산 부분. 같은 사용자의 같은 키라도 연산이 다르면 다른 키다.
 *
 * <p><b>지금은 선점 하나뿐이다.</b> 해제·확정·결제 시작에 같은 계약을 적용할지는 아직 정하지 않았다
 * (ADR-009 §6). 값을 미리 늘리지 않는다. 저장 값은 {@link #name()} 이다.
 */
public enum IdempotentOperation {
    HOLD_SEATS
}
