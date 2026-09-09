package com.railgate.reservation.infra.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.railgate.reservation.infra.MySqlTestSupport;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ★ V6 {@code user_hold_quota} 마이그레이션 (Task 2G-G).
 *
 * <h2>I-12 의 FK 대상이 이제 존재한다</h2>
 *
 * <p>2G-D 가 quota 범위를 {@code sale_event_id} 로 결정했고 2G-E-B2 가 {@code sale_event}
 * 테이블을 만들었다. 그때까지 "FK 대상이 없어 마이그레이션을 쓸 수 없다" 던 것이 해소됐다.
 *
 * <h2>테이블은 <b>비어 있는 채로</b> 만든다</h2>
 *
 * <p>기존 활성 좌석이 있는 배포에서 카운터를 backfill 하지 않는다. 이유는 §
 * {@link 기존_데이터_업그레이드} 에서 실측으로 다룬다 — 짧게 말하면
 * <b>I-12 가 한 번도 강제된 적이 없어서 상한을 넘는 사용자가 이미 있을 수 있고</b>,
 * 그런 데이터를 CHECK 가 있는 표에 그대로 옮길 수 없다.
 *
 * <p>비어 있어도 안전한 이유는 <b>아직 아무도 이 표를 읽지 않기 때문</b>이다.
 * 위험은 Task 2H 가 요청 경로에 quota 검사를 배선하는 순간 생긴다.
 */
@Timeout(300)
@DisplayName("V6 user_hold_quota 마이그레이션 (Task 2G-G)")
class UserHoldQuotaSchemaMigrationTest extends MySqlTestSupport {

    private static final String SCHEMA = "railgate_mig_2gg";

    private static final long CHUSEOK = 9001L;
    private static final long SCHEDULE = 101L;
    private static final long USER = 11L;

    private HikariDataSource schemaDataSource;
    private JdbcTemplate schemaJdbc;

    @BeforeEach
    void createFreshSchema() throws SQLException {
        try (Connection root = rootConnection(); Statement st = root.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + SCHEMA);
            st.execute("CREATE DATABASE " + SCHEMA
                    + " DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        schemaDataSource = dataSourceFor(SCHEMA);
        schemaJdbc = new JdbcTemplate(schemaDataSource);
    }

    @AfterEach
    void dropSchema() throws SQLException {
        if (schemaDataSource != null) {
            schemaDataSource.close();
        }
        try (Connection root = rootConnection(); Statement st = root.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + SCHEMA);
        }
    }

    private static Connection rootConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), "root", rootPassword());
    }

    private HikariDataSource dataSourceFor(String schema) {
        String url = jdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + schema + "$1");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername("root");
        config.setPassword(rootPassword());
        config.setMaximumPoolSize(4);
        return new HikariDataSource(config);
    }

    private void migrateTo(String version) {
        var configuration = Flyway.configure()
                .dataSource(schemaDataSource)
                .locations("classpath:db/migration");
        if (version != null) {
            configuration = configuration.target(MigrationVersion.fromVersion(version));
        }
        configuration.load().migrate();
    }

    private String currentVersion() {
        return Flyway.configure().dataSource(schemaDataSource)
                .locations("classpath:db/migration").load()
                .info().current().getVersion().getVersion();
    }

    private void seedSaleEventAndSchedule() {
        schemaJdbc.update("""
                INSERT INTO sale_event (id, name, opens_at, status)
                VALUES (?, '2G-G 실험 회차', DATE_SUB(NOW(3), INTERVAL 1 DAY), 'OPEN')
                """, CHUSEOK);
        schemaJdbc.update("INSERT INTO train_schedule (id, sale_event_id) VALUES (?, ?)",
                SCHEDULE, CHUSEOK);
    }

    /** 활성 좌석 하나를 만든다. 상태 전이를 우회하지 않고 직접 넣는다(마이그레이션 전 상태 재현). */
    private void insertHeldSeat(String seatNo, long heldBy) {
        schemaJdbc.update("""
                INSERT INTO seat_inventory
                       (schedule_id, seat_no, status, hold_id, held_by, held_at, expires_at)
                VALUES (?, ?, 'HELD', UUID(), ?, NOW(3), DATE_ADD(NOW(3), INTERVAL 5 MINUTE))
                """, SCHEDULE, seatNo, heldBy);
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("§1 fresh 설치")
    class Fresh_설치 {

        @Test
        void 빈_스키마에서_V6_까지_적용된다() {
            assertThatCode(() -> migrateTo(null)).doesNotThrowAnyException();

            assertThat(currentVersion()).isEqualTo("6");
            assertThat(schemaJdbc.queryForObject(
                    "SELECT COUNT(*) FROM user_hold_quota", Long.class))
                    .as("카운터는 비어 있는 채로 시작한다")
                    .isZero();
        }

        @Test
        void PK_는_sale_event_id_와_user_id_다() {
            migrateTo(null);

            List<String> pkColumns = schemaJdbc.queryForList("""
                    SELECT column_name FROM information_schema.key_column_usage
                     WHERE table_schema = ? AND table_name = 'user_hold_quota'
                       AND constraint_name = 'PRIMARY'
                     ORDER BY ordinal_position
                    """, String.class, SCHEMA);

            assertThat(pkColumns)
                    .as("2G-B·2G-D 가 정한 잠금 키 순서와 같아야 한다")
                    .containsExactly("sale_event_id", "user_id");
        }

        @Test
        void sale_event_를_참조하는_외래_키가_있다() {
            migrateTo(null);
            seedSaleEventAndSchedule();

            assertThatThrownBy(() -> schemaJdbc.update(
                    "INSERT INTO user_hold_quota (sale_event_id, user_id, held_seats) VALUES (?, ?, 0)",
                    8888L, USER))
                    .as("존재하지 않는 회차의 카운터는 만들 수 없다")
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        void held_seats_는_NOT_NULL_이고_기본값이_0_이다() {
            migrateTo(null);
            seedSaleEventAndSchedule();

            schemaJdbc.update(
                    "INSERT INTO user_hold_quota (sale_event_id, user_id) VALUES (?, ?)",
                    CHUSEOK, USER);

            assertThat(schemaJdbc.queryForObject(
                    "SELECT held_seats FROM user_hold_quota WHERE sale_event_id = ? AND user_id = ?",
                    Integer.class, CHUSEOK, USER))
                    .isZero();
        }

        @Test
        void 음수와_상한_초과는_CHECK_가_막는다() {
            migrateTo(null);
            seedSaleEventAndSchedule();

            assertThatThrownBy(() -> schemaJdbc.update(
                    "INSERT INTO user_hold_quota (sale_event_id, user_id, held_seats) VALUES (?, ?, -1)",
                    CHUSEOK, USER))
                    .hasMessageContaining("ck_user_hold_quota_range");

            assertThatThrownBy(() -> schemaJdbc.update(
                    "INSERT INTO user_hold_quota (sale_event_id, user_id, held_seats) VALUES (?, ?, 5)",
                    CHUSEOK, USER))
                    .as("P-2 상한 4석을 넘는 값은 저장될 수 없다")
                    .hasMessageContaining("ck_user_hold_quota_range");
        }

        @Test
        void 같은_회차_사용자의_카운터는_하나뿐이다() {
            migrateTo(null);
            seedSaleEventAndSchedule();
            schemaJdbc.update(
                    "INSERT INTO user_hold_quota (sale_event_id, user_id, held_seats) VALUES (?, ?, 1)",
                    CHUSEOK, USER);

            assertThatThrownBy(() -> schemaJdbc.update(
                    "INSERT INTO user_hold_quota (sale_event_id, user_id, held_seats) VALUES (?, ?, 2)",
                    CHUSEOK, USER))
                    .isInstanceOf(DataAccessException.class);
        }

        /** 기존 스키마의 시간 컬럼 규약을 따른다 (DATETIME(3), 자동 갱신). */
        @Test
        void 시간_컬럼은_밀리초_정밀도다() {
            migrateTo(null);

            List<String> types = schemaJdbc.queryForList("""
                    SELECT column_type FROM information_schema.columns
                     WHERE table_schema = ? AND table_name = 'user_hold_quota'
                       AND column_name IN ('created_at', 'updated_at')
                    """, String.class, SCHEMA);

            assertThat(types).hasSize(2).allSatisfy(
                    type -> assertThat(type).isEqualTo("datetime(3)"));
        }
    }

    /**
     * ★ §2 기존 데이터가 있는 업그레이드.
     *
     * <p>여기서 확인하는 것은 <b>"비어 있는 채로 시작해도 마이그레이션은 통과한다"</b> 와
     * <b>"그래서 위험하다"</b> 두 가지다. 둘을 함께 봐야 절차가 설계된다.
     */
    @Nested
    @DisplayName("§2 ★ 기존 활성 좌석이 있는 업그레이드")
    class 기존_데이터_업그레이드 {

        private void givenDeploymentWithActiveSeats(int seatCount, long heldBy) {
            migrateTo("5");
            seedSaleEventAndSchedule();
            for (int i = 1; i <= seatCount; i++) {
                insertHeldSeat(i + "A", heldBy);
            }
        }

        @Test
        void 활성_좌석이_있어도_V6_은_적용된다() {
            givenDeploymentWithActiveSeats(3, USER);

            assertThatCode(() -> migrateTo(null)).doesNotThrowAnyException();

            assertThat(currentVersion()).isEqualTo("6");
            assertThat(schemaJdbc.queryForObject(
                    "SELECT COUNT(*) FROM seat_inventory", Long.class))
                    .as("좌석을 건드리지 않는다")
                    .isEqualTo(3);
        }

        /**
         * ★ 그 대가 — 카운터가 비어 있어 <b>활성 좌석이 집계되지 않는다.</b>
         *
         * <p>이 상태에서 요청 경로가 quota 를 강제하기 시작하면, 이미 3석을 잡은 사용자가
         * <b>4석을 더 잡을 수 있다.</b> 그래서 마이그레이션 적용과 <b>강제 활성화는 별개 절차</b>다.
         */
        @Test
        void 카운터가_비어_있어_활성_좌석이_집계되지_않는다() {
            givenDeploymentWithActiveSeats(3, USER);
            migrateTo(null);

            long activeSeats = schemaJdbc.queryForObject("""
                    SELECT COUNT(*) FROM seat_inventory
                     WHERE held_by = ? AND status IN ('HELD','PAYING')
                    """, Long.class, USER);
            long counterRows = schemaJdbc.queryForObject("""
                    SELECT COUNT(*) FROM user_hold_quota WHERE user_id = ?
                    """, Long.class, USER);

            assertThat(activeSeats).isEqualTo(3);
            assertThat(counterRows)
                    .as("★ 좌석은 3석인데 카운터 행이 없다 — 강제 활성화 전에 채워야 한다")
                    .isZero();
        }

        /**
         * ★ 자동 backfill 을 마이그레이션에 넣지 못하는 이유.
         *
         * <p><b>I-12 는 한 번도 강제된 적이 없다.</b> 그래서 상한을 넘는 사용자가 이미 있을 수 있고,
         * 그 값을 CHECK 가 있는 표에 그대로 옮기려 하면 실패한다. 어느 좌석을 빼야 하는지는
         * 데이터가 답하지 않는다 — <b>정책 결정이 필요하다.</b>
         */
        @Test
        void 상한을_넘는_사용자가_이미_있으면_backfill_이_불가능하다() {
            givenDeploymentWithActiveSeats(6, USER);
            migrateTo(null);

            assertThatThrownBy(() -> schemaJdbc.update("""
                    INSERT INTO user_hold_quota (sale_event_id, user_id, held_seats)
                    SELECT ts.sale_event_id, s.held_by, COUNT(*)
                      FROM seat_inventory s
                      JOIN train_schedule ts ON ts.id = s.schedule_id
                     WHERE s.status IN ('HELD','PAYING') AND s.held_by IS NOT NULL
                     GROUP BY ts.sale_event_id, s.held_by
                    """))
                    .as("★ 6석을 잡은 사용자의 값이 CHECK 를 위반한다")
                    .hasMessageContaining("ck_user_hold_quota_range");

            assertThat(schemaJdbc.queryForObject(
                    "SELECT COUNT(*) FROM user_hold_quota", Long.class))
                    .as("실패해도 부분 반영이 남지 않는다")
                    .isZero();
        }

        /** 상한 안에 있으면 운영자가 명시적으로 backfill 할 수 있다. 마이그레이션이 아니라 운영 단계다. */
        @Test
        void 상한_안의_데이터는_운영자가_명시적으로_backfill_할_수_있다() {
            givenDeploymentWithActiveSeats(3, USER);
            migrateTo(null);

            assertThatCode(() -> schemaJdbc.update("""
                    INSERT INTO user_hold_quota (sale_event_id, user_id, held_seats)
                    SELECT ts.sale_event_id, s.held_by, COUNT(*)
                      FROM seat_inventory s
                      JOIN train_schedule ts ON ts.id = s.schedule_id
                     WHERE s.status IN ('HELD','PAYING') AND s.held_by IS NOT NULL
                     GROUP BY ts.sale_event_id, s.held_by
                    """)).doesNotThrowAnyException();

            assertThat(schemaJdbc.queryForObject("""
                    SELECT held_seats FROM user_hold_quota WHERE sale_event_id = ? AND user_id = ?
                    """, Integer.class, CHUSEOK, USER))
                    .isEqualTo(3);
        }

        /** 강제 활성화 전 점검 쿼리 — 상한을 넘는 사용자가 있는지. */
        @Test
        void 강제_활성화_전_점검_쿼리가_상한_초과를_찾아낸다() {
            givenDeploymentWithActiveSeats(6, USER);
            migrateTo(null);

            List<Long> overLimit = schemaJdbc.queryForList("""
                    SELECT s.held_by
                      FROM seat_inventory s
                      JOIN train_schedule ts ON ts.id = s.schedule_id
                     WHERE s.status IN ('HELD','PAYING') AND s.held_by IS NOT NULL
                     GROUP BY ts.sale_event_id, s.held_by
                    HAVING COUNT(*) > 4
                    """, Long.class);

            assertThat(overLimit)
                    .as("이 쿼리가 행을 반환하면 backfill 을 중단하고 정책을 정해야 한다")
                    .containsExactly(USER);
        }
    }

    /** V1~V5 를 수정하지 않았음을 스키마로 확인한다. */
    @Test
    void 기존_마이그레이션이_그대로_적용된다() {
        migrateTo(null);

        List<String> tables = schemaJdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema = ? AND table_type = 'BASE TABLE'
                 ORDER BY table_name
                """, String.class, SCHEMA);

        assertThat(tables).contains(
                "flyway_schema_history", "sale_event", "seat_inventory",
                "train_schedule", "user_hold_quota");
    }
}
