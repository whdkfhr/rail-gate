package com.railgate.reservation;

/** 확정된 예약의 식별자. 좌석이 SOLD 가 될 때 어느 예약에 귀속되는지를 가리킨다. */
public record ReservationId(long value) {

    public ReservationId {
        if (value <= 0) {
            throw new IllegalArgumentException("reservationId 는 양수여야 한다: " + value);
        }
    }
}
