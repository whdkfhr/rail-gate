package com.railgate.reservation.infra.saleevent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.railgate.reservation.infra.MySqlTestSupport;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * ★ 실험 B — 같은 운행편의 판매 회차 소속 변경을 DB 에서 어떻게 막는가 (Task 2G-E-B1).
 *
 * <h2>2G-E-A 가 남긴 구멍</h2>
 *
 * <p>도메인은 <b>인스턴스 불변</b>과 <b>공개 재배정 API 부재</b>까지만 보장했다.
 * 같은 {@code TrainScheduleId} 의 <b>영속</b> 소속이 갱신되는 것은 막지 못한다.
 * 소속이 바뀌면 이미 쌓인 quota 의 의미가 달라진다 (2G-D §4).
 *
 * <h2>§1 은 "통과하면 좋은" 테스트가 아니다</h2>
 *
 * <p>§1 이 통과한다는 것은 <b>FK 만으로는 소속이 불변이 되지 않는다는 것을 관측했다</b>는
 * 뜻이다 (CLAUDE.md 규칙 27). 흔한 오해를 먼저 실패로 고정한다.
 *
 * <pre>
 * FK 가 있으니 소속도 불변일 것이다
 *   → 실제로는 다른 유효한 SaleEventId 로 UPDATE 가 성공한다
 * </pre>
 *
 * <h2>범위</h2>
 *
 * <p><b>실험이다.</b> 여기서 고른 방어 수단을 운영 마이그레이션에 넣지 않는다.
 * 동작과 대가를 확인하고 다음 Task 의 적용 여부만 결정한다.
 */
@Timeout(300)
@DisplayName("실험 B — 소속 변경을 DB 에서 막는 수단 (Task 2G-E-B1)")
class TrainScheduleMembershipImmutabilityExperimentTest extends MySqlTestSupport {

    private static final String SALE_EVENT = "task2geb1_ms_sale_event";
    private static final String TRAIN_SCHEDULE = "task2geb1_ms_train_schedule";
    private static final String ASSOCIATION = "task2geb1_ms_association";
    private static final String TRIGGER = "trg_task2geb1_ms_membership";

    /** 불변식 위반을 로그·알람에서 식별할 수 있게 하는 표지. */
    private static final String VIOLATION_MARKER = "I-12-SCOPE";

    private static final long CHUSEOK = 9001L;
    private static final long SEOLLAL = 9002L;
    private static final long SCHEDULE = 101L;

    @BeforeEach
    void setUpSchema() {
        dropAll();

        jdbc().execute("""
                CREATE TABLE %s (
                    id   BIGINT       NOT NULL PRIMARY KEY,
                    name VARCHAR(100) NOT NULL
                ) ENGINE = InnoDB
                """.formatted(SALE_EVENT));

        // 2G-D §5 초안 + FK. note_seq 는 "소속과 무관한 컬럼" 을 대표한다.
        jdbc().execute("""
                CREATE TABLE %s (
                    id            BIGINT NOT NULL PRIMARY KEY,
                    sale_event_id BIGINT NOT NULL,
                    note_seq      BIGINT NOT NULL DEFAULT 0,
                    KEY idx_%s_sale_event (sale_event_id),
                    CONSTRAINT fk_%s_sale_event
                        FOREIGN KEY (sale_event_id) REFERENCES %s (id)
                ) ENGINE = InnoDB
                """.formatted(TRAIN_SCHEDULE, TRAIN_SCHEDULE, TRAIN_SCHEDULE, SALE_EVENT));

        jdbc().update("INSERT INTO %s (id, name) VALUES (?, ?), (?, ?)".formatted(SALE_EVENT),
                CHUSEOK, "추석 판매", SEOLLAL, "설 판매");
        jdbc().update("INSERT INTO %s (id, sale_event_id) VALUES (?, ?)".formatted(TRAIN_SCHEDULE),
                SCHEDULE, CHUSEOK);
    }

    @AfterAll
    static void tearDown() {
        dropAll();
    }

    private static void dropAll() {
        executeAsRoot("DROP TRIGGER IF EXISTS railgate." + TRIGGER);
        jdbc().execute("DROP TABLE IF EXISTS " + ASSOCIATION);
        jdbc().execute("DROP TABLE IF EXISTS " + TRAIN_SCHEDULE);
        jdbc().execute("DROP TABLE IF EXISTS " + SALE_EVENT);
    }

    private static long saleEventOf(long scheduleId) {
        return jdbc().queryForObject(
                "SELECT sale_event_id FROM %s WHERE id = ?".formatted(TRAIN_SCHEDULE),
                Long.class, scheduleId);
    }

    private static int reassign(long scheduleId, long saleEventId) {
        return jdbc().update("UPDATE %s SET sale_event_id = ? WHERE id = ?".formatted(TRAIN_SCHEDULE),
                saleEventId, scheduleId);
    }

    /**
     * 권한이 검증 대상인 실험에서만 쓰는 관리 계정 커넥션.
     *
     * <p>운영에 비유하면 <b>마이그레이션 계정</b>이다. 애플리케이션 계정
     * ({@link #jdbc()}) 과 구분해서 쓰는 것 자체가 §2·§4 의 관측 대상이다.
     */
    private static Connection rootConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), "root", rootPassword());
    }

    private static void executeAsRoot(String sql) {
        try (Connection root = rootConnection(); Statement st = root.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("관리 계정 실행 실패: " + sql, e);
        }
    }

    private static final String CREATE_MEMBERSHIP_TRIGGER = """
            CREATE TRIGGER %s
            BEFORE UPDATE ON %s
            FOR EACH ROW
            BEGIN
                IF NEW.sale_event_id <> OLD.sale_event_id THEN
                    SIGNAL SQLSTATE '45000'
                        SET MESSAGE_TEXT = '%s: train_schedule.sale_event_id is immutable';
                END IF;
            END
            """.formatted(TRIGGER, TRAIN_SCHEDULE, VIOLATION_MARKER);

    // ------------------------------------------------------------------

    /**
     * §1 실패 재현 — FK 의 보장 범위.
     *
     * <p>FK 는 <b>"유효한 SaleEvent 를 참조한다"</b> 만 보장한다.
     * <b>"처음 참조한 그 SaleEvent 를 계속 참조한다" 는 보장하지 않는다.</b> 다른 규칙이다.
     */
    @Nested
    @DisplayName("§1 ★ FK 만으로는 소속이 불변이 되지 않는다")
    class FK의_보장_범위 {

        @Test
        void 존재하지_않는_판매_회차_참조는_INSERT_가_거부된다() {
            assertThatThrownBy(() -> jdbc().update(
                    "INSERT INTO %s (id, sale_event_id) VALUES (?, ?)".formatted(TRAIN_SCHEDULE),
                    999L, 8888L))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void 존재하지_않는_판매_회차로의_UPDATE_도_거부된다() {
            assertThatThrownBy(() -> reassign(SCHEDULE, 8888L))
                    .isInstanceOf(DataIntegrityViolationException.class);

            assertThat(saleEventOf(SCHEDULE)).isEqualTo(CHUSEOK);
        }

        /**
         * ★ 이 테스트가 통과한다는 것은 <b>우회를 관측했다</b>는 뜻이다.
         *
         * <p>추석 회차의 운행편이 설 회차로 옮겨간다. FK 는 아무 말도 하지 않는다.
         * 그 운행편의 좌석에 쌓인 quota 는 이제 어느 회차의 것인지 되짚을 수 없다.
         */
        @Test
        void 다른_유효한_판매_회차로의_UPDATE_는_FK_가_막지_못한다() {
            int affected = reassign(SCHEDULE, SEOLLAL);

            assertThat(affected).isEqualTo(1);
            assertThat(saleEventOf(SCHEDULE))
                    .as("FK 는 '유효한 참조' 만 보장하고 '기존 소속 유지' 는 보장하지 않는다")
                    .isEqualTo(SEOLLAL);
        }
    }

    /**
     * §2 대안 2 — {@code BEFORE UPDATE} 트리거.
     *
     * <p>값이 <b>바뀌는</b> UPDATE 만 거부한다. 값이 같은 UPDATE 와 다른 컬럼 UPDATE 는
     * 통과해야 한다. 그렇지 않으면 정상적인 갱신 경로와 재시도가 함께 막힌다.
     */
    @Nested
    @DisplayName("§2 대안 2 — BEFORE UPDATE 트리거")
    class 트리거 {

        /**
         * 트리거는 <b>관리 계정으로만</b> 만들 수 있다. 그 사실 자체가 아래에서 관측된다.
         */
        @BeforeEach
        void createTrigger() {
            executeAsRoot(CREATE_MEMBERSHIP_TRIGGER);
        }

        /**
         * ★ 트리거의 첫 번째 대가 — <b>애플리케이션 계정으로는 만들 수 없다.</b>
         *
         * <p>바이너리 로깅이 켜진 MySQL 에서 트리거 생성에는 {@code SUPER}(또는
         * {@code SET_USER_ID}) 가 필요하다. 즉 이 방어를 택하면
         * <b>Flyway 를 애플리케이션 계정으로 돌릴 수 없다</b> — 마이그레이션 계정 분리가
         * 선택이 아니라 전제가 된다. 대안 4 와 얽히는 지점이다.
         */
        @Test
        void 애플리케이션_계정으로는_트리거를_만들_수_없다() {
            jdbc().execute("DROP TRIGGER IF EXISTS " + TRIGGER);

            assertThatThrownBy(() -> jdbc().execute(CREATE_MEMBERSHIP_TRIGGER))
                    .hasMessageContaining("SUPER");
        }

        @Test
        void 다른_판매_회차로의_재배정을_거부한다() {
            assertThatThrownBy(() -> reassign(SCHEDULE, SEOLLAL))
                    .hasMessageContaining(VIOLATION_MARKER);

            assertThat(saleEventOf(SCHEDULE)).isEqualTo(CHUSEOK);
        }

        @Test
        void 예외_메시지로_불변식_위반임을_식별할_수_있다() {
            assertThatThrownBy(() -> reassign(SCHEDULE, SEOLLAL))
                    .as("알람이 다른 오류와 구분할 수 있어야 한다")
                    .hasMessageContaining(VIOLATION_MARKER)
                    .hasMessageContaining("sale_event_id");
        }

        /** 같은 값 UPDATE 는 멱등이다. 재시도나 no-op 갱신이 막히면 안 된다. */
        @Test
        void 같은_판매_회차로의_UPDATE_는_허용한다() {
            assertThatCode(() -> reassign(SCHEDULE, CHUSEOK)).doesNotThrowAnyException();

            assertThat(saleEventOf(SCHEDULE)).isEqualTo(CHUSEOK);
        }

        @Test
        void 소속과_무관한_컬럼_UPDATE_는_허용한다() {
            assertThatCode(() -> jdbc().update(
                    "UPDATE %s SET note_seq = note_seq + 1 WHERE id = ?".formatted(TRAIN_SCHEDULE),
                    SCHEDULE))
                    .doesNotThrowAnyException();

            assertThat(saleEventOf(SCHEDULE)).isEqualTo(CHUSEOK);
        }

        @Test
        void 새_운행편_INSERT_는_영향받지_않는다() {
            assertThatCode(() -> jdbc().update(
                    "INSERT INTO %s (id, sale_event_id) VALUES (?, ?)".formatted(TRAIN_SCHEDULE),
                    202L, SEOLLAL))
                    .doesNotThrowAnyException();
        }

        /**
         * ★ 트리거가 막지 못하는 것 (1) — DELETE 후 재삽입.
         *
         * <p>{@code BEFORE UPDATE} 는 UPDATE 만 본다. 지우고 다시 넣으면 같은 id 가
         * 다른 소속으로 존재하게 된다. 이것을 {@code BEFORE DELETE} 로 막으면
         * <b>정당한 운행편 삭제까지 막힌다.</b>
         *
         * <p>운영에서 이 경로를 좁히는 것은 트리거가 아니라
         * {@code seat_inventory.schedule_id} FK 다 — 좌석이 붙은 운행편은 지워지지 않는다.
         * 그 FK 는 아직 없다 (Task 2G-E-B2).
         */
        @Test
        void DELETE_후_재삽입은_막지_못한다() {
            jdbc().update("DELETE FROM %s WHERE id = ?".formatted(TRAIN_SCHEDULE), SCHEDULE);
            jdbc().update("INSERT INTO %s (id, sale_event_id) VALUES (?, ?)".formatted(TRAIN_SCHEDULE),
                    SCHEDULE, SEOLLAL);

            assertThat(saleEventOf(SCHEDULE))
                    .as("트리거는 UPDATE 만 본다. 이 경로는 남는다")
                    .isEqualTo(SEOLLAL);
        }

        /**
         * ★ 트리거가 막지 못하는 것 (2) — 트리거를 지울 수 있는 계정.
         *
         * <p>같은 계정이 {@code DROP TRIGGER} 를 할 수 있다면 방어는 그 계정 앞에서 멈춘다.
         * <b>이것이 트리거를 "DB 수준 보장" 이라고 부르면 안 되는 이유다.</b>
         * 동시에 <b>운영자의 정당한 정정 경로</b>이기도 하다 — 지우고 고치고 되살리는 절차가
         * 명시적이라 감사 로그에 남는다.
         */
        @Test
        void 트리거를_지울_수_있는_계정_앞에서는_멈춘다() {
            jdbc().execute("DROP TRIGGER " + TRIGGER);

            int affected = reassign(SCHEDULE, SEOLLAL);

            assertThat(affected).isEqualTo(1);
            assertThat(saleEventOf(SCHEDULE)).isEqualTo(SEOLLAL);
        }

        /**
         * ★ 비대칭 — 애플리케이션 계정은 <b>지울 수는 있고 되살릴 수는 없다.</b>
         *
         * <p>{@code DROP TRIGGER} 는 {@code TRIGGER} 권한만 요구하지만
         * {@code CREATE TRIGGER} 는 {@code SUPER} 를 요구한다. 그래서 애플리케이션 계정에
         * {@code TRIGGER} 권한이 남아 있으면 <b>방어를 한 번 걷어낸 뒤 복구할 수 없는</b>
         * 상태가 된다. 대안 4 로 {@code TRIGGER} 권한까지 회수해야 하는 이유다.
         */
        @Test
        void 지운_트리거를_애플리케이션_계정이_되살릴_수는_없다() {
            jdbc().execute("DROP TRIGGER " + TRIGGER);

            assertThatThrownBy(() -> jdbc().execute(CREATE_MEMBERSHIP_TRIGGER))
                    .hasMessageContaining("SUPER");
        }
    }

    /**
     * §3 대안 3 — 별도 연결 테이블 (append-only 를 의도).
     *
     * <p>{@code UNIQUE (schedule_id)} 로 "운행편당 소속 한 개" 를 강제하면 소속이 불변이 될까.
     * <b>되지 않는다.</b> 새 행 삽입은 막지만 기존 행 UPDATE 는 그대로 열려 있다.
     * 즉 <b>문제를 한 테이블 옆으로 옮길 뿐</b>이고, 조회에는 조인이 하나 더 붙는다.
     */
    @Nested
    @DisplayName("§3 대안 3 — 연결 테이블")
    class 연결_테이블 {

        @BeforeEach
        void createAssociation() {
            jdbc().execute("""
                    CREATE TABLE %s (
                        schedule_id   BIGINT NOT NULL,
                        sale_event_id BIGINT NOT NULL,
                        PRIMARY KEY (schedule_id),
                        CONSTRAINT fk_%s_sale_event
                            FOREIGN KEY (sale_event_id) REFERENCES %s (id)
                    ) ENGINE = InnoDB
                    """.formatted(ASSOCIATION, ASSOCIATION, SALE_EVENT));
            jdbc().update("INSERT INTO %s (schedule_id, sale_event_id) VALUES (?, ?)"
                    .formatted(ASSOCIATION), SCHEDULE, CHUSEOK);
        }

        @Test
        void 같은_운행편에_두_번째_소속을_넣을_수_없다() {
            assertThatThrownBy(() -> jdbc().update(
                    "INSERT INTO %s (schedule_id, sale_event_id) VALUES (?, ?)".formatted(ASSOCIATION),
                    SCHEDULE, SEOLLAL))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        /** ★ 그러나 기존 행 UPDATE 는 그대로 열려 있다. 문제가 옮겨갔을 뿐이다. */
        @Test
        void 기존_연결_행의_UPDATE_는_여전히_가능하다() {
            int affected = jdbc().update(
                    "UPDATE %s SET sale_event_id = ? WHERE schedule_id = ?".formatted(ASSOCIATION),
                    SEOLLAL, SCHEDULE);

            assertThat(affected).isEqualTo(1);
            assertThat(jdbc().queryForObject(
                    "SELECT sale_event_id FROM %s WHERE schedule_id = ?".formatted(ASSOCIATION),
                    Long.class, SCHEDULE))
                    .as("UNIQUE 는 '한 개' 만 강제하고 '바뀌지 않음' 은 강제하지 않는다")
                    .isEqualTo(SEOLLAL);
        }

        @Test
        void DELETE_후_재삽입도_가능하다() {
            jdbc().update("DELETE FROM %s WHERE schedule_id = ?".formatted(ASSOCIATION), SCHEDULE);
            jdbc().update("INSERT INTO %s (schedule_id, sale_event_id) VALUES (?, ?)"
                    .formatted(ASSOCIATION), SCHEDULE, SEOLLAL);

            assertThat(jdbc().queryForObject(
                    "SELECT sale_event_id FROM %s WHERE schedule_id = ?".formatted(ASSOCIATION),
                    Long.class, SCHEDULE))
                    .isEqualTo(SEOLLAL);
        }
    }

    /**
     * §4 대안 4 — 컬럼 단위 UPDATE 권한 회수.
     *
     * <p>애플리케이션 계정에서 {@code sale_event_id} 의 UPDATE 권한만 뺀다.
     * 숨은 DB 로직이 없고 우회가 불가능하다는 점에서 가장 강하다.
     *
     * <p>다만 <b>계정을 만들 권한이 필요하다.</b> 여기서는 컨테이너 root 로 만든다.
     * 운영에서 마이그레이션 계정과 애플리케이션 계정을 분리하는 것은 현재 프로젝트 범위 밖이며,
     * 그 판단은 실험 문서 §11 에 적었다.
     */
    @Nested
    @DisplayName("§4 대안 4 — 컬럼 단위 권한 회수")
    class 권한_분리 {

        private static final String LIMITED_USER = "task2geb1_app";
        private static final String LIMITED_PASSWORD = "task2geb1_app_pw";

        @BeforeEach
        void grantLimitedPrivileges() throws SQLException {
            try (Connection root = rootConnection(); Statement st = root.createStatement()) {
                st.execute("DROP USER IF EXISTS '%s'@'%%'".formatted(LIMITED_USER));
                st.execute("CREATE USER '%s'@'%%' IDENTIFIED BY '%s'"
                        .formatted(LIMITED_USER, LIMITED_PASSWORD));
                st.execute("GRANT SELECT, INSERT, DELETE ON railgate.%s TO '%s'@'%%'"
                        .formatted(TRAIN_SCHEDULE, LIMITED_USER));
                // sale_event_id 를 빼고 나머지 컬럼에만 UPDATE 를 준다.
                st.execute("GRANT UPDATE (id, note_seq) ON railgate.%s TO '%s'@'%%'"
                        .formatted(TRAIN_SCHEDULE, LIMITED_USER));
                st.execute("FLUSH PRIVILEGES");
            }
        }

        @AfterAll
        static void dropLimitedUser() throws SQLException {
            try (Connection root = rootConnection(); Statement st = root.createStatement()) {
                st.execute("DROP USER IF EXISTS '%s'@'%%'".formatted(LIMITED_USER));
            }
        }

        private static Connection limitedConnection() throws SQLException {
            return DriverManager.getConnection(jdbcUrl(), LIMITED_USER, LIMITED_PASSWORD);
        }

        @Test
        void 제한된_계정은_소속_컬럼을_갱신할_수_없다() throws SQLException {
            try (Connection c = limitedConnection(); Statement st = c.createStatement()) {
                assertThatThrownBy(() -> st.executeUpdate(
                        "UPDATE %s SET sale_event_id = %d WHERE id = %d"
                                .formatted(TRAIN_SCHEDULE, SEOLLAL, SCHEDULE)))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("command denied");
            }

            assertThat(saleEventOf(SCHEDULE)).isEqualTo(CHUSEOK);
        }

        @Test
        void 제한된_계정도_다른_컬럼은_갱신할_수_있다() throws SQLException {
            try (Connection c = limitedConnection(); Statement st = c.createStatement()) {
                int affected = st.executeUpdate(
                        "UPDATE %s SET note_seq = note_seq + 1 WHERE id = %d"
                                .formatted(TRAIN_SCHEDULE, SCHEDULE));

                assertThat(affected).isEqualTo(1);
            }
        }

        /**
         * ★ 권한 회수도 만능이 아니다 — DELETE 후 재삽입은 남는다.
         *
         * <p>선점 경로가 운행편을 INSERT 하지 않는다면 INSERT/DELETE 권한도 뺄 수 있지만,
         * 그 판단은 저장소 계약이 정해진 뒤에야 가능하다 (Task 2G-E-B2).
         */
        @Test
        void 삽입_삭제_권한이_남아_있으면_우회할_수_있다() throws SQLException {
            try (Connection c = limitedConnection(); Statement st = c.createStatement()) {
                st.executeUpdate("DELETE FROM %s WHERE id = %d".formatted(TRAIN_SCHEDULE, SCHEDULE));
                st.executeUpdate("INSERT INTO %s (id, sale_event_id) VALUES (%d, %d)"
                        .formatted(TRAIN_SCHEDULE, SCHEDULE, SEOLLAL));
            }

            assertThat(saleEventOf(SCHEDULE)).isEqualTo(SEOLLAL);
        }
    }
}
