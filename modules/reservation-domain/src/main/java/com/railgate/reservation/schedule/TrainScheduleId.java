package com.railgate.reservation.schedule;

/**
 * 열차 운행편 식별자. {@code seat_inventory.schedule_id} 가 가리키는 대상이다.
 *
 * <p><b>{@code SaleEventId} 와 달리 Comparable 이 아니다.</b> 이 값은 잠금 키가 아니기 때문이다.
 * 잠금 순서를 정하는 것은 quota 키 {@code (saleEventId, userId)} 와 좌석 {@code seatId} 이며
 * (TASK-002G-D §6), 운행편 식별자는 그 순서에 등장하지 않는다.
 * 필요해지면 그때 근거와 함께 추가한다.
 */
public record TrainScheduleId(long value) {

    public TrainScheduleId {
        if (value <= 0) {
            throw new IllegalArgumentException("trainScheduleId 는 양수여야 한다: " + value);
        }
    }
}
