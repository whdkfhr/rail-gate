package com.railgate.reservation.infra;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Timestamp;
import java.time.Instant;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 실제 MySQL 컨테이너를 띄우고 Flyway 로 스키마를 적용하는 테스트 기반.
 *
 * <p><b>임베디드 DB 를 쓰지 않는 이유</b>(CLAUDE.md 규칙 26): H2 등은 행 잠금과
 * 격리 수준 동작이 InnoDB 와 다르다. 조건부 UPDATE 의 {@code affected_rows} 의미론과
 * 잠금 직렬화를 검증하는 것이 이 테스트의 목적인데, 그 동작이 다르면 아무것도 증명하지 못한다.
 *
 * <p>컨테이너는 JVM 당 하나만 띄운다(정적 싱글턴). 클래스마다 새로 띄우면 CI 시간이 배로 늘고,
 * 테스트 간 격리는 {@link #resetSeatInventory()} 로 충분하다.
 */
public abstract class MySqlTestSupport {

    /**
     * 홀드 트랜잭션의 잠금 대기 상한 (CLAUDE.md 규칙 9).
     *
     * <p>MySQL 기본값 50초는 경합 시 커넥션 풀을 고갈시킨다. 승자의 트랜잭션이 잠깐 늦어지면
     * 나머지 요청이 전부 50초씩 커넥션을 점유하고, 무관한 좌석 조회까지 503 이 된다.
     * 운영 DataSource 도 반드시 같은 설정을 해야 한다.
     */
    private static final int LOCK_WAIT_TIMEOUT_SECONDS = 3;

    /** 1,000 동시 요청이 MySQL max_connections(기본 151) 를 넘기지 않도록 제한한다. */
    private static final int POOL_SIZE = 64;

    // Testcontainers 2.x 의 org.testcontainers.mysql.MySQLContainer 를 쓴다.
    // 1.x 의 org.testcontainers.containers.MySQLContainer 는 deprecated 이며,
    // 신규 클래스는 self-type 제네릭을 걷어내 MySQLContainer<?> 가 아니라 MySQLContainer 다.
    @SuppressWarnings("resource") // JVM 종료 시까지 유지하는 싱글턴 컨테이너
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("railgate")
            .withUsername("railgate")
            .withPassword("railgate")
            .withCommand(
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                    // 밀리초 정밀도 로그 확인용
                    "--log_timestamps=SYSTEM");

    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbcTemplate;
    private static DataSourceTransactionManager transactionManager;

    @BeforeAll
    static void startDatabase() {
        if (!MYSQL.isRunning()) {
            MYSQL.start();
        }
        if (dataSource == null) {
            dataSource = createDataSource();
            jdbcTemplate = new JdbcTemplate(dataSource);
            transactionManager = new DataSourceTransactionManager(dataSource);
            migrate(dataSource);
        }
    }

    private static HikariDataSource createDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(MYSQL.getJdbcUrl());
        config.setUsername(MYSQL.getUsername());
        config.setPassword(MYSQL.getPassword());
        config.setMaximumPoolSize(POOL_SIZE);
        config.setConnectionTimeout(30_000);
        // 커넥션마다 세션 변수를 적용한다. 홀드 경로에서 매번 SET 을 보내면
        // 요청당 왕복이 하나 늘기 때문에 커넥션 초기화 시점에 한 번만 설정한다.
        config.setConnectionInitSql(
                "SET SESSION innodb_lock_wait_timeout = " + LOCK_WAIT_TIMEOUT_SECONDS);
        return new HikariDataSource(config);
    }

    private static void migrate(DataSource ds) {
        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    /**
     * 테스트 픽스처가 쓰는 판매 회차.
     *
     * <p><b>운영 규칙이 아니라 픽스처의 편의다.</b> 별도로 회차를 만들지 않는 테스트의
     * 운행편은 전부 이 회차에 속한다. 회차별 동작을 검증하는 테스트는 자기 회차를
     * 직접 INSERT 한다 ({@code JdbcSaleEventScopeRepositoryTest} 처럼).
     */
    protected static final long FIXTURE_SALE_EVENT_ID = 9_000_000L;

    /**
     * V5 의 외래 키 때문에 좌석보다 먼저 지운다.
     *
     * <p>{@code train_schedule} 까지 지우는 이유: 테스트마다 다른 {@code scheduleId} 를 쓰고,
     * 남겨두면 앞 테스트가 만든 소속이 다음 테스트의 quota 범위 해석에 섞인다.
     */
    @BeforeEach
    void resetSeatInventory() {
        jdbcTemplate.execute("DELETE FROM seat_inventory");
        jdbcTemplate.execute("DELETE FROM train_schedule");
        jdbcTemplate.execute("DELETE FROM sale_event");
    }

    protected static JdbcTemplate jdbc() {
        return jdbcTemplate;
    }

    /**
     * 이 {@link DataSource} 를 관리하는 <b>공유</b> 트랜잭션 관리자.
     *
     * <p>외부 트랜잭션을 여는 쪽과 저장소는 <b>같은 {@code DataSource} 트랜잭션 리소스</b>를
     * 관리해야 한다. <b>같은 관리자 인스턴스일 필요는 없다</b> — 같은 {@code DataSource} 를 가진
     * 서로 다른 {@link DataSourceTransactionManager} 인스턴스도 같은 스레드에 바인딩된 리소스에
     * 참여한다. {@code MultiSeatHoldWiringTest} 가 그것을 실제 MySQL 로 확인한다.
     *
     * <p>테스트에서 관리자를 공유하는 것은 <b>배선 의도를 명확히 하기 위해서일 뿐</b>이며,
     * 인스턴스 동일성이 동작 조건은 아니다.
     *
     * <p>관리자는 무상태이고 트랜잭션 상태는 {@code TransactionSynchronizationManager} 의
     * 스레드 로컬에 있으므로, 공유해도 각 스레드는 독립된 트랜잭션을 갖는다.
     */
    protected static DataSourceTransactionManager transactionManager() {
        return transactionManager;
    }

    /**
     * 애플리케이션 서비스가 소유할 트랜잭션 경계를 테스트에서 대신 연다.
     *
     * <p><b>이 헬퍼는 운영 코드의 책임을 대신하지 않는다.</b> 트랜잭션을 여는 주체가
     * 저장소 밖에 있다는 사실 자체가 검증 대상이므로, 헬퍼가 그것을 감추면 안 된다.
     * 그래서 이름과 반환 타입을 숨기지 않고 {@link TransactionTemplate} 을 그대로 돌려준다.
     * 커밋·롤백 시점을 테스트가 명시적으로 정하게 하려는 것이다.
     *
     * <p>매번 새 템플릿을 만든다. <b>동시성 테스트의 각 작업자가 각자 독립된 트랜잭션을
     * 연다는 의도를 코드에서 드러내기 위해서다.</b> 관리자는 공유하되 템플릿은 작업자마다 갖는다.
     */
    protected static TransactionTemplate newTransactionTemplate() {
        return new TransactionTemplate(transactionManager);
    }

    /** 커밋으로 끝나는 트랜잭션 경계. 예외가 나면 롤백되고 그대로 전파된다. */
    protected static void inTransaction(Runnable work) {
        newTransactionTemplate().executeWithoutResult(status -> work.run());
    }

    protected static DataSource dataSource() {
        return dataSource;
    }

    /**
     * 컨테이너의 JDBC URL. <b>DB 권한 분리를 검증할 때만</b> 쓴다 (Task 2G-E-B1 실험 B).
     *
     * <p>운영 경로는 항상 {@link #dataSource()} 의 애플리케이션 계정이다.
     * 권한 자체가 검증 대상인 실험은 제한된 계정을 따로 만들어야 하므로 URL 이 필요하다.
     */
    protected static String jdbcUrl() {
        return MYSQL.getJdbcUrl();
    }

    /** 컨테이너 root 비밀번호. 위와 같은 이유로만 쓴다. */
    protected static String rootPassword() {
        return MYSQL.getPassword();
    }

    protected static int lockWaitTimeoutSeconds() {
        return LOCK_WAIT_TIMEOUT_SECONDS;
    }

    protected static int poolSize() {
        return POOL_SIZE;
    }

    /**
     * 판매 가능한 좌석 한 개를 넣고 그 id 를 돌려준다.
     *
     * <p>V5 가 {@code seat_inventory.schedule_id → train_schedule.id} 외래 키를 걸었으므로
     * <b>좌석을 넣기 전에 운행편이 존재해야 한다.</b> 이 헬퍼가 그것을 보장하기 때문에
     * 각 테스트는 예전처럼 임의의 {@code scheduleId} 를 그대로 쓸 수 있다.
     *
     * <p><b>운영 코드는 이런 자동 생성을 하지 않는다.</b> 미매핑 운행편을 조용히 만들어
     * 붙이는 것은 TASK-002G-E-B1 §12 가 금지한 지름길이다. 여기서 하는 일은
     * "테스트가 쓰겠다고 선언한 운행편을 준비" 하는 것이지 매핑을 <b>추측</b>하는 것이 아니며,
     * 그래서 소속도 고정된 {@link #FIXTURE_SALE_EVENT_ID} 하나로만 간다 —
     * {@code scheduleId} 를 {@code saleEventId} 로 대입하지 않는다.
     */
    protected static long insertAvailableSeat(long scheduleId, String seatNo) {
        ensureTrainSchedule(scheduleId);
        jdbc().update(
                "INSERT INTO seat_inventory (schedule_id, seat_no, status) VALUES (?, ?, 'AVAILABLE')",
                scheduleId, seatNo);
        return jdbc().queryForObject(
                "SELECT id FROM seat_inventory WHERE schedule_id = ? AND seat_no = ?",
                Long.class, scheduleId, seatNo);
    }

    /**
     * 이 운행편과 그 소속 회차가 존재하도록 보장한다. 이미 있으면 그대로 둔다.
     *
     * <p>{@code ON DUPLICATE KEY UPDATE id = id} 는 값을 바꾸지 않는 no-op 갱신이다.
     * 이미 다른 회차에 배정된 운행편의 소속을 <b>덮어쓰지 않는다</b> —
     * 회차별 동작을 검증하는 테스트가 직접 넣은 매핑이 픽스처 때문에 바뀌면 안 된다.
     */
    protected static void ensureTrainSchedule(long scheduleId) {
        jdbc().update("""
                INSERT INTO sale_event (id, name, opens_at, status)
                VALUES (?, '테스트 판매 회차', DATE_SUB(NOW(3), INTERVAL 1 DAY), 'OPEN')
                ON DUPLICATE KEY UPDATE id = id
                """, FIXTURE_SALE_EVENT_ID);
        jdbc().update("""
                INSERT INTO train_schedule (id, sale_event_id)
                VALUES (?, ?)
                ON DUPLICATE KEY UPDATE id = id
                """, scheduleId, FIXTURE_SALE_EVENT_ID);
    }

    protected static String statusOf(long seatId) {
        return jdbc().queryForObject(
                "SELECT status FROM seat_inventory WHERE id = ?", String.class, seatId);
    }

    protected static String holdIdOf(long seatId) {
        return jdbc().queryForObject(
                "SELECT hold_id FROM seat_inventory WHERE id = ?", String.class, seatId);
    }

    protected static Long heldByOf(long seatId) {
        return jdbc().queryForObject(
                "SELECT held_by FROM seat_inventory WHERE id = ?", Long.class, seatId);
    }

    protected static Long reservationIdOf(long seatId) {
        return jdbc().queryForObject(
                "SELECT reservation_id FROM seat_inventory WHERE id = ?", Long.class, seatId);
    }

    protected static Long versionOf(long seatId) {
        return jdbc().queryForObject(
                "SELECT version FROM seat_inventory WHERE id = ?", Long.class, seatId);
    }

    protected static Instant heldAtOf(long seatId) {
        Timestamp value = jdbc().queryForObject(
                "SELECT held_at FROM seat_inventory WHERE id = ?", Timestamp.class, seatId);
        return value == null ? null : value.toInstant();
    }

    protected static Instant expiresAtOf(long seatId) {
        Timestamp value = jdbc().queryForObject(
                "SELECT expires_at FROM seat_inventory WHERE id = ?", Timestamp.class, seatId);
        return value == null ? null : value.toInstant();
    }

    /**
     * 만료까지 남은 시간을 DB 시각 기준으로 잰다.
     *
     * <p>애플리케이션 시각과 DB 시각을 섞어 비교하면 컨테이너와 호스트의 시계 차이만큼
     * 오차가 생긴다. 만료 판정 기준이 {@code NOW(3)} 이므로 측정도 DB 안에서 한다.
     */
    protected static Long secondsUntilExpiry(long seatId) {
        return jdbc().queryForObject(
                "SELECT TIMESTAMPDIFF(SECOND, NOW(3), expires_at) FROM seat_inventory WHERE id = ?",
                Long.class, seatId);
    }
}
