package com.railgate.reservation.infra.saleevent;

import com.railgate.reservation.saleevent.SaleEventId;
import com.railgate.reservation.schedule.TrainSchedule;
import com.railgate.reservation.schedule.TrainScheduleId;
import com.railgate.reservation.schedule.TrainScheduleSnapshot;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 운행편 조회.
 *
 * <h2>★ 재배정 API 를 제공하지 않는다</h2>
 *
 * <p>이것이 소속 변경 방어의 <b>저장소 층</b>이다 (TASK-002G-E-B1 §11).
 * {@code SaleEventId} 를 <b>입력으로</b> 받는 public 메서드가 하나도 없고, 그래서
 * 애플리케이션 정상 경로에는 {@code train_schedule.sale_event_id} 를 바꿀 통로가 없다.
 * 테스트가 리플렉션으로 이 사실을 고정한다.
 *
 * <p><b>어디까지 막는지 분명히 해 둔다.</b>
 * <ul>
 *   <li>도메인 — {@code TrainSchedule} 인스턴스에 소속 변경 API 가 없어 도메인 객체를 통한
 *       변경을 막는다 (2G-E-A §4).</li>
 *   <li>저장소 — <b>이 클래스</b>가 재배정 UPDATE 를 제공하지 않아 정상 애플리케이션 경로를 막는다.</li>
 *   <li>DB — V5 의 FK 는 "유효한 {@code sale_event} 를 참조한다" 만 보장한다.
 *       유효한 다른 회차로의 {@code UPDATE} 는 <b>막지 않는다</b> (2G-E-B1 §9 에서 실측).</li>
 *   <li>트리거·컬럼 권한 회수 — 마이그레이션 계정 분리를 전제로 하며 <b>아직 적용하지 않았다.</b></li>
 * </ul>
 *
 * <p>정리하면, <b>도메인과 저장소의 정상 애플리케이션 경로에서는 소속 재배정을 차단하지만,
 * DB 직접 SQL 수준의 {@code sale_event_id} 변경은 아직 차단하지 못한다.</b>
 * 운영 스크립트나 다른 저장소가 직접 UPDATE 하는 경로가 그것이다.
 *
 * <h2>포트 인터페이스를 만들지 않은 이유</h2>
 *
 * <p>구현이 하나뿐이고 이것을 소비할 애플리케이션 서비스가 아직 없다. 지금 인터페이스를
 * 만들면 <b>구현을 그대로 베낀 이름 하나</b>가 생길 뿐이고, 경계는 실제 유스케이스가
 * 생길 때 그 요구에 맞춰 그어야 한다.
 */
public class JdbcTrainScheduleRepository {

    private static final String FIND_BY_ID_SQL = """
            SELECT id, sale_event_id
              FROM train_schedule
             WHERE id = ?
            """;

    private final JdbcTemplate jdbc;

    public JdbcTrainScheduleRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
    }

    /**
     * 저장된 운행편을 복원한다.
     *
     * <p>{@link TrainScheduleSnapshot} 이 소속 없는 행을 거부한다. 그런 행이 있으면 그 운행편의
     * 좌석은 어느 quota 카운터에도 귀속되지 않아 I-12 의 상한 밖에 놓인다.
     * (V4 의 {@code NOT NULL} 이 이미 막지만, 조회 시점에도 확인한다)
     */
    public Optional<TrainSchedule> findById(TrainScheduleId id) {
        Objects.requireNonNull(id, "trainScheduleId");
        return jdbc.query(FIND_BY_ID_SQL,
                        (rs, rowNum) -> TrainSchedule.restore(new TrainScheduleSnapshot(
                                new TrainScheduleId(rs.getLong("id")),
                                new SaleEventId(rs.getLong("sale_event_id")))),
                        id.value())
                .stream()
                .findFirst();
    }

    /**
     * 이 운행편이 속한 판매 회차만 얻는다.
     *
     * <p>운행편 객체 전체가 필요 없는 호출부를 위한 것이다. <b>{@code scheduleId} 를
     * {@code saleEventId} 로 대입하지 않고</b> 실제로 조회한다는 사실이 여기서 드러난다
     * (TASK-002G-D §8: 암묵 대입 금지).
     */
    public Optional<SaleEventId> findSaleEventId(TrainScheduleId id) {
        return findById(id).map(TrainSchedule::saleEventId);
    }
}
