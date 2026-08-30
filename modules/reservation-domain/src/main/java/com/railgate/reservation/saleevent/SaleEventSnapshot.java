package com.railgate.reservation.saleevent;

import java.time.Instant;

/**
 * 저장된 판매 회차 한 행의 값. 운영 스키마 초안의 {@code sale_event} 컬럼과 대응한다
 * (TASK-002G-D §5).
 *
 * <p><b>{@code SeatSnapshot} 과 다른 점을 분명히 해 둔다.</b> {@code SeatSnapshot} 은
 * 상태마다 허용되는 필드 조합이 달라서(예: {@code SOLD} 에는 {@code holdId} 가 없어야 한다)
 * 상태별 표를 검증한다. <b>판매 회차에는 그런 상태 의존 조합이 없다.</b>
 * 세 상태 모두 같은 필드를 가지며, 상태는 판매 기간과 독립적으로 운영이 정한다
 * ({@link SaleEvent#close()} 가 마감 시각을 요구하지 않는 이유와 같다).
 *
 * <p>그런데도 스냅샷 타입을 두는 이유는 두 가지다.
 * <ul>
 *   <li><b>복원이 상태 전이를 재실행하지 않게 하려고.</b> {@code CLOSED} 회차를 만들려고
 *       {@code schedule → open → close} 를 다시 돌리면, 오픈 시각 검사 때문에 과거에
 *       유효했던 데이터를 현재 시각 기준으로 다시 판정하게 되어 읽기가 실패한다.</li>
 *   <li><b>값 검증을 한 곳에 모으려고.</b> 5개 필드 중 1개가 nullable 이고
 *       기간 규칙이 걸린다. {@link SaleEvent} 는 전이 규칙에만 집중한다.</li>
 * </ul>
 *
 * <p>규칙 자체는 {@link SaleEventRules} 가 갖고 있어 {@link SaleEvent#schedule} 과 공유한다.
 * 여기서 던지는 예외만 다르다.
 */
public record SaleEventSnapshot(
        SaleEventId id,
        String name,
        Instant opensAt,
        Instant closesAt,
        SaleEventStatus status) {

    public SaleEventSnapshot {
        require(id != null, "판매 회차 식별자가 없다");
        require(SaleEventRules.hasName(name), "판매 회차 이름이 없다");
        require(opensAt != null, "오픈 시각이 없다");
        require(status != null, "판매 회차 상태가 없다");
        require(SaleEventRules.isValidSalePeriod(opensAt, closesAt),
                "마감 시각(%s)은 오픈 시각(%s)보다 뒤여야 한다".formatted(closesAt, opensAt));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new SaleEventRestoreException("판매 회차를 복원할 수 없다: " + message);
        }
    }
}
