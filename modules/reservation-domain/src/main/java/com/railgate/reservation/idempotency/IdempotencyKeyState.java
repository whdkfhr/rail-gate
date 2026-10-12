package com.railgate.reservation.idempotency;

/** 키 행의 상태. 저장 값은 {@link #name()} 이다. */
public enum IdempotencyKeyState {
    /** 어떤 시도가 리스를 쥐고 실행 중이거나, 실행 중 사라졌다. 결과가 없다. */
    IN_PROGRESS,
    /** 결과가 확정됐다. 이후 같은 요청은 재생만 한다. 리스가 없다. */
    COMPLETED
}
