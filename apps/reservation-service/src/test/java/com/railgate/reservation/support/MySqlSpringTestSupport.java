package com.railgate.reservation.support;

import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;

/**
 * <b>Spring 이 실제로 배선한</b> 애플리케이션을 실제 MySQL 에 붙여 검증하는 기반.
 *
 * <h2>★ 왜 손으로 조립하지 않는가</h2>
 *
 * <p>{@code new HoldSeatsService(...)} 로 만든 객체에는 {@code @Transactional} 프록시가 없다.
 * 그 객체로 통과한 테스트는 <b>트랜잭션 경계가 실제로 열리는지 아무것도 증명하지 못한다.</b>
 * 경계가 없으면 저장소들이 참여 검사로 거부하므로 조용히 통과하지도 않지만,
 * "경계가 열린다" 는 것 자체가 이 Task 의 검증 대상이다.
 *
 * <p>그래서 컨테이너를 띄우고 {@code @SpringBootTest} 로 진짜 컨텍스트를 올린 뒤
 * <b>주입받은 빈</b>을 호출한다. Flyway 도 앱 설정 그대로 돈다.
 *
 * <p>컨테이너는 JVM 당 하나만 띄운다. {@code reservation-infra} 의
 * {@code MySqlTestSupport} 와 같은 이유이며, 모듈이 달라 인스턴스는 공유되지 않는다.
 */
@SpringBootTest
public abstract class MySqlSpringTestSupport {

    @SuppressWarnings("resource") // JVM 종료 시까지 유지하는 싱글턴 컨테이너
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("railgate")
            .withUsername("railgate")
            .withPassword("railgate")
            .withCommand(
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                    "--log_timestamps=SYSTEM");

    static {
        MYSQL.start();
    }

    /**
     * 앱의 {@code application.yml} 은 유지하고 접속 정보만 컨테이너로 바꾼다.
     *
     * <p><b>{@code connection-init-sql} 과 풀 크기는 덮어쓰지 않는다.</b>
     * 그 설정이 실제로 적용되는지가 검증 대상이기 때문이다 (규칙 9).
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    /** 테스트 픽스처가 쓰는 판매 회차. {@code MySqlTestSupport} 와 같은 값이다. */
    protected static final long FIXTURE_SALE_EVENT_ID = 9_000_000L;

    /**
     * 외래 키 방향의 역순으로 지운다.
     *
     * <pre>
     *   user_hold_quota ─┐
     *                    ├─▶ sale_event
     *   seat_inventory ──▶ train_schedule ─┘
     * </pre>
     */
    @BeforeEach
    void resetTables() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DELETE FROM seat_inventory");
        jdbc.execute("DELETE FROM user_hold_quota");
        jdbc.execute("DELETE FROM train_schedule");
        jdbc.execute("DELETE FROM sale_event");
    }

    protected JdbcTemplate jdbc() {
        return jdbc;
    }

    protected DataSource dataSource() {
        return dataSource;
    }

    /** 판매 회차를 만든다. 이미 있으면 값을 바꾸지 않는다. */
    protected void ensureSaleEvent(long saleEventId) {
        jdbc.update("""
                INSERT INTO sale_event (id, name, opens_at, status)
                VALUES (?, '테스트 판매 회차', DATE_SUB(NOW(3), INTERVAL 1 DAY), 'OPEN')
                ON DUPLICATE KEY UPDATE id = id
                """, saleEventId);
    }

    /** 운행편을 해당 회차에 속하게 만든다. 이미 있으면 소속을 덮어쓰지 않는다. */
    protected void ensureTrainSchedule(long scheduleId, long saleEventId) {
        ensureSaleEvent(saleEventId);
        jdbc.update("""
                INSERT INTO train_schedule (id, sale_event_id)
                VALUES (?, ?)
                ON DUPLICATE KEY UPDATE id = id
                """, scheduleId, saleEventId);
    }

    protected long insertAvailableSeat(long scheduleId, String seatNo) {
        return insertAvailableSeat(scheduleId, seatNo, FIXTURE_SALE_EVENT_ID);
    }

    protected long insertAvailableSeat(long scheduleId, String seatNo, long saleEventId) {
        ensureTrainSchedule(scheduleId, saleEventId);
        jdbc.update(
                "INSERT INTO seat_inventory (schedule_id, seat_no, status) VALUES (?, ?, 'AVAILABLE')",
                scheduleId, seatNo);
        return jdbc.queryForObject(
                "SELECT id FROM seat_inventory WHERE schedule_id = ? AND seat_no = ?",
                Long.class, scheduleId, seatNo);
    }

    protected String statusOf(long seatId) {
        return jdbc.queryForObject(
                "SELECT status FROM seat_inventory WHERE id = ?", String.class, seatId);
    }

    protected Long heldByOf(long seatId) {
        return jdbc.queryForObject(
                "SELECT held_by FROM seat_inventory WHERE id = ?", Long.class, seatId);
    }

    protected String holdIdOf(long seatId) {
        return jdbc.queryForObject(
                "SELECT hold_id FROM seat_inventory WHERE id = ?", String.class, seatId);
    }

    protected Long reservationIdOf(long seatId) {
        return jdbc.queryForObject(
                "SELECT reservation_id FROM seat_inventory WHERE id = ?", Long.class, seatId);
    }

    protected java.sql.Timestamp expiresAtOf(long seatId) {
        return jdbc.queryForObject(
                "SELECT expires_at FROM seat_inventory WHERE id = ?", java.sql.Timestamp.class, seatId);
    }

    protected Long versionOf(long seatId) {
        return jdbc.queryForObject(
                "SELECT version FROM seat_inventory WHERE id = ?", Long.class, seatId);
    }

    protected long heldSeatCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat_inventory WHERE status = 'HELD'", Long.class);
    }

    /** quota 카운터 값. 행이 없으면 빈 값이 아니라 {@code null} 이 아닌 -1 로 구분한다. */
    protected int quotaOf(long saleEventId, long userId) {
        Integer value = jdbc.query(
                "SELECT held_seats FROM user_hold_quota WHERE sale_event_id = ? AND user_id = ?",
                rs -> rs.next() ? rs.getInt(1) : null,
                saleEventId, userId);
        return value == null ? -1 : value;
    }

    protected long quotaRowCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_hold_quota", Long.class);
    }
}
