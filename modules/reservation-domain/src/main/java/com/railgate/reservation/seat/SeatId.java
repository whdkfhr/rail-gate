package com.railgate.reservation.seat;

/**
 * 좌석 재고 한 행의 식별자.
 *
 * <p>Comparable 을 구현하는 이유는 잠금 순서 때문이다.
 * 여러 좌석을 함께 다루는 트랜잭션은 예외 없이 이 값의 오름차순으로 접근해야
 * 데드락이 생기지 않는다 (CLAUDE.md 규칙 2).
 */
public record SeatId(long value) implements Comparable<SeatId> {

    public SeatId {
        if (value <= 0) {
            throw new IllegalArgumentException("seatId 는 양수여야 한다: " + value);
        }
    }

    @Override
    public int compareTo(SeatId other) {
        return Long.compare(this.value, other.value);
    }
}
