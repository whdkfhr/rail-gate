package com.railgate.reservation.infra.quota;

import static org.assertj.core.api.Assertions.assertThat;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.infra.MySqlTestSupport;
import com.railgate.reservation.infra.quota.Task2gfExpirySweeper.TransactionMode;
import com.railgate.reservation.infra.saleevent.QueryPlanProbe;
import com.railgate.reservation.infra.seat.JdbcMultiSeatHoldRepository;
import com.railgate.reservation.infra.seat.JdbcSeatExpiryRepository;
import com.railgate.reservation.seat.SeatId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * ★ §15 — 배치 전체 트랜잭션과 사용자별 트랜잭션의 비용 (Task 2G-F).
 *
 * <h2>2G-C 가 미측정으로 남긴 것</h2>
 *
 * <p>"SQL 수 증가 비용" 이다. 사용자별 트랜잭션은 그룹마다 트랜잭션과 quota 잠금이 늘어난다.
 * 그 증가가 <b>얼마나</b> 되는지 모른 채 대안을 고를 수 없다.
 *
 * <h2>무엇을 근거로 삼는가</h2>
 *
 * <p><b>SQL 호출 수와 트랜잭션 수</b>다. 이 값들은 하드웨어와 무관한 정수이고
 * assertion 으로 고정할 수 있다. 실행 시간은 CI 부하와 버퍼 풀 상태에 좌우되므로
 * <b>관측값으로 출력만 하고 단정하지 않는다</b> (CLAUDE.md 규칙 30·31).
 *
 * <h2>비용 공식</h2>
 *
 * <p>사용자 수를 {@code U}, 그룹 수를 {@code G}(= {@code U}) 라 하면
 *
 * <pre>
 *   배치 전체   후보SELECT 1 + quota잠금 G + 좌석UPDATE G + quota감소 G + TX 1
 *   사용자별    후보SELECT 1 + quota잠금 G + 좌석UPDATE G + quota감소 G + TX G
 * </pre>
 *
 * <p><b>SQL 수는 같다.</b> 늘어나는 것은 트랜잭션 수뿐이다. 이것이 이 측정의 핵심이며,
 * "사용자별 트랜잭션은 SQL 이 많아진다" 는 직관이 틀렸음을 보인다.
 */
@Timeout(300)
@DisplayName("§15 — 배치 전체 대 사용자별 트랜잭션 비용 (Task 2G-F)")
class ExpirySweeperCostMeasurementTest extends MySqlTestSupport {

    private static final Duration HOLD_DURATION = Duration.ofMinutes(5);
    private static final long CHUSEOK = 9001L;
    private static final long SCHEDULE = 101L;

    private JdbcSeatExpiryRepository expiryRepository;
    private JdbcMultiSeatHoldRepository holdRepository;
    private Task2gQuotaCounter quota;

    @BeforeEach
    void setUp() {
        Task2gQuotaCounter.createTable(jdbc());
        Task2gQuotaCounter.truncate(jdbc());

        expiryRepository = new JdbcSeatExpiryRepository(dataSource());
        holdRepository = new JdbcMultiSeatHoldRepository(
                dataSource(), transactionManager(), HOLD_DURATION);
        quota = new Task2gQuotaCounter(dataSource());

        jdbc().update("""
                INSERT INTO sale_event (id, name, opens_at, status)
                VALUES (?, '2G-F 실험 회차', DATE_SUB(NOW(3), INTERVAL 1 DAY), 'OPEN')
                """, CHUSEOK);
        jdbc().update("INSERT INTO train_schedule (id, sale_event_id) VALUES (?, ?)",
                SCHEDULE, CHUSEOK);
    }

    /**
     * 만료 후보를 만든다.
     *
     * <p>P-2 상한이 4석이므로 사용자당 후보는 4석을 넘지 않는다. 후보 100개를 만들려면
     * 사용자가 최소 25명 필요하다 — <b>이것이 배치 크기와 사용자 수의 실제 관계다.</b>
     */
    private void seedExpiredCandidates(int userCount, int seatsPerUser) {
        for (int u = 1; u <= userCount; u++) {
            UserId user = new UserId(1000L + u);
            quota.ensureRow(user.value(), CHUSEOK);
            assertThat(quota.tryAcquire(user.value(), CHUSEOK, seatsPerUser)).isTrue();

            List<SeatId> seats = new ArrayList<>();
            for (int i = 1; i <= seatsPerUser; i++) {
                seats.add(new SeatId(insertAvailableSeat(SCHEDULE, "u" + u + "s" + i)));
            }
            inTransaction(() -> holdRepository.holdAll(seats, HoldId.newId(), user));
        }
        jdbc().update(
                "UPDATE seat_inventory SET expires_at = DATE_SUB(NOW(3), INTERVAL 1 MINUTE) "
                        + "WHERE status IN ('HELD','PAYING')");
    }

    private Task2gfSweepOutcome sweep(TransactionMode mode, int batchSize) {
        return Task2gfExpirySweeper.of(dataSource(), expiryRepository, quota, mode)
                .sweep(batchSize);
    }

    private void reset() {
        jdbc().execute("DELETE FROM seat_inventory");
        Task2gQuotaCounter.truncate(jdbc());
    }

    /** 두 모드를 같은 분포에서 재고, 그 결과를 표로 남긴다. */
    private void compare(String label, int userCount, int seatsPerUser) {
        int candidates = userCount * seatsPerUser;

        seedExpiredCandidates(userCount, seatsPerUser);
        Task2gfSweepOutcome batchWide = sweep(TransactionMode.BATCH_WIDE, candidates);
        reset();

        seedExpiredCandidates(userCount, seatsPerUser);
        Task2gfSweepOutcome perGroup = sweep(TransactionMode.PER_USER_GROUP, candidates);
        reset();

        System.out.printf(
                "[Task 2G-F] %s (후보 %d / 사용자 %d, 사용자당 %.1f석)%n"
                        + "            배치전체 %s 회수=%d 실측 %dms%n"
                        + "            사용자별 %s 회수=%d 실측 %dms%n",
                label, candidates, userCount, (double) candidates / userCount,
                batchWide.sql(), batchWide.reclaimed(), batchWide.elapsed().toMillis(),
                perGroup.sql(), perGroup.reclaimed(), perGroup.elapsed().toMillis());

        // 구조적 값만 단정한다. 실행 시간은 출력만 한다.
        assertThat(batchWide.reclaimed()).isEqualTo(candidates);
        assertThat(perGroup.reclaimed()).isEqualTo(candidates);

        assertThat(perGroup.sql().candidateSelects()).isEqualTo(1);
        assertThat(batchWide.sql().candidateSelects()).isEqualTo(1);

        assertThat(perGroup.sql().quotaLockSelects()).isEqualTo(userCount);
        assertThat(perGroup.sql().seatUpdates()).isEqualTo(userCount);
        assertThat(perGroup.sql().quotaUpdates()).isEqualTo(userCount);

        assertThat(perGroup.sql().total())
                .as("★ SQL 호출 수는 두 대안이 같다. 늘어나는 것은 트랜잭션 수뿐이다")
                .isEqualTo(batchWide.sql().total());

        assertThat(batchWide.sql().transactions()).isEqualTo(1);
        assertThat(perGroup.sql().transactions()).isEqualTo(userCount);
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("§1 세 가지 분포")
    class 분포별_비용 {

        @Test
        void 후보_10개_사용자_1명() {
            // P-2 상한이 4석이라 한 사용자가 10석을 잡을 수 없다.
            // 사용자당 후보 수가 가장 큰 분포는 4석이다.
            compare("사용자 1명·4석", 1, 4);
        }

        @Test
        void 후보_40개_사용자_10명() {
            compare("사용자 10명·4석", 10, 4);
        }

        @Test
        void 후보_100개_사용자_50명() {
            compare("사용자 50명·2석", 50, 2);
        }
    }

    /**
     * §2 ★ 실패 그룹이 있을 때의 처리량.
     *
     * <p>비용 비교의 진짜 의미는 여기서 드러난다. <b>배치 전체 트랜잭션은 실패 그룹 하나에
     * 처리량이 0 이 된다.</b> SQL 수가 같아도 결과가 다르다.
     */
    @Nested
    @DisplayName("§2 ★ 실패 그룹이 있을 때의 처리량")
    class 실패_시_처리량 {

        @Test
        void 한_그룹이_실패하면_배치_전체는_0_사용자별은_나머지를_처리한다() {
            int userCount = 10;
            int seatsPerUser = 4;
            int candidates = userCount * seatsPerUser;

            seedExpiredCandidates(userCount, seatsPerUser);
            // 사용자 한 명의 카운터 행만 지운다 = drift.
            jdbc().update("DELETE FROM task2g_user_hold_quota WHERE user_id = ?", 1005L);
            Task2gfSweepOutcome batchWide = sweep(TransactionMode.BATCH_WIDE, candidates);
            reset();

            seedExpiredCandidates(userCount, seatsPerUser);
            jdbc().update("DELETE FROM task2g_user_hold_quota WHERE user_id = ?", 1005L);
            Task2gfSweepOutcome perGroup = sweep(TransactionMode.PER_USER_GROUP, candidates);
            reset();

            System.out.printf(
                    "[Task 2G-F] 실패 그룹 1개 (사용자 %d명·후보 %d) — "
                            + "배치전체 회수=%d 성공그룹=%d / 사용자별 회수=%d 성공그룹=%d%n",
                    userCount, candidates,
                    batchWide.reclaimed(), batchWide.succeeded().size(),
                    perGroup.reclaimed(), perGroup.succeeded().size());

            assertThat(batchWide.reclaimed())
                    .as("★ 사용자 한 명의 drift 가 배치 전체를 멈춘다")
                    .isZero();
            assertThat(batchWide.succeeded()).isEmpty();
            assertThat(batchWide.failed()).hasSize(userCount);

            assertThat(perGroup.reclaimed())
                    .as("실패 그룹의 4석만 남고 나머지 %d석은 회수된다", candidates - seatsPerUser)
                    .isEqualTo(candidates - seatsPerUser);
            assertThat(perGroup.succeeded()).hasSize(userCount - 1);
            assertThat(perGroup.failed()).hasSize(1);
        }
    }

    /**
     * §3 ★ 후보 조회의 실행 계획.
     *
     * <p>운영 {@code JdbcSeatExpiryRepository} 는 후보 조회에 {@code FORCE INDEX} 를 걸고
     * {@code SeatExpiryCandidateFairnessTest} 가 그것을 EXPLAIN 으로 강제한다.
     * <b>2G-F 의 후보 조회는 거기에 {@code train_schedule} 조인이 더 붙는다</b> —
     * 옵티마이저의 선택지가 늘었으므로 같은 검증이 더 필요해졌다.
     *
     * <p>테스트가 <b>스위퍼가 실행하는 SQL 상수를 그대로</b> EXPLAIN 한다. 복사본을 두면
     * 실제 SQL 이 바뀌어도 이 검증이 눈치채지 못한다.
     */
    @Nested
    @DisplayName("§3 ★ 후보 조회 실행 계획")
    class 실행_계획 {

        private QueryPlanProbe probe;

        @BeforeEach
        void setUpProbe() {
            probe = new QueryPlanProbe(dataSource());
        }

        private List<QueryPlanProbe.PlanRow> explainCandidates(int batchSize) {
            return probe.explain(Task2gfExpirySweeper.FIND_CANDIDATES_SQL, batchSize);
        }

        @Test
        void 좌석_테이블이_구동_테이블이고_만료_후보_인덱스를_쓴다() {
            seedExpiredCandidates(20, 4);

            List<QueryPlanProbe.PlanRow> plan = explainCandidates(50);

            assertThat(plan).hasSize(2);
            assertThat(plan.get(0).table())
                    .as("좌석이 먼저 구동돼야 한다: %s", plan)
                    .isEqualTo("s");
            assertThat(plan.get(0).key())
                    .as("운영 저장소와 같은 인덱스를 강제한다: %s", plan)
                    .isEqualTo(Task2gfExpirySweeper.CANDIDATE_INDEX);
        }

        @Test
        void 운행편_조인은_PK_기반이다() {
            seedExpiredCandidates(20, 4);

            List<QueryPlanProbe.PlanRow> plan = explainCandidates(50);

            assertThat(plan.get(1).table()).isEqualTo("ts");
            assertThat(plan.get(1).type())
                    .as("좌석 한 건당 운행편 한 건: %s", plan)
                    .isEqualTo("eq_ref");
            assertThat(plan.get(1).key()).isEqualTo("PRIMARY");
        }

        @Test
        void 풀_스캔이_없다() {
            seedExpiredCandidates(20, 4);

            assertThat(explainCandidates(50)).allSatisfy(row ->
                    assertThat(row.isFullScan()).as("풀 스캔이 없어야 한다: %s", row).isFalse());
        }

        /**
         * ★ filesort 가 없어야 {@code LIMIT} 이 조기 종료한다.
         *
         * <p>정렬이 인덱스로 해결되지 않으면 만료 후보 <b>전부</b>를 읽어 정렬한 뒤 잘라낸다.
         * 그러면 배치 비용이 배치 크기가 아니라 <b>backlog 크기</b>에 비례한다 (V2 주석).
         */
        @Test
        void ORDER_BY_때문에_filesort_가_발생하지_않는다() {
            seedExpiredCandidates(20, 4);

            assertThat(explainCandidates(50)).allSatisfy(row ->
                    assertThat(row.usesFilesort())
                            .as("정렬이 인덱스로 해결돼야 LIMIT 이 조기 종료한다: %s", row)
                            .isFalse());
        }

        /**
         * ★ 규모를 키워도 읽는 행 수가 전체 좌석 수에 비례하지 않는다.
         *
         * <p>{@code LIMIT} 이 실제로 조기 종료하는지를 <b>실측</b>으로 확인한다.
         * 만료 후보를 5배로 늘려도 배치 크기가 같으면 읽는 행 수가 그대로여야 한다.
         */
        @Test
        void 만료_backlog_가_늘어도_읽는_행_수가_늘지_않는다() {
            int batchSize = 20;

            seedExpiredCandidates(10, 4);
            long small = probe.rowsRead(Task2gfExpirySweeper.FIND_CANDIDATES_SQL, batchSize);
            long smallTotal = jdbc().queryForObject(
                    "SELECT COUNT(*) FROM seat_inventory", Long.class);
            reset();

            seedExpiredCandidates(50, 4);
            long large = probe.rowsRead(Task2gfExpirySweeper.FIND_CANDIDATES_SQL, batchSize);
            long largeTotal = jdbc().queryForObject(
                    "SELECT COUNT(*) FROM seat_inventory", Long.class);
            reset();

            System.out.printf(
                    "[Task 2G-F] 후보 조회 읽은 행 수 — 좌석 %d행에서 %d, %d행에서 %d (배치 %d)%n",
                    smallTotal, small, largeTotal, large, batchSize);

            assertThat(largeTotal).as("전체 좌석이 5배로 늘었다").isEqualTo(smallTotal * 5);
            assertThat(large)
                    .as("배치 크기가 같으므로 읽는 행 수가 늘면 안 된다")
                    .isLessThanOrEqualTo(small);
            assertThat(large)
                    .as("읽는 행 수가 배치 크기 규모에 갇혀 있어야 한다")
                    .isLessThanOrEqualTo(batchSize * 4L);
        }

        /** 테스트가 EXPLAIN 하는 SQL 과 스위퍼가 실행하는 SQL 이 같아야 한다. */
        @Test
        void 스위퍼가_실행하는_SQL_을_그대로_검증한다() {
            assertThat(Task2gfExpirySweeper.FIND_CANDIDATES_SQL)
                    .contains("FORCE INDEX (" + Task2gfExpirySweeper.CANDIDATE_INDEX + ")")
                    .contains("STRAIGHT_JOIN train_schedule")
                    .as("손상 후보 탐지를 위해 held_by 를 거르지 않는 계약은 유지한다")
                    .doesNotContain("held_by IS NOT NULL");
        }
    }

    /**
     * §4 배치 크기와 사용자 수의 관계.
     *
     * <p>P-2 상한(4석) 때문에 <b>사용자당 후보 수는 4를 넘을 수 없다.</b>
     * 따라서 후보 {@code N} 개인 배치의 그룹 수는 최소 {@code ceil(N/4)} 다 —
     * 트랜잭션 수의 하한이 배치 크기로 정해진다.
     */
    @Test
    void 사용자당_후보_수는_P2_상한을_넘지_않는다() {
        seedExpiredCandidates(10, 4);

        Task2gfSweepOutcome outcome = sweep(TransactionMode.PER_USER_GROUP, 100);

        assertThat(outcome.succeeded().values())
                .as("한 사용자의 후보가 4석을 넘을 수 없다 (P-2)")
                .allSatisfy(count -> assertThat(count).isLessThanOrEqualTo(4));
        assertThat(outcome.sql().transactions())
                .as("후보 40개 → 그룹 최소 10개 → 트랜잭션 10개")
                .isEqualTo(10);
    }
}
