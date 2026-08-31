package com.railgate.reservation.infra.saleevent;

import static com.railgate.reservation.infra.saleevent.Task2geb1Schema.LARGE_SCHEDULES;
import static com.railgate.reservation.infra.saleevent.Task2geb1Schema.LARGE_SEATS_PER_SCHEDULE;
import static com.railgate.reservation.infra.saleevent.Task2geb1Schema.MAX_SEATS_PER_REQUEST;
import static com.railgate.reservation.infra.saleevent.Task2geb1Schema.SMALL_SCHEDULES;
import static com.railgate.reservation.infra.saleevent.Task2geb1Schema.SMALL_SEATS_PER_SCHEDULE;
import static org.assertj.core.api.Assertions.assertThat;

import com.railgate.reservation.infra.MySqlTestSupport;
import com.railgate.reservation.infra.saleevent.QueryPlanProbe.PlanRow;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * ★ 실험 A — {@code scheduleId → saleEventId} 를 정규화 조인으로 얻을 것인가 (Task 2G-E-B1).
 *
 * <h2>결정해야 하는 것</h2>
 *
 * <p>2G-D §5 가 남긴 미결정 사항이다.
 *
 * <blockquote>
 * {@code seat_inventory} 에 {@code sale_event_id} 를 비정규화해 넣을지는 결정하지 않았다.
 * {@code train_schedule} 조인 한 번을 줄이는 대신 소속 변경 시 정합성 문제가 생긴다.
 * </blockquote>
 *
 * <p>비정규화는 <b>되돌리기 어렵다.</b> 컬럼을 넣고 나면 좌석 행과 운행편 행이 어긋나는
 * drift 를 계속 감시해야 하고, 그 감시 쿼리와 정정 절차가 영구히 따라붙는다.
 * 그래서 "조인이 하나 더 있다" 는 이유만으로 넣지 않는다.
 *
 * <h2>무엇을 근거로 판단하는가</h2>
 *
 * <p>벽시계 시간이 아니다 (규칙 30·31). 판단 근거는 세 가지다.
 *
 * <ol>
 *   <li>access type 과 선택된 인덱스 — {@code EXPLAIN}</li>
 *   <li><b>실제로 읽은 행 수</b> — {@code Handler_read_*} 세션 카운터</li>
 *   <li><b>전체 크기를 150배로 키웠을 때 그 값이 늘어나는가</b></li>
 * </ol>
 *
 * <p>정규화 경로의 비용이 요청 좌석 수(P-2: 최대 4)에 갇혀 있고 테이블이 커져도 늘지 않는다면,
 * 조인 하나는 받아들일 만하다.
 *
 * <h2>분포를 한쪽에 유리하게 두지 않는다</h2>
 *
 * <p>4석 요청을 <b>두 분포</b>로 잰다. 한 운행편 안의 4석(조인 대상이 한 행)과
 * 서로 다른 운행편의 4석(조인 대상이 네 행)이다. 앞의 분포만 재면 정규화 쪽에 유리하게 기운다.
 *
 * <h2>범위</h2>
 *
 * <p><b>실험이다.</b> 운영 마이그레이션·저장소를 만들지 않는다. 테이블은 {@code task2geb1_}
 * 접두사를 쓰는 테스트 전용 사본이며 운영 {@code seat_inventory} 를 ALTER 하지 않는다.
 */
@Timeout(300)
@DisplayName("실험 A — 판매 회차 조회: 정규화 대 비정규화 (Task 2G-E-B1)")
class SaleEventScopeLookupPlanTest extends MySqlTestSupport {

    /**
     * 요청 좌석 수에 갇힌 비용의 상한.
     *
     * <p>좌석 60,000행에서 이 값을 넘지 않는다면 비용은 <b>전체 크기가 아니라 요청 크기</b>에
     * 매인 것이다. 실측값(정규화 7, 비정규화 6)보다 넉넉하게 잡아, 옵티마이저가 계획을
     * 조금 바꿔도 결론이 흔들리지 않게 한다.
     */
    private static final long ROWS_READ_BOUND = 4L * MAX_SEATS_PER_REQUEST;

    private static QueryPlanProbe probe;

    @BeforeEach
    void setUpSchema() {
        Task2geb1Schema.create(jdbc());
        probe = new QueryPlanProbe(dataSource());
    }

    @AfterAll
    static void dropSchema() {
        Task2geb1Schema.drop(jdbc());
    }

    private static void seedSmall() {
        Task2geb1Schema.seed(jdbc(), SMALL_SCHEDULES, SMALL_SEATS_PER_SCHEDULE);
    }

    private static void seedLarge() {
        Task2geb1Schema.seed(jdbc(), LARGE_SCHEDULES, LARGE_SEATS_PER_SCHEDULE);
    }

    private static Object[] withinOneSchedule(int count, int seatsPerSchedule) {
        return Task2geb1Schema.seatIdsWithinOneSchedule(count, seatsPerSchedule);
    }

    private static Object[] acrossSchedules(int count, int seatsPerSchedule) {
        return Task2geb1Schema.seatIdsAcrossSchedules(count, seatsPerSchedule);
    }

    // ------------------------------------------------------------------

    /**
     * Q1. 요청이 {@code scheduleId} 를 이미 알고 있는 경우.
     *
     * <p>이 경로에서는 두 대안이 <b>완전히 같다.</b> 좌석 테이블을 거치지 않기 때문이다.
     * 비정규화의 이점은 여기에 없다 — 좌석에서 출발하는 경로에만 있다.
     */
    @Nested
    @DisplayName("Q1. scheduleId 를 아는 요청")
    class Q1_운행편을_아는_요청 {

        @Test
        void 운행편_PK_한_건으로_판매_회차를_얻는다() {
            seedLarge();

            List<PlanRow> plan = probe.explain(Task2geb1Schema.LOOKUP_BY_SCHEDULE_ID, 1L);

            assertThat(plan).hasSize(1);
            assertThat(plan.get(0).type())
                    .as("PK 상수 조회여야 한다: %s", plan)
                    .isIn("const", "eq_ref");
            assertThat(plan.get(0).key()).isEqualTo("PRIMARY");
            assertThat(plan.get(0).rowsEstimate()).isEqualTo(1);
        }

        @Test
        void 운행편_수가_늘어도_읽는_행_수가_늘지_않는다() {
            seedSmall();
            long small = probe.rowsRead(Task2geb1Schema.LOOKUP_BY_SCHEDULE_ID, 1L);

            seedLarge();
            long large = probe.rowsRead(Task2geb1Schema.LOOKUP_BY_SCHEDULE_ID, 1L);

            assertThat(large)
                    .as("운행편 %d개 → %d개 로 늘려도 PK 조회 비용은 늘지 않아야 한다",
                            SMALL_SCHEDULES, LARGE_SCHEDULES)
                    .isLessThanOrEqualTo(small)
                    .isLessThanOrEqualTo(ROWS_READ_BOUND);
        }
    }

    /**
     * Q2·Q3. 좌석 id 만 아는 요청.
     *
     * <p>여기가 실제 비교 지점이다. 선점 요청은 좌석 id 목록을 받으므로, quota 범위를
     * 알아내려면 좌석에서 판매 회차로 가야 한다.
     */
    @Nested
    @DisplayName("Q2·Q3. seatId 만 아는 요청")
    class Q2_좌석에서_출발하는_요청 {

        @ParameterizedTest(name = "{0}석 요청 — 정규화 조인은 두 테이블 모두 PK 를 쓴다")
        @ValueSource(ints = {1, MAX_SEATS_PER_REQUEST})
        void 정규화_조인은_풀스캔도_filesort도_하지_않는다(int seatCount) {
            seedLarge();
            String sql = Task2geb1Schema.normalizedLookupBySeat(seatCount);

            List<PlanRow> plan = probe.explain(sql, acrossSchedules(seatCount, LARGE_SEATS_PER_SCHEDULE));

            assertThat(plan)
                    .as("좌석 테이블과 운행편 테이블 두 줄이어야 한다: %s", plan)
                    .hasSize(2);
            assertThat(plan).allSatisfy(row -> {
                assertThat(row.isFullScan()).as("풀 스캔이 없어야 한다: %s", row).isFalse();
                assertThat(row.usesFilesort()).as("filesort 가 없어야 한다: %s", row).isFalse();
                assertThat(row.key()).as("PK 를 써야 한다: %s", row).isEqualTo("PRIMARY");
                assertThat(row.rowsEstimate())
                        .as("추정 행 수가 요청 좌석 수를 넘지 않아야 한다: %s", row)
                        .isLessThanOrEqualTo(seatCount);
            });
        }

        @ParameterizedTest(name = "{0}석 요청 — 비정규화 직접 조회도 PK 를 쓴다")
        @ValueSource(ints = {1, MAX_SEATS_PER_REQUEST})
        void 비정규화_조회도_풀스캔도_filesort도_하지_않는다(int seatCount) {
            seedLarge();
            String sql = Task2geb1Schema.denormalizedLookupBySeat(seatCount);

            List<PlanRow> plan = probe.explain(sql, acrossSchedules(seatCount, LARGE_SEATS_PER_SCHEDULE));

            assertThat(plan).hasSize(1);
            assertThat(plan.get(0).isFullScan()).isFalse();
            assertThat(plan.get(0).usesFilesort()).isFalse();
            assertThat(plan.get(0).key()).isEqualTo("PRIMARY");
        }

        /** 두 대안이 같은 답을 주지 않으면 비교 자체가 무의미하다. */
        @ParameterizedTest(name = "{0}석 요청 — 두 대안이 같은 SaleEventId 를 반환한다")
        @ValueSource(ints = {1, MAX_SEATS_PER_REQUEST})
        void 두_대안은_같은_판매_회차를_반환한다(int seatCount) {
            seedLarge();
            Object[] seatIds = withinOneSchedule(seatCount, LARGE_SEATS_PER_SCHEDULE);

            List<Long> normalized = jdbc().queryForList(
                    Task2geb1Schema.normalizedLookupBySeat(seatCount), Long.class, seatIds);
            List<Long> denormalized = jdbc().queryForList(
                    Task2geb1Schema.denormalizedLookupBySeat(seatCount), Long.class, seatIds);

            assertThat(normalized).hasSize(1).isEqualTo(denormalized);
        }

        /**
         * 회차를 넘나드는 요청은 <b>행 수로</b> 드러난다.
         *
         * <p>{@code DISTINCT} 결과가 1행이 아니면 교차 요청이다. 이벤트 간 quota 는
         * 완전히 독립이므로(2G-D §4) 그런 요청은 거부돼야 한다.
         * 두 대안 모두 같은 방식으로 그것을 드러낸다.
         */
        @Test
        void 여러_회차에_걸친_요청은_두_대안_모두_한_행을_넘긴다() {
            seedLarge();
            Object[] seatIds = acrossSchedules(MAX_SEATS_PER_REQUEST, LARGE_SEATS_PER_SCHEDULE);

            List<Long> normalized = jdbc().queryForList(
                    Task2geb1Schema.normalizedLookupBySeat(MAX_SEATS_PER_REQUEST), Long.class, seatIds);
            List<Long> denormalized = jdbc().queryForList(
                    Task2geb1Schema.denormalizedLookupBySeat(MAX_SEATS_PER_REQUEST), Long.class, seatIds);

            assertThat(normalized)
                    .as("운행편 1~4 는 서로 다른 판매 회차에 배정돼 있다")
                    .hasSizeGreaterThan(1)
                    .containsExactlyInAnyOrderElementsOf(denormalized);
        }
    }

    /**
     * ★ 결정의 핵심 — 전체 크기가 커져도 대상 스캔이 제한되는가.
     *
     * <p>비정규화를 정당화하려면 <b>정규화 경로가 데이터 증가에 따라 악화</b>되어야 한다.
     * 좌석 400 → 60,000 (150배) 에서 읽는 행 수가 늘지 않는다면 그 근거는 없다.
     */
    @Nested
    @DisplayName("★ 규모를 150배로 키워도 읽는 행 수가 늘지 않는가")
    class 규모_불변성 {

        @ParameterizedTest(name = "{0}석(같은 운행편) — 정규화: 좌석 400행 → 60,000행 에서 늘지 않는다")
        @ValueSource(ints = {1, MAX_SEATS_PER_REQUEST})
        void 정규화_경로는_전체_크기에_영향받지_않는다(int seatCount) {
            assertRowsReadDoesNotGrow(Task2geb1Schema::normalizedLookupBySeat, seatCount,
                    SaleEventScopeLookupPlanTest::withinOneSchedule);
        }

        @ParameterizedTest(name = "{0}석(다른 운행편) — 정규화: 좌석 400행 → 60,000행 에서 늘지 않는다")
        @ValueSource(ints = {MAX_SEATS_PER_REQUEST})
        void 정규화_경로는_조인_대상이_흩어져도_전체_크기에_영향받지_않는다(int seatCount) {
            assertRowsReadDoesNotGrow(Task2geb1Schema::normalizedLookupBySeat, seatCount,
                    SaleEventScopeLookupPlanTest::acrossSchedules);
        }

        @ParameterizedTest(name = "{0}석(같은 운행편) — 비정규화: 좌석 400행 → 60,000행 에서 늘지 않는다")
        @ValueSource(ints = {1, MAX_SEATS_PER_REQUEST})
        void 비정규화_경로도_전체_크기에_영향받지_않는다(int seatCount) {
            assertRowsReadDoesNotGrow(Task2geb1Schema::denormalizedLookupBySeat, seatCount,
                    SaleEventScopeLookupPlanTest::withinOneSchedule);
        }

        /**
         * 규모를 키웠을 때 읽는 행 수가 늘지 않고, 요청 좌석 수에 갇혀 있는지 확인한다.
         *
         * <p><b>두 규모의 값이 "같다" 고 단정하지 않는다.</b> 400행짜리 테이블에서는
         * 전체를 훑는 편이 실제로 더 싸서 옵티마이저가 그렇게 고를 수 있다.
         * 여기서 확인할 것은 <b>커졌을 때 나빠지지 않는가</b> 이지 작은 표의 계획이 아니다.
         */
        private void assertRowsReadDoesNotGrow(
                java.util.function.IntFunction<String> sqlFor,
                int seatCount,
                SeatIdsFactory seatIds) {

            seedSmall();
            long small = probe.rowsRead(sqlFor.apply(seatCount),
                    seatIds.create(seatCount, SMALL_SEATS_PER_SCHEDULE));

            seedLarge();
            long large = probe.rowsRead(sqlFor.apply(seatCount),
                    seatIds.create(seatCount, LARGE_SEATS_PER_SCHEDULE));

            assertThat(large)
                    .as("좌석 %,d행 → %,d행 (150배) 으로 늘려도 읽는 행 수가 늘면 안 된다",
                            SMALL_SCHEDULES * SMALL_SEATS_PER_SCHEDULE,
                            LARGE_SCHEDULES * LARGE_SEATS_PER_SCHEDULE)
                    .isLessThanOrEqualTo(small);
            assertThat(large)
                    .as("비용이 전체 크기가 아니라 요청 좌석 수에 매여 있어야 한다")
                    .isLessThanOrEqualTo(ROWS_READ_BOUND);
        }
    }

    @FunctionalInterface
    private interface SeatIdsFactory {
        Object[] create(int count, int seatsPerSchedule);
    }

    /**
     * 두 대안의 실측 차이.
     *
     * <p>정규화가 더 많이 읽는다는 사실을 <b>부정하지 않는다.</b> 확인할 것은 그 차이가
     * 전체 크기와 무관한 <b>작은 상수</b>인가다.
     */
    @Test
    void 두_대안의_차이는_전체_크기와_무관한_상수다() {
        seedLarge();

        long normalizedOne = probe.rowsRead(
                Task2geb1Schema.normalizedLookupBySeat(1),
                withinOneSchedule(1, LARGE_SEATS_PER_SCHEDULE));
        long denormalizedOne = probe.rowsRead(
                Task2geb1Schema.denormalizedLookupBySeat(1),
                withinOneSchedule(1, LARGE_SEATS_PER_SCHEDULE));
        long normalizedFourSame = probe.rowsRead(
                Task2geb1Schema.normalizedLookupBySeat(MAX_SEATS_PER_REQUEST),
                withinOneSchedule(MAX_SEATS_PER_REQUEST, LARGE_SEATS_PER_SCHEDULE));
        long normalizedFourSpread = probe.rowsRead(
                Task2geb1Schema.normalizedLookupBySeat(MAX_SEATS_PER_REQUEST),
                acrossSchedules(MAX_SEATS_PER_REQUEST, LARGE_SEATS_PER_SCHEDULE));
        long denormalizedFour = probe.rowsRead(
                Task2geb1Schema.denormalizedLookupBySeat(MAX_SEATS_PER_REQUEST),
                withinOneSchedule(MAX_SEATS_PER_REQUEST, LARGE_SEATS_PER_SCHEDULE));

        System.out.printf(
                "[Task 2G-E-B1] 읽은 행 수 (좌석 %,d행): 정규화 1석=%d 4석(같은 운행편)=%d "
                        + "4석(다른 운행편)=%d / 비정규화 1석=%d 4석=%d%n",
                LARGE_SCHEDULES * LARGE_SEATS_PER_SCHEDULE,
                normalizedOne, normalizedFourSame, normalizedFourSpread,
                denormalizedOne, denormalizedFour);

        assertThat(normalizedOne).isGreaterThan(denormalizedOne);
        assertThat(normalizedFourSame).isGreaterThan(denormalizedFour);
        assertThat(normalizedFourSame - denormalizedFour)
                .as("차이가 요청 좌석 수 규모의 상수여야 한다")
                .isLessThanOrEqualTo(MAX_SEATS_PER_REQUEST);
        assertThat(normalizedFourSpread)
                .as("조인 대상이 흩어져도 요청 좌석 수에 갇혀 있어야 한다")
                .isLessThanOrEqualTo(ROWS_READ_BOUND);
    }

    /**
     * 비정규화를 택했을 때 <b>추가로 생기는 것</b>.
     *
     * <p>좌석 행의 {@code sale_event_id} 와 운행편의 값이 어긋날 수 있다.
     * 조회 비용을 줄이는 대신 <b>이 검사와 정정 절차가 영구히 따라붙는다.</b>
     */
    @Nested
    @DisplayName("비정규화의 대가 — drift")
    class 비정규화_drift {

        @Test
        void 좌석_행과_운행편의_값은_어긋날_수_있다() {
            seedSmall();

            // 운행편의 소속만 바꾼다. 좌석 행은 그대로 남는다.
            jdbc().update("UPDATE %s SET sale_event_id = ? WHERE id = ?"
                    .formatted(Task2geb1Schema.TRAIN_SCHEDULE), Task2geb1Schema.saleEventId(3), 1L);

            List<Map<String, Object>> drifted = jdbc().queryForList(Task2geb1Schema.DRIFT_CHECK);

            assertThat(drifted)
                    .as("운행편 1 의 좌석 %d행이 운행편과 다른 판매 회차를 가리킨다",
                            SMALL_SEATS_PER_SCHEDULE)
                    .hasSize(SMALL_SEATS_PER_SCHEDULE);
        }

        @Test
        void 정규화_구조에는_어긋날_값_자체가_없다() {
            seedSmall();

            jdbc().update("UPDATE %s SET sale_event_id = ? WHERE id = ?"
                    .formatted(Task2geb1Schema.TRAIN_SCHEDULE), Task2geb1Schema.saleEventId(3), 1L);

            List<Long> resolved = jdbc().queryForList(
                    Task2geb1Schema.normalizedLookupBySeat(MAX_SEATS_PER_REQUEST), Long.class,
                    withinOneSchedule(MAX_SEATS_PER_REQUEST, SMALL_SEATS_PER_SCHEDULE));

            assertThat(resolved)
                    .as("진실의 원천이 하나이므로 조회는 항상 현재 값을 준다")
                    .containsExactly(Task2geb1Schema.saleEventId(3));
        }
    }
}
