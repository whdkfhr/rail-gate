package com.railgate.reservation.infra.saleevent;

import com.railgate.reservation.saleevent.SaleEvent;
import com.railgate.reservation.saleevent.SaleEventId;
import com.railgate.reservation.saleevent.SaleEventSnapshot;
import com.railgate.reservation.saleevent.SaleEventStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * 판매 회차 조회와 상태 전이.
 *
 * <h2>도메인 전이와 여기의 전이는 층이 다르다</h2>
 *
 * <p>{@code SaleEvent.open(Instant now)} 는 <b>단일 스레드 객체의 규칙</b>을 표현하고,
 * 실제 방어선은 여기의 조건부 UPDATE 다. 2G-E-A §3 이 명시한 대로 도메인 객체는
 * 두 스레드가 같은 회차를 동시에 여는 것을 막지 못한다. 여기서는 검사와 갱신이
 * 한 문장이므로 {@code affected_rows = 1} 인 요청이 하나뿐이다.
 *
 * <h2>왜 open() 이 시각을 받지 않는가</h2>
 *
 * <p>도메인은 시각을 파라미터로 받지만(테스트 가능성) <b>운영 판정은 DB 의 {@code NOW(3)}</b>
 * 이 한다 (CLAUDE.md 규칙 7). 애플리케이션 시각을 넘기면 인스턴스 간 클럭 드리프트가
 * 판정에 섞이고, 시각을 조작한 요청이 아직 열리지 않은 회차를 열 수 있다.
 * 그래서 서명이 다르다 — 이것은 불일치가 아니라 각 층이 답하는 질문이 다르기 때문이다.
 *
 * <h2>이 클래스가 제공하지 않는 것</h2>
 *
 * <ul>
 *   <li><b>회차 생성·수정 API</b> — 운영자가 등록하는 경로이며 아직 정해지지 않았다.
 *       조회와 전이만 있으면 선점 경로가 동작한다.</li>
 *   <li><b>I-12 quota 연동</b> — {@code user_hold_quota} 테이블 자체가 없다.
 *       이 클래스는 quota <b>범위를 가리키는 회차</b>를 다룰 뿐 상한을 강제하지 않는다.</li>
 *   <li><b>회차 상태와 선점 경로의 연결</b> — {@code isOpen()} 을 실제 좌석 선점이
 *       검사하도록 배선하는 것은 애플리케이션 서비스의 몫이며 아직 없다.</li>
 * </ul>
 */
public class JdbcSaleEventRepository {

    private static final String FIND_BY_ID_SQL = """
            SELECT id, name, opens_at, closes_at, status
              FROM sale_event
             WHERE id = ?
            """;

    /**
     * 판매를 시작한다. 검사와 갱신이 한 문장이다.
     *
     * <p><b>{@code opens_at <= NOW(3)} 이 오픈 시각 판정의 유일한 근거다.</b>
     * 경계를 {@code <=} 로 둔 이유는 도메인의 {@code now >= opensAt} 과 맞추기 위해서다 —
     * 오픈 시각과 정확히 같은 순간은 열 수 있다.
     *
     * <p>{@code status = 'SCHEDULED'} 는 이미 열린 회차의 재오픈과 CLOSED 의 부활을 함께 막는다.
     * 상태 컬럼이 {@code ascii_bin} 이므로 소문자 {@code 'scheduled'} 행에는 매칭되지 않는다.
     */
    private static final String OPEN_SQL = """
            UPDATE sale_event
               SET status = 'OPEN'
             WHERE id       = ?
               AND status   = 'SCHEDULED'
               AND opens_at <= NOW(3)
            """;

    /**
     * 판매를 마감한다.
     *
     * <p><b>시각 조건이 없다.</b> {@code closes_at} 은 예정 시각일 뿐이고 매진이나 운영 중단으로
     * 인한 조기 마감은 정상 운영 행위다 (2G-E-A §3). {@code now >= closes_at} 을 요구하면
     * 그 정상 경로가 막힌다.
     *
     * <p>{@code status = 'OPEN'} 이 {@code SCHEDULED → CLOSED} 를 막는다. 열린 적 없는 회차를
     * 닫는다는 것의 업무적 의미가 정해지지 않았기 때문이다.
     */
    private static final String CLOSE_SQL = """
            UPDATE sale_event
               SET status = 'CLOSED'
             WHERE id     = ?
               AND status = 'OPEN'
            """;

    private static final RowMapper<SaleEvent> ROW_MAPPER = JdbcSaleEventRepository::mapRow;

    private final JdbcTemplate jdbc;

    public JdbcSaleEventRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
    }

    /**
     * 저장된 회차를 도메인 객체로 복원한다.
     *
     * <p>복원은 <b>전이를 재실행하지 않는다</b> (2G-E-A §5). 오픈 시각이 미래로 보이는 행이
     * 이미 {@code OPEN} 으로 저장돼 있다면 그대로 읽는다. 그렇지 않으면 규칙이 바뀔 때마다
     * 과거 데이터를 읽지 못하게 된다.
     */
    public Optional<SaleEvent> findById(SaleEventId id) {
        Objects.requireNonNull(id, "saleEventId");
        return jdbc.query(FIND_BY_ID_SQL, ROW_MAPPER, id.value()).stream().findFirst();
    }

    /**
     * 판매를 시작한다.
     *
     * @return {@link SaleEventTransitionOutcome#APPLIED} 이면 이 요청이 회차를 열었다.
     *         {@code NOT_APPLIED} 는 오픈 시각 전이거나, 이미 열렸거나, 회차가 없다는 뜻이며
     *         <b>오류가 아니다.</b>
     */
    public SaleEventTransitionOutcome open(SaleEventId id) {
        Objects.requireNonNull(id, "saleEventId");
        return outcomeOf(jdbc.update(OPEN_SQL, id.value()));
    }

    /**
     * 판매를 마감한다.
     *
     * @return {@link SaleEventTransitionOutcome#APPLIED} 이면 이 요청이 회차를 닫았다.
     */
    public SaleEventTransitionOutcome close(SaleEventId id) {
        Objects.requireNonNull(id, "saleEventId");
        return outcomeOf(jdbc.update(CLOSE_SQL, id.value()));
    }

    private static SaleEventTransitionOutcome outcomeOf(int affectedRows) {
        return affectedRows == 1
                ? SaleEventTransitionOutcome.APPLIED
                : SaleEventTransitionOutcome.NOT_APPLIED;
    }

    /**
     * 행을 스냅샷으로 옮긴 뒤 복원한다.
     *
     * <p>{@link SaleEventSnapshot} 을 거치는 이유는 <b>손상된 행을 조회 시점에 잡기 위해서다.</b>
     * 스냅샷 생성자가 이름·오픈 시각·판매 기간을 검증하고, 어긋나면
     * {@code SaleEventRestoreException} 으로 즉시 실패한다.
     *
     * <p>{@code SaleEventStatus.valueOf} 가 알 수 없는 상태 문자열에 실패하는 것도 의도한 것이다.
     * DB 의 CHECK 제약과 이 enum 이 어긋나면 그것을 조용히 넘기면 안 된다.
     */
    private static SaleEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
        return SaleEvent.restore(new SaleEventSnapshot(
                new SaleEventId(rs.getLong("id")),
                rs.getString("name"),
                instantOf(rs.getObject("opens_at", LocalDateTime.class)),
                instantOf(rs.getObject("closes_at", LocalDateTime.class)),
                SaleEventStatus.valueOf(rs.getString("status"))));
    }

    /**
     * {@code DATETIME(3)} 을 {@link Instant} 로 옮긴다.
     *
     * <p><b>{@code ResultSet.getTimestamp} 를 쓰지 않는 이유.</b> 그 메서드는 값을
     * <b>JVM 기본 시간대</b>로 해석한다. {@code DATETIME} 은 시간대가 없는 타입이므로,
     * 같은 행이라도 애플리케이션 인스턴스의 시간대에 따라 서로 다른 {@code Instant} 가 된다.
     * 인스턴스 간 클럭·시간대 차이가 값에 섞이는 것을 막자는 것이 CLAUDE.md 규칙 7 의 취지다.
     *
     * <p>그래서 해석 기준을 UTC 로 <b>고정</b>한다. 배포 환경이 달라져도 같은 행은 같은 값이 된다.
     *
     * <p><b>이 변환은 판정에 쓰이지 않는다.</b> 오픈 여부를 정하는 것은 SQL 안의
     * {@code opens_at <= NOW(3)} 이고, 그 비교는 DB 안에서 같은 타입끼리 이뤄지므로
     * 여기의 시간대 선택과 무관하다. 이 값은 조회·표시용이다.
     *
     * <p><b>남는 위험:</b> DB 서버 시간대가 UTC 가 아니면 표시 값이 그만큼 어긋난다.
     * 근본 해결은 {@code TIMESTAMP} 로 저장하거나 세션 시간대를 고정하는 것이며,
     * 그 변경은 기존 {@code seat_inventory} 의 시각 컬럼 전부에 영향을 주므로 후속 과제다.
     */
    private static Instant instantOf(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
