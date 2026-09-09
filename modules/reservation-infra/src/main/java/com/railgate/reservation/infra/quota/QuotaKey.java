package com.railgate.reservation.infra.quota;

import com.railgate.reservation.UserId;
import com.railgate.reservation.saleevent.SaleEventId;
import java.util.Objects;

/**
 * quota 카운터 행을 가리키는 키. {@code user_hold_quota} 의 PK 와 같다.
 *
 * <p><b>{@link Comparable} 인 이유는 잠금 순서 때문이다.</b> 여러 quota 행을 잠그는 경로
 * (만료 배치)는 이 순서의 오름차순으로 접근해야 데드락이 생기지 않는다
 * (TASK-002G-B, CLAUDE.md 규칙 2). {@code SeatId}·{@code SaleEventId} 가 Comparable 인 것과
 * 같은 이유다.
 *
 * <p>정렬 기준이 PK 컬럼 순서({@code sale_event_id}, {@code user_id})와 <b>같아야</b> 한다.
 * 다르면 InnoDB 가 실제로 잠그는 순서와 어긋나 순서를 정한 의미가 사라진다.
 */
public record QuotaKey(SaleEventId saleEventId, UserId userId) implements Comparable<QuotaKey> {

    public QuotaKey {
        Objects.requireNonNull(saleEventId, "saleEventId");
        Objects.requireNonNull(userId, "userId");
    }

    @Override
    public int compareTo(QuotaKey other) {
        int byEvent = saleEventId.compareTo(other.saleEventId);
        return byEvent != 0 ? byEvent : Long.compare(userId.value(), other.userId.value());
    }

    @Override
    public String toString() {
        return "(회차=%d, 사용자=%d)".formatted(saleEventId.value(), userId.value());
    }
}
