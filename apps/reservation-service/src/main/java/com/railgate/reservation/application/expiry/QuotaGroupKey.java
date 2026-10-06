package com.railgate.reservation.application.expiry;

import com.railgate.reservation.UserId;
import com.railgate.reservation.saleevent.SaleEventId;
import java.util.Comparator;
import java.util.Objects;

/**
 * 만료 배치의 정산 단위 — {@code (saleEventId, userId)}.
 *
 * <p>quota 카운터 행 하나에 대응한다. 그룹마다 독립 트랜잭션을 열고, 그룹 안에서 좌석 회수와
 * quota 감소가 원자적이다 (TASK-002G-F 실험 B). 처리 순서는 이 키의 오름차순이며
 * {@code user_hold_quota} 의 PK 컬럼 순서와 같다 (TASK-002G-B).
 */
public record QuotaGroupKey(SaleEventId saleEventId, UserId userId) implements Comparable<QuotaGroupKey> {

    private static final Comparator<QuotaGroupKey> ORDER =
            Comparator.comparingLong((QuotaGroupKey k) -> k.saleEventId().value())
                    .thenComparingLong(k -> k.userId().value());

    public QuotaGroupKey {
        Objects.requireNonNull(saleEventId, "saleEventId");
        Objects.requireNonNull(userId, "userId");
    }

    @Override
    public int compareTo(QuotaGroupKey other) {
        return ORDER.compare(this, other);
    }

    @Override
    public String toString() {
        return "(회차 %d, 사용자 %d)".formatted(saleEventId.value(), userId.value());
    }
}
