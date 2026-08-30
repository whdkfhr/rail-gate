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
 * <h2>소속 변경에 대해 이 클래스가 보장하는 범위</h2>
 *
 * <p>TASK-002G-D §4 는 다음을 계약으로 정했다.
 *
 * <blockquote>
 * 판매가 시작되거나 좌석 재고가 만들어진 뒤 {@code TrainSchedule} 의 {@code SaleEvent}
 * 소속을 바꾸지 않는다. 바꾸면 <b>이미 쌓인 quota 의 의미가 달라진다</b> — 그 사용자가
 * 어느 이벤트에서 몇 석을 잡고 있었는지 되짚을 수 없다.
 * </blockquote>
 *
 * <p><b>이 클래스는 그 계약의 일부만 보장한다.</b> 경계를 분명히 해 둔다.
 *
 * <table border="1">
 *   <caption>소속 변경 방지의 층위</caption>
 *   <tr><th>보장</th><th>수단</th></tr>
 *   <tr>
 *     <td>도메인 객체 <b>인스턴스</b>는 불변이다. 생성 후 그 인스턴스의
 *         {@code saleEventId} 는 바뀌지 않는다</td>
 *     <td>모든 필드가 {@code final}</td>
 *   </tr>
 *   <tr>
 *     <td><b>공개 재배정 API 를 제공하지 않는다</b></td>
 *     <td>소속을 받는 메서드는 질의 {@link #belongsTo(SaleEventId)} 하나뿐</td>
 *   </tr>
 *   <tr>
 *     <td style="color:gray">같은 {@link TrainScheduleId} 의 <b>영속 소속</b>이 다른
 *         {@code SaleEventId} 로 갱신되는 것</td>
 *     <td style="color:gray"><b>막지 못한다.</b> Task 2G-E-B 의 repository/DB 계약 몫</td>
 *   </tr>
 * </table>
 *
 * <p><b>세 번째 줄이 핵심이다.</b> 같은 {@code TrainScheduleId} 에 다른 소속을 담은 스냅샷을
 * 넘기면 이 클래스는 그대로 복원한다. 스냅샷은 저장된 값을 옮길 뿐 그것이 옳은지 판정하지
 * 않는다. 즉 <b>aggregate identity 수준의 소속 불변은 아직 어디에서도 강제되지 않는다.</b>
 * {@code UPDATE train_schedule SET sale_event_id = ...} 를 막을 수단이 필요하며,
 * 그것은 마이그레이션과 저장소의 책임이다 (TASK-002G-E-A §4·§8).
 *
 * <h2>왜 상태 규칙을 쓰지 않았나</h2>
 *
 * <p>2G-D §12 는 "판매 시작 후 소속 변경 금지를 <b>상태 규칙</b>으로 강제" 로 적었지만
 * 그 방식을 택하지 않았다.
 * <ul>
 *   <li><b>판매 시작 전에도 안전하지 않다.</b> 좌석 재고와 quota 행은 회차가 열리기 전에도
 *       만들어질 수 있다. "아직 SCHEDULED 니까 옮겨도 된다" 는 판단은 그 경우 틀린다.</li>
 *   <li><b>규칙을 지키려면 다른 애그리거트의 상태를 읽어야 한다.</b> 운행편이 판매 회차의
 *       상태에 따라 자기 행동을 바꾸면, 두 애그리거트가 한 트랜잭션에 묶인다.</li>
 * </ul>
 *
 * <p>대신 이 클래스는 위 표의 두 줄만 맡고, 나머지는 후속 Task 로 남긴다.
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
     * <p>소속은 생성자에서만 받는다. 만들어진 <b>이 인스턴스</b>의 소속은 이후 바뀌지 않는다.
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
     * <p><b>스냅샷에 기록된 소속을 그대로 복원한다.</b> 그 소속이 이전에 저장된 값과 같은지는
     * 검사하지 않는다 — 도메인은 이전 값을 알지 못한다. 같은 {@link TrainScheduleId} 의
     * 영속 소속이 바뀌는 것을 막는 것은 저장소의 책임이다 (Task 2G-E-B).
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
