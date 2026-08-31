package com.railgate.reservation.infra.saleevent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.railgate.reservation.infra.MySqlTestSupport;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * ★ 실험 C — 기존 {@code schedule_id} 데이터를 깨지 않고 옮기는 순서 (Task 2G-E-B1).
 *
 * <h2>현재 사실</h2>
 *
 * <p>{@code seat_inventory.schedule_id} 는 <b>참조 대상이 없는 값</b>이다 (V1 주석).
 * FK 도 없다. 기존 테스트들은 임의의 {@code scheduleId}(101, 999 …)로 좌석을 넣는다.
 * 여기에 FK 를 바로 걸면 그 데이터가 전부 orphan 이 된다.
 *
 * <h2>무엇을 결정하는가</h2>
 *
 * <p>세 선택지 중 어느 순서로 마이그레이션할지다. <b>적용은 Task 2G-E-B2 로 남기고
 * 순서와 실패 정책만 여기서 정한다.</b>
 *
 * <h2>§3 은 "통과하면 좋은" 테스트가 아니다</h2>
 *
 * <p>§3 이 통과한다는 것은 <b>암묵 fallback 이 FK 를 통과시키면서 quota 범위를 오염시키는
 * 것을 관측했다</b>는 뜻이다 (CLAUDE.md 규칙 27). 채택한 절차는 §2 다.
 *
 * <h2>범위</h2>
 *
 * <p>운영 {@code seat_inventory} 를 ALTER 하지 않는다. V1/V2/V3 도 수정하지 않는다.
 * 여기서 쓰는 것은 같은 형태의 <b>테스트 전용 사본</b>이다.
 */
@Timeout(300)
@DisplayName("실험 C — migration 호환성과 backfill 순서 (Task 2G-E-B1)")
class SaleEventMigrationCompatibilityExperimentTest extends MySqlTestSupport {

    private static final String SALE_EVENT = "task2geb1_mig_sale_event";
    private static final String TRAIN_SCHEDULE = "task2geb1_mig_train_schedule";
    private static final String SEAT = "task2geb1_mig_seat";
    private static final String SEAT_FK = "fk_task2geb1_mig_seat_schedule";

    private static final long CHUSEOK = 9001L;
    private static final long SEOLLAL = 9002L;

    /** 운영자가 판매 회차에 명시적으로 배정한 운행편들. */
    private static final long[] MAPPED_SCHEDULES = {101L, 102L, 201L};
    /** 어디에도 배정되지 않은 채 좌석만 있는 운행편. 기존 테스트 픽스처가 만드는 형태다. */
    private static final long UNMAPPED_SCHEDULE = 999L;

    /**
     * orphan 탐지 — 참조 대상이 없는 {@code schedule_id}.
     *
     * <p>FK 를 걸기 전에 <b>반드시</b> 0행이어야 한다. 0행이 아닌데 진행하면
     * 두 가지 나쁜 선택 중 하나를 하게 된다: 조용히 지우거나, 아무 회차에나 붙이거나.
     */
    private static final String ORPHAN_DETECTION = """
            SELECT DISTINCT s.schedule_id
              FROM %s s
              LEFT JOIN %s ts ON ts.id = s.schedule_id
             WHERE ts.id IS NULL
             ORDER BY s.schedule_id
            """.formatted(SEAT, TRAIN_SCHEDULE);

    private static final String ADD_SEAT_FK = """
            ALTER TABLE %s
                ADD CONSTRAINT %s FOREIGN KEY (schedule_id) REFERENCES %s (id)
            """.formatted(SEAT, SEAT_FK, TRAIN_SCHEDULE);

    @BeforeEach
    void setUpSchema() {
        dropAll();

        jdbc().execute("""
                CREATE TABLE %s (
                    id   BIGINT       NOT NULL PRIMARY KEY,
                    name VARCHAR(100) NOT NULL
                ) ENGINE = InnoDB
                """.formatted(SALE_EVENT));

        jdbc().execute("""
                CREATE TABLE %s (
                    id            BIGINT NOT NULL PRIMARY KEY,
                    sale_event_id BIGINT NOT NULL,
                    KEY idx_%s_sale_event (sale_event_id),
                    CONSTRAINT fk_%s_sale_event FOREIGN KEY (sale_event_id) REFERENCES %s (id)
                ) ENGINE = InnoDB
                """.formatted(TRAIN_SCHEDULE, TRAIN_SCHEDULE, TRAIN_SCHEDULE, SALE_EVENT));

        // seat_inventory(V1) 와 같은 상태: schedule_id 는 있고 참조 대상은 없다.
        jdbc().execute("""
                CREATE TABLE %s (
                    id          BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    schedule_id BIGINT      NOT NULL,
                    seat_no     VARCHAR(10) NOT NULL,
                    UNIQUE KEY uk_%s_seat (schedule_id, seat_no),
                    KEY idx_%s_schedule (schedule_id)
                ) ENGINE = InnoDB
                """.formatted(SEAT, SEAT, SEAT));

        jdbc().update("INSERT INTO %s (id, name) VALUES (?, ?), (?, ?)".formatted(SALE_EVENT),
                CHUSEOK, "추석 판매", SEOLLAL, "설 판매");

        // 기존 데이터: 배정된 운행편들 + 배정되지 않은 운행편 하나.
        for (long scheduleId : MAPPED_SCHEDULES) {
            insertSeats(scheduleId);
        }
        insertSeats(UNMAPPED_SCHEDULE);
    }

    @AfterAll
    static void tearDown() {
        dropAll();
    }

    private static void dropAll() {
        jdbc().execute("DROP TABLE IF EXISTS " + SEAT);
        jdbc().execute("DROP TABLE IF EXISTS " + TRAIN_SCHEDULE);
        jdbc().execute("DROP TABLE IF EXISTS " + SALE_EVENT);
    }

    private static void insertSeats(long scheduleId) {
        for (int i = 1; i <= 4; i++) {
            jdbc().update("INSERT INTO %s (schedule_id, seat_no) VALUES (?, ?)".formatted(SEAT),
                    scheduleId, i + "A");
        }
    }

    /** 운영자가 명시적으로 등록한 매핑만 넣는다. 추측하지 않는다. */
    private static void registerExplicitMappings() {
        jdbc().update("INSERT INTO %s (id, sale_event_id) VALUES (?, ?), (?, ?), (?, ?)"
                        .formatted(TRAIN_SCHEDULE),
                MAPPED_SCHEDULES[0], CHUSEOK,
                MAPPED_SCHEDULES[1], CHUSEOK,
                MAPPED_SCHEDULES[2], SEOLLAL);
    }

    private static List<Long> orphans() {
        return jdbc().queryForList(ORPHAN_DETECTION, Long.class);
    }

    private static long seatCount() {
        return jdbc().queryForObject("SELECT COUNT(*) FROM " + SEAT, Long.class);
    }

    private static boolean seatForeignKeyExists() {
        return jdbc().queryForObject("""
                SELECT COUNT(*)
                  FROM information_schema.table_constraints
                 WHERE table_schema = DATABASE()
                   AND table_name = ?
                   AND constraint_name = ?
                   AND constraint_type = 'FOREIGN KEY'
                """, Long.class, SEAT, SEAT_FK) > 0;
    }

    // ------------------------------------------------------------------

    /**
     * §1 실패 재현 — 선택지 C(한 번에 처리)가 기존 데이터에서 실패한다.
     */
    @Nested
    @DisplayName("§1 ★ backfill 없이 FK 를 걸면 실패한다")
    class 선택지_C_한번에 {

        @Test
        void 매핑되지_않은_운행편이_있으면_FK_추가가_실패한다() {
            registerExplicitMappings();

            assertThat(orphans()).containsExactly(UNMAPPED_SCHEDULE);
            assertThatThrownBy(() -> jdbc().execute(ADD_SEAT_FK))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void 아무_매핑도_없으면_모든_운행편이_orphan_이다() {
            assertThat(orphans())
                    .as("참조 대상이 없는 schedule_id 는 전부 orphan 이다")
                    .containsExactly(MAPPED_SCHEDULES[0], MAPPED_SCHEDULES[1], MAPPED_SCHEDULES[2],
                            UNMAPPED_SCHEDULE);

            assertThatThrownBy(() -> jdbc().execute(ADD_SEAT_FK))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        /**
         * 실패해도 <b>데이터는 그대로다.</b> 재실행 가능해야 한다.
         *
         * <p>MySQL 의 {@code ADD CONSTRAINT} 는 원자적이다. 실패하면 제약도 붙지 않고
         * 행도 손상되지 않는다. 그래서 orphan 을 정리한 뒤 같은 마이그레이션을 다시 돌릴 수 있다.
         */
        @Test
        void FK_추가_실패는_데이터를_남긴다() {
            registerExplicitMappings();
            long before = seatCount();

            assertThatThrownBy(() -> jdbc().execute(ADD_SEAT_FK))
                    .isInstanceOf(DataIntegrityViolationException.class);

            assertThat(seatCount()).as("조용히 지우지 않는다").isEqualTo(before);
            assertThat(seatForeignKeyExists()).as("제약도 붙지 않는다").isFalse();
        }
    }

    /**
     * §2 채택 — 선택지 A(단계 분리).
     *
     * <pre>
     * 1. sale_event · train_schedule 생성
     * 2. 명시적 매핑만 backfill
     * 3. orphan 탐지 → 0행 확인
     * 4. 별도 마이그레이션에서 seat.schedule_id FK 추가
     * </pre>
     */
    @Nested
    @DisplayName("§2 선택지 A — 단계를 나누면 통과한다")
    class 선택지_A_단계_분리 {

        @Test
        void orphan_이_0행이_된_뒤에는_FK_추가가_성공한다() {
            registerExplicitMappings();
            // 남은 orphan 은 운영자가 명시적으로 처리한다. 추측하지 않는다.
            jdbc().update("INSERT INTO %s (id, sale_event_id) VALUES (?, ?)".formatted(TRAIN_SCHEDULE),
                    UNMAPPED_SCHEDULE, SEOLLAL);

            assertThat(orphans()).isEmpty();
            assertThatCode(() -> jdbc().execute(ADD_SEAT_FK)).doesNotThrowAnyException();
            assertThat(seatForeignKeyExists()).isTrue();
        }

        /** FK 가 붙은 뒤에는 미매핑 운행편의 좌석을 넣을 수 없다. 이것이 3단계의 목적이다. */
        @Test
        void FK_적용_후에는_미매핑_운행편_좌석을_넣을_수_없다() {
            registerExplicitMappings();
            jdbc().update("DELETE FROM %s WHERE schedule_id = ?".formatted(SEAT), UNMAPPED_SCHEDULE);
            jdbc().execute(ADD_SEAT_FK);

            assertThatThrownBy(() -> jdbc().update(
                    "INSERT INTO %s (schedule_id, seat_no) VALUES (?, ?)".formatted(SEAT),
                    UNMAPPED_SCHEDULE, "1A"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        /**
         * 좌석이 붙은 운행편은 지울 수 없다.
         *
         * <p>실험 B §2 가 남긴 "DELETE 후 재삽입" 우회를 <b>이 FK 가 좁힌다.</b>
         * 트리거가 막지 못하는 경로를 다른 층이 맡는 형태다.
         */
        @Test
        void FK_적용_후에는_좌석이_있는_운행편을_지울_수_없다() {
            registerExplicitMappings();
            jdbc().update("DELETE FROM %s WHERE schedule_id = ?".formatted(SEAT), UNMAPPED_SCHEDULE);
            jdbc().execute(ADD_SEAT_FK);

            assertThatThrownBy(() -> jdbc().update(
                    "DELETE FROM %s WHERE id = ?".formatted(TRAIN_SCHEDULE), MAPPED_SCHEDULES[0]))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    /**
     * §3 ★ 금지 사항의 실패 재현.
     *
     * <p>여기 테스트들이 통과한다는 것은 <b>금지된 지름길이 실제로 통과해버린다</b>는 뜻이다.
     * FK 는 만족되고 마이그레이션은 초록색이 되지만 quota 범위는 틀린다.
     */
    @Nested
    @DisplayName("§3 ★ 금지 — 암묵 fallback 과 값 대입")
    class 금지된_지름길 {

        /**
         * 미매핑 운행편을 기본 회차에 몰아넣으면 FK 는 통과한다.
         *
         * <p>그리고 그 운행편의 좌석은 <b>실제로는 아무 관계 없는 판매 회차의 quota</b> 에
         * 합산된다. 그 사용자는 추석 표를 잡은 적이 없는데 추석 회차 상한을 소진한다.
         * <b>탐지할 방법이 없다</b> — 데이터가 정합적으로 보이기 때문이다.
         */
        @Test
        void 기본_회차로_몰아넣으면_FK_는_통과하지만_quota_범위가_오염된다() {
            registerExplicitMappings();

            // ★ 하지 말아야 할 backfill.
            jdbc().update("""
                    INSERT INTO %s (id, sale_event_id)
                    SELECT DISTINCT s.schedule_id, ?
                      FROM %s s
                      LEFT JOIN %s ts ON ts.id = s.schedule_id
                     WHERE ts.id IS NULL
                    """.formatted(TRAIN_SCHEDULE, SEAT, TRAIN_SCHEDULE), CHUSEOK);

            assertThat(orphans()).isEmpty();
            assertThatCode(() -> jdbc().execute(ADD_SEAT_FK)).doesNotThrowAnyException();

            assertThat(jdbc().queryForObject(
                    "SELECT sale_event_id FROM %s WHERE id = ?".formatted(TRAIN_SCHEDULE),
                    Long.class, UNMAPPED_SCHEDULE))
                    .as("근거 없이 추석 회차에 귀속됐다. 마이그레이션은 성공한 것처럼 보인다")
                    .isEqualTo(CHUSEOK);
        }

        /**
         * {@code scheduleId} 를 {@code saleEventId} 로 그대로 대입하는 것도 통과해버린다.
         *
         * <p>2G-D §8 이 "암묵 대입 금지" 로 못 박은 것이다. 두 값은 서로 다른 식별자다.
         */
        @Test
        void scheduleId_를_saleEventId_로_대입하면_회차가_존재하지_않는다() {
            // 회차 id 를 운행편 id 와 같게 만들어 두면 FK 조차 통과한다.
            jdbc().update("INSERT INTO %s (id, name) SELECT DISTINCT schedule_id, '자동 생성' FROM %s"
                    .formatted(SALE_EVENT, SEAT));
            jdbc().update("INSERT INTO %s (id, sale_event_id) SELECT DISTINCT schedule_id, schedule_id FROM %s"
                    .formatted(TRAIN_SCHEDULE, SEAT));

            assertThat(orphans()).isEmpty();
            assertThatCode(() -> jdbc().execute(ADD_SEAT_FK)).doesNotThrowAnyException();

            assertThat(jdbc().queryForObject(
                    "SELECT COUNT(*) FROM %s".formatted(SALE_EVENT), Long.class))
                    .as("운행편 수만큼 판매 회차가 생겼다 — 2G-D §2 가 기각한 운행편 단위 범위와 같아진다")
                    .isEqualTo(2 + MAPPED_SCHEDULES.length + 1);
        }

        /** orphan 을 지우는 것도 통과한다. 좌석 데이터가 사라진 것을 아무도 모른다. */
        @Test
        void orphan_좌석을_지우면_FK_는_통과하지만_재고가_사라진다() {
            registerExplicitMappings();
            long before = seatCount();

            jdbc().update("""
                    DELETE s FROM %s s
                     LEFT JOIN %s ts ON ts.id = s.schedule_id
                     WHERE ts.id IS NULL
                    """.formatted(SEAT, TRAIN_SCHEDULE));

            assertThat(orphans()).isEmpty();
            assertThatCode(() -> jdbc().execute(ADD_SEAT_FK)).doesNotThrowAnyException();
            assertThat(seatCount())
                    .as("좌석 4행이 조용히 사라졌다")
                    .isEqualTo(before - 4);
        }
    }

    /**
     * §4 선택지 B — FK 를 연기하고 저장소에만 맡기는 경우.
     */
    @Nested
    @DisplayName("§4 선택지 B — FK 를 연기하면 새 orphan 이 계속 생긴다")
    class 선택지_B_연기 {

        @Test
        void FK_가_없으면_미매핑_운행편_좌석이_계속_들어온다() {
            registerExplicitMappings();
            jdbc().update("INSERT INTO %s (id, sale_event_id) VALUES (?, ?)".formatted(TRAIN_SCHEDULE),
                    UNMAPPED_SCHEDULE, SEOLLAL);
            assertThat(orphans()).isEmpty();

            // 저장소를 우회하는 경로(운영 스크립트, 다른 저장소, 배치)는 이것을 막지 못한다.
            jdbc().update("INSERT INTO %s (schedule_id, seat_no) VALUES (?, ?)".formatted(SEAT),
                    777L, "1A");

            assertThat(orphans())
                    .as("FK 가 없으면 정리한 뒤에도 새 orphan 이 생긴다")
                    .containsExactly(777L);
        }
    }
}
