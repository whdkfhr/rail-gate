package com.railgate.reservation.infra.saleevent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.railgate.reservation.infra.MySqlTestSupport;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ★ 운영 마이그레이션 적용 절차 (Task 2G-E-B2).
 *
 * <h2>B1 계획의 정정</h2>
 *
 * <p>[2G-E-B1] §12 는 {@code V4 생성 → V5 backfill → V6 FK} 를 계획했다.
 * <b>구현하면서 V5 를 마이그레이션 파일로 만들 수 없다는 것이 드러났다.</b>
 *
 * <p>backfill 은 "운행편이 어느 판매 회차에 속하는가" 를 적는 일인데,
 * <b>이 저장소에는 그 답의 원천이 없다.</b> 운영 데이터도, 매핑 표도, 등록 API 도 없다.
 * 마이그레이션 파일이 그 답을 갖고 있으려면 <b>지어내는 수밖에 없고</b>, 그것은 B1 §12 가
 * 재현해 금지한 세 지름길("기본 회차 몰아넣기", "scheduleId 대입")과 정확히 같다.
 * 빈 V5 를 넣는 것은 아무 일도 하지 않으면서 단계를 밟은 것처럼 보이게 만든다.
 *
 * <p>그래서 backfill 을 <b>마이그레이션이 아니라 V4 와 FK 사이의 명시적 운영 단계</b>로 옮겼다.
 *
 * <pre>
 * V4                  sale_event · train_schedule 생성        (자동)
 * ── 운영 단계 ──      운영자가 명시적 매핑을 입력                (수동, 마이그레이션 아님)
 * V5                  orphan 0 검증 → seat_inventory FK 추가   (자동)
 * </pre>
 *
 * <p>배포 절차는 {@code flyway migrate -target=4} → 매핑 주입 → {@code flyway migrate} 다.
 * 이 테스트가 그 절차를 그대로 실행한다.
 *
 * <h2>격리</h2>
 *
 * <p>공유 컨테이너에 <b>별도 스키마</b>를 만들어 거기서 마이그레이션을 처음부터 돌린다.
 * {@link MySqlTestSupport} 의 기본 스키마는 이미 최신 버전으로 마이그레이션돼 있어
 * 중간 단계를 재현할 수 없고, 건드리면 다른 테스트가 깨진다.
 */
@Timeout(300)
@DisplayName("V4·V5 마이그레이션 절차 (Task 2G-E-B2)")
class SaleEventSchemaMigrationTest extends MySqlTestSupport {

    private static final String SCHEMA = "railgate_mig_2geb2";

    /** 기존 운영 데이터를 대표한다. 참조 대상 없는 schedule_id 로 들어가 있다. */
    private static final long LEGACY_SCHEDULE_A = 101L;
    private static final long LEGACY_SCHEDULE_B = 202L;

    private static final long CHUSEOK = 9001L;
    private static final long SEOLLAL = 9002L;

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
        return java.sql.DriverManager.getConnection(jdbcUrl(), "root", rootPassword());
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

    /** 지정한 버전까지만 마이그레이션한다. {@code null} 이면 끝까지. */
    private void migrateTo(String version) {
        var configuration = Flyway.configure()
                .dataSource(schemaDataSource)
                .locations("classpath:db/migration");
        if (version != null) {
            configuration = configuration.target(MigrationVersion.fromVersion(version));
        }
        configuration.load().migrate();
    }

    /**
     * 실패한 마이그레이션 기록을 정리한다.
     *
     * <p><b>이 단계가 필요하다는 것이 이 Task 의 운영 발견 중 하나다.</b> Flyway 는 실패한
     * 시도를 {@code flyway_schema_history} 에 남기고, 그 뒤로는 {@code migrate} 를
     * {@code FlywayValidateException} 으로 거부한다 — 매핑을 넣어 원인을 없앤 뒤에도 그렇다.
     *
     * <p>우리 V5 는 <b>절반만 적용된 변경을 남기지 않는다.</b> 가드가 FK 추가보다 먼저 실패하고,
     * 가드 테이블은 세션 임시 테이블이라 커넥션이 닫히면 사라진다. 그래서 스키마를 손볼 것은
     * 없고 {@code repair} 로 이력만 정리하면 된다.
     */
    private void repair() {
        Flyway.configure()
                .dataSource(schemaDataSource)
                .locations("classpath:db/migration")
                .load()
                .repair();
    }

    private String currentVersion() {
        return Flyway.configure()
                .dataSource(schemaDataSource)
                .locations("classpath:db/migration")
                .load()
                .info()
                .current()
                .getVersion()
                .getVersion();
    }

    private void insertLegacySeats(long scheduleId, int count) {
        for (int i = 1; i <= count; i++) {
            schemaJdbc.update(
                    "INSERT INTO seat_inventory (schedule_id, seat_no, status) VALUES (?, ?, 'AVAILABLE')",
                    scheduleId, i + "A");
        }
    }

    private long seatCount() {
        return schemaJdbc.queryForObject("SELECT COUNT(*) FROM seat_inventory", Long.class);
    }

    private List<Long> orphanSchedules() {
        return schemaJdbc.queryForList("""
                SELECT DISTINCT s.schedule_id
                  FROM seat_inventory s
                  LEFT JOIN train_schedule ts ON ts.id = s.schedule_id
                 WHERE ts.id IS NULL
                 ORDER BY s.schedule_id
                """, Long.class);
    }

    private boolean seatScheduleForeignKeyExists() {
        return schemaJdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM information_schema.table_constraints
                 WHERE table_schema = ?
                   AND table_name = 'seat_inventory'
                   AND constraint_name = 'fk_seat_inventory_schedule'
                   AND constraint_type = 'FOREIGN KEY'
                """, Long.class, SCHEMA) > 0;
    }

    /** 운영자가 하는 일. 마이그레이션이 아니라 사람이 근거를 갖고 입력한다. */
    private void operatorRegistersMappings() {
        schemaJdbc.update("""
                INSERT INTO sale_event (id, name, opens_at, closes_at, status)
                VALUES (?, ?, '2026-09-01 09:00:00.000', NULL, 'SCHEDULED'),
                       (?, ?, '2027-01-15 09:00:00.000', NULL, 'SCHEDULED')
                """, CHUSEOK, "2026년 추석 승차권 판매", SEOLLAL, "2027년 설 승차권 판매");
        schemaJdbc.update(
                "INSERT INTO train_schedule (id, sale_event_id) VALUES (?, ?), (?, ?)",
                LEGACY_SCHEDULE_A, CHUSEOK, LEGACY_SCHEDULE_B, SEOLLAL);
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("§1 fresh migration")
    class 처음부터_적용 {

        @Test
        void 빈_스키마에서는_끝까지_적용된다() {
            assertThatCode(() -> migrateTo(null)).doesNotThrowAnyException();

            assertThat(currentVersion()).isEqualTo("5");
            assertThat(seatScheduleForeignKeyExists()).isTrue();
        }

        @Test
        void sale_event_상태는_대소문자를_구분한다() {
            migrateTo(null);
            schemaJdbc.update("""
                    INSERT INTO sale_event (id, name, opens_at, status)
                    VALUES (?, '추석', '2026-09-01 09:00:00.000', 'SCHEDULED')
                    """, CHUSEOK);

            // Spring 은 MySQL 의 CHECK 위반(3819)을 DataIntegrityViolationException 이 아니라
            // UncategorizedSQLException 으로 올린다. 여기서 검증할 것은 예외 타입이 아니라
            // "소문자가 거부된다" 는 사실이므로 제약 이름으로 확인한다.
            assertThatThrownBy(() -> schemaJdbc.update("""
                    INSERT INTO sale_event (id, name, opens_at, status)
                    VALUES (?, '설', '2027-01-15 09:00:00.000', 'scheduled')
                    """, SEOLLAL))
                    .as("소문자 상태가 CHECK 를 통과하면 조건부 UPDATE 의 WHERE 도 흔들린다")
                    .isInstanceOf(org.springframework.dao.DataAccessException.class)
                    .hasMessageContaining("chk_sale_event_status");
        }

        @Test
        void train_schedule_은_존재하지_않는_판매_회차를_참조할_수_없다() {
            migrateTo(null);

            assertThatThrownBy(() -> schemaJdbc.update(
                    "INSERT INTO train_schedule (id, sale_event_id) VALUES (?, ?)", 1L, 8888L))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }

        @Test
        void seat_inventory_에_sale_event_id_를_비정규화하지_않는다() {
            migrateTo(null);

            List<String> columns = schemaJdbc.queryForList("""
                    SELECT column_name FROM information_schema.columns
                     WHERE table_schema = ? AND table_name = 'seat_inventory'
                    """, String.class, SCHEMA);

            assertThat(columns)
                    .as("2G-E-B1 §8 정규화 결정. 좌석 행은 회차를 복제하지 않는다")
                    .doesNotContain("sale_event_id");
        }
    }

    /**
     * §2 ★ 기존 데이터가 있는 단계적 upgrade.
     *
     * <p>이 절차 전체가 이 Task 의 핵심 산출물이다.
     */
    @Nested
    @DisplayName("§2 ★ 기존 데이터가 있는 단계적 upgrade")
    class 단계적_upgrade {

        /** V3 까지 적용된 상태 = 이 Task 이전의 운영 스키마. */
        private void givenLegacyDeployment() {
            migrateTo("3");
            insertLegacySeats(LEGACY_SCHEDULE_A, 4);
            insertLegacySeats(LEGACY_SCHEDULE_B, 4);
        }

        @Test
        void V4_는_기존_좌석을_건드리지_않고_테이블만_만든다() {
            givenLegacyDeployment();

            migrateTo("4");

            assertThat(currentVersion()).isEqualTo("4");
            assertThat(seatCount()).isEqualTo(8);
            assertThat(seatScheduleForeignKeyExists())
                    .as("FK 는 V5 의 일이다. V4 는 아직 걸지 않는다")
                    .isFalse();
        }

        /**
         * ★ 매핑을 입력하지 않고 진행하면 <b>실패한다.</b>
         *
         * <p>그리고 <b>기존 좌석 데이터는 그대로 남는다.</b> 조용히 지우거나 아무 회차에나
         * 붙이지 않는다. 운영자가 매핑을 넣은 뒤 같은 마이그레이션을 다시 돌릴 수 있어야 한다.
         */
        @Test
        void 매핑_없이_V5_를_적용하면_실패하고_좌석은_보존된다() {
            givenLegacyDeployment();
            migrateTo("4");

            assertThat(orphanSchedules()).containsExactly(LEGACY_SCHEDULE_A, LEGACY_SCHEDULE_B);
            assertThatThrownBy(() -> migrateTo(null))
                    .as("orphan 이 있으면 마이그레이션이 실패해야 한다")
                    .isInstanceOf(Exception.class);

            assertThat(seatCount()).as("좌석을 조용히 지우지 않는다").isEqualTo(8);
            assertThat(seatScheduleForeignKeyExists()).isFalse();
        }

        /** 실패 원인을 마이그레이션 이름이나 제약 이름으로 알 수 있어야 한다. */
        @Test
        void 실패_원인에서_미매핑_운행편임을_알_수_있다() {
            givenLegacyDeployment();
            migrateTo("4");

            assertThatThrownBy(() -> migrateTo(null))
                    .hasMessageContaining("V5")
                    .hasStackTraceContaining("unmapped_train_schedule");
        }

        /** ★ 매핑을 넣은 뒤 같은 마이그레이션을 재개하면 통과한다. */
        @Test
        void 매핑을_입력한_뒤_재개하면_적용된다() {
            givenLegacyDeployment();
            migrateTo("4");

            operatorRegistersMappings();

            assertThat(orphanSchedules()).isEmpty();
            assertThatCode(() -> migrateTo(null)).doesNotThrowAnyException();

            assertThat(currentVersion()).isEqualTo("5");
            assertThat(seatScheduleForeignKeyExists()).isTrue();
            assertThat(seatCount()).isEqualTo(8);
        }

        /**
         * ★ 실패한 뒤에는 {@code flyway repair} 가 필요하다.
         *
         * <p>매핑을 넣어 <b>원인을 없앤 뒤에도</b> {@code migrate} 만으로는 재개되지 않는다.
         * Flyway 가 실패한 시도를 이력에 남겨 두고 검증에서 거부하기 때문이다.
         * 배포 절차에 이 단계를 적어 두지 않으면 운영자가 여기서 막힌다.
         */
        @Test
        void 실패한_뒤에는_repair_없이_재개되지_않는다() {
            givenLegacyDeployment();
            migrateTo("4");
            assertThatThrownBy(() -> migrateTo(null)).isInstanceOf(Exception.class);

            operatorRegistersMappings();

            assertThatThrownBy(() -> migrateTo(null))
                    .as("원인을 없애도 실패 이력이 남아 있으면 거부된다")
                    .hasMessageContaining("repair");
        }

        /** repair 로 이력을 정리하면 같은 파일이 그대로 적용된다. */
        @Test
        void 실패한_뒤_매핑을_넣고_repair_하면_재실행된다() {
            givenLegacyDeployment();
            migrateTo("4");
            assertThatThrownBy(() -> migrateTo(null)).isInstanceOf(Exception.class);

            operatorRegistersMappings();
            repair();

            assertThatCode(() -> migrateTo(null))
                    .as("V5 는 절반만 적용된 변경을 남기지 않으므로 이력 정리만으로 충분하다")
                    .doesNotThrowAnyException();
            assertThat(seatScheduleForeignKeyExists()).isTrue();
            assertThat(seatCount()).isEqualTo(8);
        }
    }

    /**
     * §3 FK 가 실제로 보장하는 것.
     *
     * <p>{@code sale_event_id} UPDATE 를 막지는 <b>못한다</b> (2G-E-B1 §9).
     */
    @Nested
    @DisplayName("§3 FK 가 보장하는 범위")
    class FK_보장_범위 {

        private void givenMigratedWithMappings() {
            migrateTo("4");
            operatorRegistersMappings();
            migrateTo(null);
        }

        @Test
        void 미매핑_운행편의_좌석은_넣을_수_없다() {
            givenMigratedWithMappings();

            assertThatThrownBy(() -> insertLegacySeats(777L, 1))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }

        @Test
        void 좌석이_있는_운행편은_지울_수_없다() {
            givenMigratedWithMappings();
            insertLegacySeats(LEGACY_SCHEDULE_A, 1);

            assertThatThrownBy(() -> schemaJdbc.update(
                    "DELETE FROM train_schedule WHERE id = ?", LEGACY_SCHEDULE_A))
                    .as("B1 §11 에서 트리거의 구멍으로 지목한 DELETE 후 재삽입을 좁힌다")
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }

        /**
         * ★ FK 가 막지 <b>못하는</b> 것 — 남은 위험을 테스트로 고정한다.
         *
         * <p>트리거를 넣지 않았으므로 (마이그레이션 계정 분리가 전제, B1 §11)
         * 같은 운행편의 소속을 다른 유효한 회차로 바꾸는 UPDATE 는 <b>그대로 통과한다.</b>
         */
        @Test
        void 유효한_다른_회차로의_소속_UPDATE_는_여전히_통과한다() {
            givenMigratedWithMappings();

            int affected = schemaJdbc.update(
                    "UPDATE train_schedule SET sale_event_id = ? WHERE id = ?",
                    SEOLLAL, LEGACY_SCHEDULE_A);

            assertThat(affected)
                    .as("이 Task 는 이것을 막지 않는다. 트리거는 계정 분리 결정과 함께 판단한다")
                    .isEqualTo(1);
        }
    }
}
