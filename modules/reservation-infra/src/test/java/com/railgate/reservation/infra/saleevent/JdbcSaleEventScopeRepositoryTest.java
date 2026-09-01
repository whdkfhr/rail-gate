package com.railgate.reservation.infra.saleevent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.railgate.reservation.infra.MySqlTestSupport;
import com.railgate.reservation.saleevent.SaleEventId;
import com.railgate.reservation.schedule.TrainSchedule;
import com.railgate.reservation.schedule.TrainScheduleId;
import com.railgate.reservation.seat.SeatId;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * 운행편 조회와 quota 범위 해석 (Task 2G-E-B2).
 *
 * <h2>정규화 조인을 운영 테이블에서 다시 확인한다</h2>
 *
 * <p>[2G-E-B1] §7 의 측정은 <b>테스트 전용 사본</b>에서 한 것이다. 운영 {@code seat_inventory} 는
 * 컬럼과 인덱스가 더 많아 행 크기와 계획이 달라질 수 있다 — B1 §13 이 남긴 미검증 항목이다.
 * 여기서 실제 테이블로 다시 잰다.
 *
 * <h2>재배정 API 를 제공하지 않는다</h2>
 *
 * <p>저장소 층의 방어다 (B1 §11). 도메인의 인스턴스 불변과 달리 <b>운영 경로를</b> 막는다.
 * 직접 SQL 은 여전히 막지 못한다.
 */
@Timeout(180)
@DisplayName("운행편 조회와 quota 범위 해석 (Task 2G-E-B2)")
class JdbcSaleEventScopeRepositoryTest extends MySqlTestSupport {

    private static final SaleEventId CHUSEOK = new SaleEventId(9001L);
    private static final SaleEventId SEOLLAL = new SaleEventId(9002L);

    private static final TrainScheduleId SCHEDULE_A = new TrainScheduleId(101L);
    private static final TrainScheduleId SCHEDULE_B = new TrainScheduleId(102L);
    private static final TrainScheduleId SCHEDULE_C = new TrainScheduleId(201L);

    private JdbcTrainScheduleRepository schedules;
    private JdbcSaleEventScopeRepository scope;

    @BeforeEach
    void setUp() {
        schedules = new JdbcTrainScheduleRepository(dataSource());
        scope = new JdbcSaleEventScopeRepository(dataSource());

        insertSaleEvent(CHUSEOK, "2026년 추석 승차권 판매");
        insertSaleEvent(SEOLLAL, "2027년 설 승차권 판매");
        // 추석 회차에 운행편 두 개, 설 회차에 하나.
        insertTrainSchedule(SCHEDULE_A, CHUSEOK);
        insertTrainSchedule(SCHEDULE_B, CHUSEOK);
        insertTrainSchedule(SCHEDULE_C, SEOLLAL);
    }

    private static void insertSaleEvent(SaleEventId id, String name) {
        jdbc().update("""
                INSERT INTO sale_event (id, name, opens_at, status)
                VALUES (?, ?, DATE_SUB(NOW(3), INTERVAL 1 DAY), 'OPEN')
                """, id.value(), name);
    }

    private static void insertTrainSchedule(TrainScheduleId id, SaleEventId saleEventId) {
        jdbc().update("INSERT INTO train_schedule (id, sale_event_id) VALUES (?, ?)",
                id.value(), saleEventId.value());
    }

    private static SeatId seat(TrainScheduleId scheduleId, String seatNo) {
        return new SeatId(insertAvailableSeat(scheduleId.value(), seatNo));
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("운행편 조회")
    class 운행편_조회 {

        @Test
        void 저장된_운행편을_복원한다() {
            Optional<TrainSchedule> found = schedules.findById(SCHEDULE_A);

            assertThat(found).isPresent();
            assertThat(found.orElseThrow().id()).isEqualTo(SCHEDULE_A);
            assertThat(found.orElseThrow().belongsTo(CHUSEOK)).isTrue();
        }

        @Test
        void 소속_판매_회차만_따로_조회할_수_있다() {
            assertThat(schedules.findSaleEventId(SCHEDULE_C)).contains(SEOLLAL);
        }

        @Test
        void 없는_운행편은_비어_있다() {
            assertThat(schedules.findById(new TrainScheduleId(404L))).isEmpty();
            assertThat(schedules.findSaleEventId(new TrainScheduleId(404L))).isEmpty();
        }

        /** ★ 저장소 층의 방어 — 재배정 통로를 열지 않는다. */
        @Test
        void 소속을_바꾸는_public_메서드가_없다() {
            List<String> mutators = Arrays.stream(JdbcTrainScheduleRepository.class.getDeclaredMethods())
                    .filter(m -> Modifier.isPublic(m.getModifiers()))
                    .filter(m -> !Modifier.isStatic(m.getModifiers()))
                    .filter(m -> Arrays.asList(m.getParameterTypes()).contains(SaleEventId.class))
                    .map(Method::getName)
                    .toList();

            assertThat(mutators)
                    .as("SaleEventId 를 입력으로 받는 저장소 메서드가 있으면 재배정 통로가 된다")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("quota 범위 해석")
    class 범위_해석 {

        @Test
        void 한_운행편의_좌석들은_그_회차로_해석된다() {
            List<SeatId> seats = List.of(
                    seat(SCHEDULE_A, "1A"), seat(SCHEDULE_A, "2A"),
                    seat(SCHEDULE_A, "3A"), seat(SCHEDULE_A, "4A"));

            assertThat(scope.resolve(seats)).isEqualTo(CHUSEOK);
        }

        /** ★ 2G-D §2 가 기각한 우회를 막는 지점. 운행편이 달라도 같은 회차면 합산된다. */
        @Test
        void 같은_회차의_다른_운행편끼리는_하나의_범위로_해석된다() {
            List<SeatId> seats = List.of(
                    seat(SCHEDULE_A, "1A"), seat(SCHEDULE_A, "2A"),
                    seat(SCHEDULE_B, "1A"), seat(SCHEDULE_B, "2A"));

            assertThat(scope.resolve(seats)).isEqualTo(CHUSEOK);
        }

        @Test
        void 좌석_하나도_해석된다() {
            assertThat(scope.resolve(List.of(seat(SCHEDULE_C, "1A")))).isEqualTo(SEOLLAL);
        }

        /** 회차를 넘나드는 요청은 거부한다. 이벤트 간 quota 는 독립이다 (2G-D §4). */
        @Test
        void 서로_다른_회차의_좌석이_섞이면_거부한다() {
            List<SeatId> seats = List.of(seat(SCHEDULE_A, "1A"), seat(SCHEDULE_C, "1A"));

            assertThatThrownBy(() -> scope.resolve(seats))
                    .isInstanceOf(SaleEventScopeException.class)
                    .hasMessageContaining("판매 회차");
        }

        /**
         * ★ 없는 좌석을 조용히 빼고 범위를 정하지 않는다.
         *
         * <p>무시하면 호출부는 "네 좌석의 범위" 를 받았다고 믿지만 실제로는 세 좌석의 범위다.
         * quota 계산이 요청과 다른 집합 위에서 이뤄진다.
         */
        @Test
        void 존재하지_않는_좌석이_섞이면_거부한다() {
            List<SeatId> seats = List.of(seat(SCHEDULE_A, "1A"), new SeatId(999_999L));

            assertThatThrownBy(() -> scope.resolve(seats))
                    .isInstanceOf(SaleEventScopeException.class)
                    .hasMessageContaining("존재하지 않는 좌석");
        }

        @Test
        void 모든_좌석이_없으면_거부한다() {
            assertThatThrownBy(() -> scope.resolve(List.of(new SeatId(999_998L), new SeatId(999_999L))))
                    .isInstanceOf(SaleEventScopeException.class);
        }

        /** 같은 좌석이 두 번 들어오면 IN 절이 합쳐져 좌석 수를 셀 수 없게 된다. */
        @Test
        void 같은_좌석이_두_번_들어오면_거부한다() {
            SeatId seatId = seat(SCHEDULE_A, "1A");

            assertThatThrownBy(() -> scope.resolve(List.of(seatId, seatId)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void 빈_목록은_거부한다() {
            assertThatThrownBy(() -> scope.resolve(List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void 상한을_넘는_좌석_수는_거부한다() {
            List<SeatId> seats = List.of(
                    seat(SCHEDULE_A, "1A"), seat(SCHEDULE_A, "2A"), seat(SCHEDULE_A, "3A"),
                    seat(SCHEDULE_A, "4A"), seat(SCHEDULE_A, "5A"));

            assertThatThrownBy(() -> scope.resolve(seats))
                    .as("P-2 상한을 넘는 요청은 IN 절을 만들기 전에 거부한다")
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void 인자를_검증한다() {
            assertThatThrownBy(() -> scope.resolve(null)).isInstanceOf(NullPointerException.class);
        }
    }

    /**
     * ★ 운영 테이블에서의 실행 계획 — B1 §13 이 남긴 미검증 항목.
     *
     * <p>B1 의 측정은 <b>테스트 전용 사본</b>에서, 그것도 픽스처가 {@code ANALYZE TABLE} 을
     * 돌린 직후에 한 것이다. 여기서 운영 {@code seat_inventory} 로, <b>통계를 갱신하지 않은</b>
     * 상태에서 다시 잰다. 운영에서는 통계가 항상 최신이라고 가정할 수 없기 때문이다.
     */
    @Nested
    @DisplayName("★ 운영 seat_inventory 에서의 실행 계획")
    class 실행_계획 {

        /**
         * 읽는 행 수의 상한.
         *
         * <p>4석 요청의 실측은 좌석 한 건당 운행편 한 건 + 임시 테이블 몇 행이라 7 안팎이다.
         * 옵티마이저가 계획을 조금 바꿔도 결론이 흔들리지 않도록 넉넉히 잡되,
         * <b>전체 좌석 수에 비례하는 계획은 반드시 걸리도록</b> 충분히 작게 둔다.
         */
        private static final long ROWS_READ_BOUND = 16L;

        private static final int SEATS_PER_SCHEDULE = 200;

        private QueryPlanProbe probe;

        @BeforeEach
        void setUpProbe() {
            probe = new QueryPlanProbe(dataSource());
        }

        /**
         * 좌석을 넉넉히 채운다. <b>{@code ANALYZE TABLE} 을 부르지 않는다</b> —
         * 통계가 최신이 아닌 상태가 이 검증의 조건이다.
         *
         * @return 운행편 A 의 앞쪽 좌석 4개. 실제로 존재하는 id 다
         */
        private List<SeatId> seedManySeats() {
            List<SeatId> firstFour = new java.util.ArrayList<>();
            for (TrainScheduleId scheduleId : List.of(SCHEDULE_A, SCHEDULE_B, SCHEDULE_C)) {
                for (int i = 1; i <= SEATS_PER_SCHEDULE; i++) {
                    long id = insertAvailableSeat(scheduleId.value(), i + "A");
                    if (scheduleId.equals(SCHEDULE_A) && i <= 4) {
                        firstFour.add(new SeatId(id));
                    }
                }
            }
            return firstFour;
        }

        private static Object[] params(List<SeatId> seats) {
            return seats.stream().map(seat -> (Object) seat.value()).toArray();
        }

        /**
         * ★ 조인 순서가 고정돼 있다.
         *
         * <p>좌석 PK 로 먼저 좁히고 운행편을 {@code eq_ref} 로 한 건씩 따라간다.
         * 이 순서가 뒤집히면 비용이 전체 좌석 수에 비례하게 된다.
         */
        @Test
        void 좌석_테이블이_먼저_구동되고_두_테이블_모두_PK_를_쓴다() {
            List<SeatId> seats = seedManySeats();

            List<QueryPlanProbe.PlanRow> plan = probe.explain(
                    JdbcSaleEventScopeRepository.resolveSql(seats.size()), params(seats));

            assertThat(plan).hasSize(2);
            assertThat(plan.get(0).table())
                    .as("구동 테이블은 좌석이어야 한다: %s", plan)
                    .isEqualTo("s");
            assertThat(plan.get(1).type())
                    .as("운행편은 좌석 한 건당 한 건씩 따라가야 한다: %s", plan)
                    .isEqualTo("eq_ref");
            assertThat(plan).allSatisfy(row -> {
                assertThat(row.isFullScan()).as("풀 스캔이 없어야 한다: %s", row).isFalse();
                assertThat(row.usesFilesort()).as("filesort 가 없어야 한다: %s", row).isFalse();
                assertThat(row.key()).as("PK 를 써야 한다: %s", row).isEqualTo("PRIMARY");
            });
        }

        @Test
        void 읽는_행_수가_요청_좌석_수에_갇혀_있다() {
            List<SeatId> seats = seedManySeats();

            long rowsRead = probe.rowsRead(
                    JdbcSaleEventScopeRepository.resolveSql(seats.size()), params(seats));

            System.out.printf("[Task 2G-E-B2] 운영 seat_inventory %,d행 (통계 미갱신) — 4석 해석 읽은 행 수 = %d%n",
                    jdbc().queryForObject("SELECT COUNT(*) FROM seat_inventory", Long.class), rowsRead);

            assertThat(rowsRead)
                    .as("전체 크기가 아니라 요청 좌석 수에 매여 있어야 한다")
                    .isLessThanOrEqualTo(ROWS_READ_BOUND);
        }

        /**
         * ★ 조인 순서를 고정하지 않으면 어떻게 되는가 (규칙 27).
         *
         * <p><b>이 테스트가 통과한다는 것은 고정하지 않은 형태가 더 많이 읽는 것을
         * 관측했다는 뜻이지, 그 형태가 쓸 만하다는 뜻이 아니다.</b>
         *
         * <p>실측(좌석 600행, 통계 미갱신): 고정하지 않으면 {@code train_schedule} 을 먼저 훑고
         * 운행편마다 {@code uk_seat_inventory_seat} 로 좌석을 따라 들어가 <b>520~609행</b>을 읽었다.
         * 고정하면 <b>5~7행</b>이다.
         *
         * <p>등호까지 허용하는 이유는 옵티마이저가 우연히 옳은 계획을 고를 수도 있기 때문이다.
         * 그 우연에 기대지 않는 것이 고정하는 이유다.
         */
        @Test
        void 조인_순서를_고정하지_않으면_더_많이_읽는다() {
            List<SeatId> seats = seedManySeats();
            Object[] parameters = params(seats);

            long pinned = probe.rowsRead(
                    JdbcSaleEventScopeRepository.resolveSql(seats.size()), parameters);
            long naive = probe.rowsRead(
                    JdbcSaleEventScopeRepository.naiveResolveSql(seats.size()), parameters);

            System.out.printf("[Task 2G-E-B2] 좌석 %,d행 (통계 미갱신) — 고정 %d행 / 미고정 %d행%n",
                    (long) SEATS_PER_SCHEDULE * 3, pinned, naive);

            assertThat(pinned).isLessThanOrEqualTo(ROWS_READ_BOUND);
            assertThat(pinned)
                    .as("고정한 쪽이 더 많이 읽는 일은 없어야 한다")
                    .isLessThanOrEqualTo(naive);
        }

        /** 좌석을 늘려도 고정된 계획의 비용은 늘지 않는다. */
        @Test
        void 좌석_수를_늘려도_읽는_행_수가_늘지_않는다() {
            List<SeatId> few = List.of(
                    new SeatId(insertAvailableSeat(SCHEDULE_A.value(), "1A")),
                    new SeatId(insertAvailableSeat(SCHEDULE_A.value(), "2A")),
                    new SeatId(insertAvailableSeat(SCHEDULE_A.value(), "3A")),
                    new SeatId(insertAvailableSeat(SCHEDULE_A.value(), "4A")));
            String sql = JdbcSaleEventScopeRepository.resolveSql(4);
            long small = probe.rowsRead(sql, params(few));

            for (int i = 5; i <= 600; i++) {
                insertAvailableSeat(SCHEDULE_A.value(), i + "A");
            }
            long large = probe.rowsRead(sql, params(few));

            assertThat(large)
                    .as("좌석 4행 → 600행 으로 늘려도 읽는 행 수가 늘면 안 된다")
                    .isLessThanOrEqualTo(small)
                    .isLessThanOrEqualTo(ROWS_READ_BOUND);
        }

        /** 테스트가 EXPLAIN 하는 SQL 과 저장소가 실행하는 SQL 이 같아야 한다. */
        @Test
        void 저장소가_실행하는_SQL_을_그대로_검증한다() {
            String sql = JdbcSaleEventScopeRepository.resolveSql(4);

            assertThat(sql)
                    .contains("seat_inventory")
                    .contains("train_schedule")
                    .contains("STRAIGHT_JOIN");
            assertThat(probe.explain(sql, 1L, 2L, 3L, 4L)).isNotEmpty();
        }

        /** 좌석 행에 회차를 복제하지 않는다는 결정을 스키마에서 확인한다. */
        @Test
        void seat_inventory_에_sale_event_id_컬럼이_없다() {
            List<Map<String, Object>> columns = jdbc().queryForList("""
                    SELECT column_name FROM information_schema.columns
                     WHERE table_schema = DATABASE() AND table_name = 'seat_inventory'
                       AND column_name = 'sale_event_id'
                    """);

            assertThat(columns).isEmpty();
        }
    }
}
