package com.railgate.reservation.saleevent;

/**
 * 판매 회차(SaleEvent) 식별자.
 *
 * <p>일반적인 {@code EventId} 를 쓰지 않는 이유는 이름 충돌 때문이다
 * (TASK-002G-D §4). 도메인 이벤트({@code SeatHeld} 등), Kafka 이벤트,
 * 대기열 이벤트와 모두 구분돼야 한다.
 *
 * <p><b>Comparable 을 구현하는 이유는 잠금 순서 때문이다.</b>
 * quota 행의 키는 {@code (saleEventId, userId)} 이고, 여러 quota 행을 잠그는 경로
 * (만료 배치)는 이 순서의 오름차순으로 접근해야 데드락이 생기지 않는다
 * (TASK-002G-D §6, CLAUDE.md 규칙 2). {@code SeatId} 가 Comparable 인 것과 같은 이유다.
 */
public record SaleEventId(long value) implements Comparable<SaleEventId> {

    public SaleEventId {
        if (value <= 0) {
            throw new IllegalArgumentException("saleEventId 는 양수여야 한다: " + value);
        }
    }

    @Override
    public int compareTo(SaleEventId other) {
        return Long.compare(this.value, other.value);
    }
}
