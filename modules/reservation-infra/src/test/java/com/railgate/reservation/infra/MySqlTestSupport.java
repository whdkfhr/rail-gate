package com.railgate.reservation.infra;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
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

    @BeforeAll
    static void startDatabase() {
        if (!MYSQL.isRunning()) {
            MYSQL.start();
        }
        if (dataSource == null) {
            dataSource = createDataSource();
            jdbcTemplate = new JdbcTemplate(dataSource);
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

    @BeforeEach
    void resetSeatInventory() {
        jdbcTemplate.execute("DELETE FROM seat_inventory");
    }

    protected static JdbcTemplate jdbc() {
        return jdbcTemplate;
    }

    protected static DataSource dataSource() {
        return dataSource;
    }

    protected static int lockWaitTimeoutSeconds() {
        return LOCK_WAIT_TIMEOUT_SECONDS;
    }

    protected static int poolSize() {
        return POOL_SIZE;
    }

    /** 판매 가능한 좌석 한 개를 넣고 그 id 를 돌려준다. */
    protected static long insertAvailableSeat(long scheduleId, String seatNo) {
        jdbc().update(
                "INSERT INTO seat_inventory (schedule_id, seat_no, status) VALUES (?, ?, 'AVAILABLE')",
                scheduleId, seatNo);
        return jdbc().queryForObject(
                "SELECT id FROM seat_inventory WHERE schedule_id = ? AND seat_no = ?",
                Long.class, scheduleId, seatNo);
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
}
