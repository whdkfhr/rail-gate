package com.railgate.reservation.schedule;

import com.railgate.reservation.saleevent.SaleEventId;
import java.util.Objects;

/**
 * 열차 운행편. 정확히 하나의 판매 회차에 속한다.
 *
 * <p>패키지 의존 방향은 {@code schedule → saleevent} 한 방향뿐이다.
 * 판매 회차는 자기에게 어떤 운행편이 속하는지 알지 못한다. 알 필요가 없고,
 * 알게 하면 순환이 생긴다.
 *
 * <h2>소속은 바뀌지 않는다</h2>
 *
 * <p>TASK-002G-D §4 의 계약이다.
 *
 * <blockquote>
 * 판매가 시작되거나 좌석 재고가 만들어진 뒤 {@code TrainSchedule} 의 {@code SaleEvent}
 * 소속을 바꾸지 않는다. 바꾸면 <b>이미 쌓인 quota 의 의미가 달라진다</b> — 그 사용자가
 * 어느 이벤트에서 몇 석을 잡고 있었는지 되짚을 수 없다.
 * </blockquote>
 *
 * <p><b>이것을 "판매 시작 후 금지" 라는 상태 규칙이 아니라 구조로 강제한다.</b>
 * 즉 이 객체에는 소속을 바꾸는 수단이 <b>아예 없다</b>. 모든 필드가 final 이고 변경자가 없다.
 *
 * <p>상태 규칙(예: {@code saleEvent.isOpen()} 이면 재배정 거부)을 고르지 않은 이유는 두 가지다.
 * <ul>
 *   <li><b>판매 시작 전에도 안전하지 않다.</b> 좌석 재고와 quota 행은 회차가 열리기 전에도
 *       만들어질 수 있다. "아직 SCHEDULED 니까 옮겨도 된다" 는 판단은 그 경우 틀린다.</li>
 *   <li><b>규칙을 지키려면 다른 애그리거트의 상태를 읽어야 한다.</b> 운행편이 판매 회차의
 *       상태에 따라 자기 행동을 바꾸면, 두 애그리거트가 한 트랜잭션에 묶인다.</li>
 * </ul>
 *
 * <p><b>다만 이 클래스가 막는 것은 도메인 객체를 통한 변경뿐이다.</b>
 * 운영 DB 의 {@code UPDATE train_schedule SET sale_event_id = ...} 는 막지 못한다.
 * 그 방어는 마이그레이션과 저장소의 몫이며 이 Task 범위가 아니다 (TASK-002G-E-A §4·§8).
 */
public final class TrainSchedule {

    private final TrainScheduleId id;
    private final SaleEventId saleEventId;

    private TrainSchedule(TrainScheduleId id, SaleEventId saleEventId) {
        this.id = id;
        this.saleEventId = saleEventId;
    }

    /**
     * 운행편을 판매 회차에 편성한다.
     *
     * <p>소속을 생성자에서만 받는 것이 계약의 전부다. 이후에 바꿀 방법이 없다.
     *
     * @throws NullPointerException 식별자나 소속이 없는 경우
     */
    public static TrainSchedule of(TrainScheduleId id, SaleEventId saleEventId) {
        return new TrainSchedule(
                Objects.requireNonNull(id, "trainScheduleId 는 null 일 수 없다"),
                Objects.requireNonNull(saleEventId, "saleEventId 는 null 일 수 없다"));
    }

    /**
     * 저장된 행을 도메인 객체로 복원한다.
     *
     * @throws TrainScheduleRestoreException 스냅샷이 없는 경우
     */
    public static TrainSchedule restore(TrainScheduleSnapshot snapshot) {
        if (snapshot == null) {
            throw new TrainScheduleRestoreException("운행편을 복원할 수 없다: 스냅샷이 없다");
        }
        return new TrainSchedule(snapshot.id(), snapshot.saleEventId());
    }

    /**
     * 이 운행편의 좌석이 귀속되는 quota 범위.
     *
     * <p>선점 경로는 {@code scheduleId} 를 이 값으로 <b>해석</b>해야 하며,
     * 둘을 같은 것으로 취급해서는 안 된다 (TASK-002G-D §8).
     */
    public SaleEventId saleEventId() {
        return saleEventId;
    }

    public boolean belongsTo(SaleEventId saleEventId) {
        return this.saleEventId.equals(saleEventId);
    }

    public TrainScheduleId id() {
        return id;
    }

    @Override
    public String toString() {
        return "TrainSchedule[id=%d, saleEventId=%d]".formatted(id.value(), saleEventId.value());
    }
}
