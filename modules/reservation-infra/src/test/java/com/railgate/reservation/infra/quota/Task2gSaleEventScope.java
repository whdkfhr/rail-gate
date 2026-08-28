package com.railgate.reservation.infra.quota;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <b>테스트 전용</b> 판매 이벤트 ↔ 열차 운행편 매핑 (Task 2G-D).
 *
 * <h2>운영 모델이 아니다</h2>
 *
 * <p>quota 범위를 <b>판매 이벤트 단위</b>로 결정하기 위한 실험 fixture 다.
 * 운영 {@code SaleEvent} aggregate 나 {@code train_schedule} 테이블은 이 Task 에서 만들지 않는다.
 * 지금 필요한 것은 "여러 운행편이 하나의 판매 이벤트로 합산된다" 는 <b>계약</b>뿐이고,
 * 그것은 Map 하나로 충분히 표현된다.
 *
 * <h2>왜 scheduleId 를 saleEventId 로 대입하지 않는가</h2>
 *
 * <p>{@code seat_inventory} 에는 {@code schedule_id} 만 있어서 그것을 그대로 범위 키로 쓰고 싶어진다.
 * 그러면 <b>사용자가 운행편을 바꿔가며 각각 4석씩</b> 잡을 수 있어 FR-2.6 의 사재기 방지 목적이
 * 무력해진다. {@code UserHoldQuotaScopeContractTest} §1 이 그것을 재현한다.
 *
 * <p>그래서 매핑을 <b>명시적으로</b> 요구한다. 매핑되지 않은 운행편은 quota 를 건드리기 전에 실패한다.
 *
 * <h2>이름을 SaleEvent 로 둔 이유</h2>
 *
 * <p>{@code EventId} 는 도메인 이벤트·Kafka 이벤트·대기열 이벤트와 섞인다.
 * <b>판매 회차</b>를 뜻한다는 것이 이름에서 드러나야 한다.
 */
final class Task2gSaleEventScope {

    /**
     * quota 카운터 행을 가리키는 키.
     *
     * <p>잠금 순서를 {@code (saleEventId, userId)} 오름차순으로 고정하기 위해 정렬 가능해야 한다
     * (Task 2G-B·2G-C 의 quota → seat 원칙과 같은 방향이다).
     */
    record QuotaKey(long saleEventId, long userId) implements Comparable<QuotaKey> {

        @Override
        public int compareTo(QuotaKey other) {
            int byEvent = Long.compare(saleEventId, other.saleEventId);
            return byEvent != 0 ? byEvent : Long.compare(userId, other.userId);
        }

        @Override
        public String toString() {
            return "(saleEvent=%d, user=%d)".formatted(saleEventId, userId);
        }
    }

    private final Map<Long, Long> scheduleToSaleEvent = new LinkedHashMap<>();

    /**
     * 운행편을 판매 이벤트에 배정한다. <b>하나의 운행편은 정확히 하나의 판매 이벤트에 속한다.</b>
     *
     * <p>이미 다른 판매 이벤트에 배정된 운행편을 옮기는 것은 거부한다. 판매가 시작되거나
     * 좌석 재고가 만들어진 뒤 소속이 바뀌면 <b>이미 쌓인 quota 의 의미가 달라진다</b> —
     * 그 사용자가 어느 이벤트에서 몇 석을 잡고 있었는지 되짚을 수 없게 된다.
     * 같은 이벤트로의 재배정은 멱등한 no-op 이다.
     */
    void assign(long scheduleId, long saleEventId) {
        Long existing = scheduleToSaleEvent.get(scheduleId);
        if (existing != null && existing != saleEventId) {
            throw new IllegalStateException(
                    "운행편 " + scheduleId + " 는 이미 판매 이벤트 " + existing + " 에 속한다. "
                            + saleEventId + " 로 옮기면 기존 quota 의 의미가 달라진다");
        }
        scheduleToSaleEvent.put(scheduleId, saleEventId);
    }

    /**
     * @return 그 운행편이 속한 판매 이벤트 식별자
     * @throws IllegalStateException 매핑되지 않은 운행편. <b>scheduleId 를 saleEventId 로
     *                               암묵 대입하지 않는다</b> — 그것이 §1 의 우회를 만든다.
     */
    long saleEventIdOf(long scheduleId) {
        Long saleEventId = scheduleToSaleEvent.get(scheduleId);
        if (saleEventId == null) {
            throw new IllegalStateException(
                    "운행편 " + scheduleId + " 가 어느 판매 이벤트에 속하는지 알 수 없다. "
                            + "quota 범위를 정할 수 없으므로 좌석을 건드리기 전에 중단한다");
        }
        return saleEventId;
    }

    QuotaKey quotaKeyOf(long scheduleId, long userId) {
        return new QuotaKey(saleEventIdOf(scheduleId), userId);
    }
}
