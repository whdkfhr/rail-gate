package com.railgate.reservation.seat;

/**
 * 좌석 재고의 상태.
 *
 * <pre>
 * AVAILABLE ──hold──▶ HELD ──startPayment──▶ PAYING ──confirm──▶ SOLD
 *     ▲                │                       │
 *     └──release/expire┴───────────────────────┘
 * </pre>
 *
 * <p>PAYING 을 별도 상태로 두는 이유: 결제가 진행되는 동안 만료 데드라인을 연장해야 하고,
 * 만료 스위퍼가 "결제 중" 과 "단순 선점" 을 구분해 다룰 수 있어야 하기 때문이다.
 *
 * <p>SOLD 는 터미널이다. 취소 후 재판매(SOLD → AVAILABLE)는 Phase 5 에서 다룬다.
 */
public enum SeatStatus {

    /** 판매 가능. */
    AVAILABLE,

    /** 선점됨. 만료 시각이 있다. */
    HELD,

    /** 결제 진행 중. 만료 시각이 결제 데드라인까지 연장된다. */
    PAYING,

    /** 확정 판매됨. */
    SOLD;

    boolean isHoldable() {
        return this == AVAILABLE;
    }

    /** 만료 또는 자발적 해제로 판매 가능 상태로 돌아갈 수 있는가. */
    boolean isReleasable() {
        return this == HELD || this == PAYING;
    }
}
