package com.railgate.reservation.infra.quota;

import com.railgate.reservation.hold.SeatConfirmationOutcome;
import static org.assertj.core.api.Assertions.assertThat;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.infra.MySqlTestSupport;
import com.railgate.reservation.infra.quota.Task2gfExpirySweeper.TransactionMode;
import com.railgate.reservation.infra.seat.JdbcMultiSeatHoldRepository;
import com.railgate.reservation.infra.seat.JdbcSeatExpiryRepository;
import com.railgate.reservation.infra.seat.JdbcSeatPaymentRepository;
import com.railgate.reservation.seat.SeatId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * ★ 실험 D — 다중 스위퍼 (Task 2G-F).
 *
 * <h2>결정해야 하는 것</h2>
 *
 * <p>2G-C §10-7 — <b>단일 인스턴스를 강제(ShedLock)해서 문제를 없앨 것인가.</b>
 *
 * <p>운영 {@code JdbcSeatExpiryRepository} 는 "중복 실행은 정합성 문제가 아니라 효율 문제" 라고
 * 적었지만, 그것은 <b>좌석만</b> 볼 때의 이야기다. quota 감소가 붙으면 질문이 달라진다 —
 * <b>두 스위퍼가 같은 후보를 보고 각자 카운터를 줄이면 이중 감소가 되는가?</b>
 * 2G-C 가 미검증으로 남긴 항목이다.
 *
 * <h2>먼저 잡 락 없이 안전한지 확인한다</h2>
 *
 * <p>ShedLock 을 정확성의 <b>필수 조건</b>으로 두지 않는다. 잡 락 자체가 "그것이 죽으면
 * 스위퍼 전체가 멈춘다" 는 새로운 가용성 위험을 만들기 때문이다. 안전하지 않다면
 * 그 실패를 그대로 기록한다.
 *
 * <h2>동시성 테스트 규칙</h2>
 *
 * <p>{@code CountDownLatch} 로 동시 출발시키고({@code Thread.sleep} 없음, 규칙 25),
 * <b>모든 {@link Future} 의 결과나 예외를 회수</b>하며, 제한 시간 안의 종료를 단정한다.
 * 근거는 반복 횟수가 아니라 <b>최종 DB 상태의 구조적 불변식</b>이다.
 */
@Timeout(300)
@DisplayName("실험 D — 다중 스위퍼 quota 정산 (Task 2G-F)")
class MultiSweeperQuotaSettlementTest extends MySqlTestSupport {

    private static final Duration HOLD_DURATION = Duration.ofMinutes(5);
    private static final int TIMEOUT_SECONDS = 60;

    private static final long CHUSEOK = 9001L;
    private static final long SCHEDULE = 101L;
    private static final UserId USER_A = new UserId(11L);
    private static final UserId USER_B = new UserId(22L);

    private JdbcSeatExpiryRepository expiryRepository;
    private JdbcMultiSeatHoldRepository holdRepository;
    private JdbcSeatPaymentRepository paymentRepository;
    private Task2gQuotaCounter quota;

    @BeforeEach
    void setUp() {
        Task2gQuotaCounter.createTable(jdbc());
        Task2gQuotaCounter.truncate(jdbc());

        expiryRepository = new JdbcSeatExpiryRepository(dataSource());
        holdRepository = new JdbcMultiSeatHoldRepository(
                dataSource(), transactionManager(), HOLD_DURATION);
        paymentRepository = new JdbcSeatPaymentRepository(dataSource(), HOLD_DURATION);
        quota = new Task2gQuotaCounter(dataSource());

        jdbc().update("""
                INSERT INTO sale_event (id, name, opens_at, status)
                VALUES (?, '2G-F 실험 회차', DATE_SUB(NOW(3), INTERVAL 1 DAY), 'OPEN')
                """, CHUSEOK);
        jdbc().update("INSERT INTO train_schedule (id, sale_event_id) VALUES (?, ?)",
                SCHEDULE, CHUSEOK);
    }

    private List<SeatId> holdSeats(UserId user, int count, String prefix) {
        quota.ensureRow(user.value(), CHUSEOK);
        assertThat(quota.tryAcquire(user.value(), CHUSEOK, count)).isTrue();

        List<SeatId> seats = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            seats.add(new SeatId(insertAvailableSeat(SCHEDULE, prefix + i)));
        }
        inTransaction(() -> holdRepository.holdAll(seats, HoldId.newId(), user));
        return seats;
    }

    private static void expireNow(List<SeatId> seats) {
        seats.forEach(seat -> jdbc().update(
                "UPDATE seat_inventory SET expires_at = DATE_SUB(NOW(3), INTERVAL 1 MINUTE) WHERE id = ?",
                seat.value()));
    }

    private static long countByStatus(String status) {
        return jdbc().queryForObject(
                "SELECT COUNT(*) FROM seat_inventory WHERE status = ?", Long.class, status);
    }

    private static long activeSeatsOf(UserId user) {
        return jdbc().queryForObject("""
                SELECT COUNT(*) FROM seat_inventory
                 WHERE held_by = ? AND status IN ('HELD', 'PAYING')
                """, Long.class, user.value());
    }

    private Task2gfExpirySweeper sweeper() {
        return Task2gfExpirySweeper.of(
                dataSource(), expiryRepository, quota, TransactionMode.PER_USER_GROUP);
    }

    /**
     * 스위퍼 {@code count} 대를 <b>같은 후보를 본 상태에서</b> 동시에 정산시킨다.
     *
     * <h2>★ 왜 출발선 래치만으로는 부족한가</h2>
     *
     * <p>{@code sweep()} 호출 시각만 맞추면 <b>첫 스위퍼가 정산까지 끝낸 뒤</b> 나머지가
     * 빈 후보를 조회할 수 있다. 그래도 최종 quota 와 좌석 상태는 정상이라
     * <b>테스트는 통과하지만 아무것도 검증하지 못한다</b> — 경합이 일어나지 않았기 때문이다.
     *
     * <p>그래서 후보 조회 직후 훅에서 <b>두 단계 배리어</b>를 친다.
     *
     * <ol>
     *   <li>후보 조회 완료를 알리고({@code allCandidatesSelected}),
     *       <b>모든 스위퍼가 조회를 끝낼 때까지 기다린다</b> — 아무도 먼저 정산하지 못한다</li>
     *   <li>각 스위퍼의 시나리오 훅(확정 등)을 실행한다</li>
     *   <li>훅 완료를 알리고({@code allPostSelectHooksCompleted}), 모두 끝날 때까지 기다린다</li>
     * </ol>
     *
     * <p>이 배리어를 통과한 뒤에야 정산이 시작되므로, <b>모든 스위퍼가 같은 후보 집합을
     * 손에 들고 경합한다</b>는 것이 보장된다.
     *
     * <p>{@code await} 실패와 interrupt 를 조용히 넘기지 않는다 — 배리어가 깨진 채
     * 통과하면 그것이야말로 검증되지 않은 통과다.
     */
    private List<Task2gfSweepOutcome> raceSweepers(int count, IntFunction<Runnable> hookFor)
            throws Exception {
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch allCandidatesSelected = new CountDownLatch(count);
        CountDownLatch allPostSelectHooksCompleted = new CountDownLatch(count);
        List<Future<Task2gfSweepOutcome>> futures = new ArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            for (int i = 0; i < count; i++) {
                int sweeperIndex = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return sweeper().sweep(50, () -> {
                        // (1) 나는 후보를 읽었다. 모두가 읽을 때까지 아무도 정산하지 않는다.
                        allCandidatesSelected.countDown();
                        awaitBarrier(allCandidatesSelected, "모든 스위퍼의 후보 조회");

                        // (2) 이 스위퍼의 시나리오 훅 (확정 등).
                        hookFor.apply(sweeperIndex).run();

                        // (3) 훅이 모두 끝난 뒤에 정산을 시작한다.
                        allPostSelectHooksCompleted.countDown();
                        awaitBarrier(allPostSelectHooksCompleted, "모든 시나리오 훅");
                    });
                }));
            }
            assertThat(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("스위퍼 %d대가 모두 출발선에 서야 한다", count).isTrue();
            start.countDown();

            pool.shutdown();
            assertThat(pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("모든 스위퍼가 %d초 안에 끝나야 한다", TIMEOUT_SECONDS).isTrue();
        } finally {
            pool.shutdownNow();
        }

        List<Task2gfSweepOutcome> outcomes = new ArrayList<>();
        for (Future<Task2gfSweepOutcome> future : futures) {
            // 작업 안의 예외(데드락·배리어 실패 포함)는 여기서 ExecutionException 으로 터진다.
            outcomes.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        }
        assertThat(outcomes).hasSize(count).doesNotContainNull();
        return outcomes;
    }

    /**
     * 배리어를 기다린다. <b>시간 초과나 interrupt 를 삼키지 않는다.</b>
     *
     * <p>여기서 조용히 넘어가면 배리어가 없는 것과 같아지고, 테스트는 경합 없이 통과한다.
     */
    private static void awaitBarrier(CountDownLatch barrier, String what) {
        try {
            if (!barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "%s 를 %d초 안에 기다리지 못했다 — 경합이 성립하지 않았다"
                                .formatted(what, TIMEOUT_SECONDS));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(what + " 대기 중 인터럽트됐다", e);
        }
    }

    /** 모든 스위퍼가 <b>같은 후보 수</b>를 읽었는지 확인한다. 배리어가 실제로 동작했다는 증거다. */
    private static void assertAllSawSameCandidates(
            List<Task2gfSweepOutcome> outcomes, int expectedCandidates) {
        assertThat(outcomes)
                .as("배리어를 통과했다면 모든 스위퍼가 같은 후보 집합을 봤어야 한다")
                .allSatisfy(outcome -> assertThat(outcome.candidatesFound())
                        .as("스위퍼가 본 후보 수: %s", outcome)
                        .isEqualTo(expectedCandidates));
    }

    private List<Task2gfSweepOutcome> raceSweepers(int count) throws Exception {
        return raceSweepers(count, index -> () -> { });
    }

    // ------------------------------------------------------------------

    /**
     * §1 ★ 같은 후보를 본 두 스위퍼가 동시에 정산한다.
     *
     * <p>가장 중요한 시나리오다. 두 스위퍼가 <b>같은 만료 좌석 집합</b>을 후보로 읽은 뒤
     * 각자 quota 를 줄이려 한다.
     */
    @Nested
    @DisplayName("§1 ★ 같은 후보를 본 두 스위퍼")
    class 같은_후보_경합 {

        @Test
        void quota_가_이중_감소되지_않는다() throws Exception {
            List<SeatId> seats = holdSeats(USER_A, 4, "A");
            expireNow(seats);

            List<Task2gfSweepOutcome> outcomes = raceSweepers(2);

            assertAllSawSameCandidates(outcomes, 4);

            int totalReclaimed = outcomes.stream()
                    .mapToInt(Task2gfSweepOutcome::reclaimed).sum();
            assertThat(totalReclaimed)
                    .as("좌석 4석은 합쳐서 정확히 4번만 회수된다")
                    .isEqualTo(4);
            assertThat(quota.heldSeats(USER_A.value(), CHUSEOK))
                    .as("★ 4 에서 4 를 빼 0. 이중 감소면 음수가 되어 CHECK 에 걸린다")
                    .isZero();
        }

        @Test
        void 좌석은_최대_한_번만_AVAILABLE_로_전이한다() throws Exception {
            List<SeatId> seats = holdSeats(USER_A, 4, "A");
            expireNow(seats);
            List<Long> versionsBefore = versionsOf(seats);

            assertAllSawSameCandidates(raceSweepers(2), 4);

            List<Long> versionsAfter = versionsOf(seats);
            for (int i = 0; i < seats.size(); i++) {
                assertThat(versionsAfter.get(i) - versionsBefore.get(i))
                    .as("좌석 %d 의 version 이 정확히 1 만 올라야 한다", seats.get(i).value())
                    .isEqualTo(1);
            }
            assertThat(countByStatus("AVAILABLE")).isEqualTo(4);
        }

        @Test
        void quota_감소_합계가_실제_회수_행_수와_같다() throws Exception {
            List<SeatId> a = holdSeats(USER_A, 3, "A");
            List<SeatId> b = holdSeats(USER_B, 2, "B");
            expireNow(a);
            expireNow(b);

            List<Task2gfSweepOutcome> outcomes = raceSweepers(3);

            assertAllSawSameCandidates(outcomes, 5);

            int totalReclaimed = outcomes.stream()
                    .mapToInt(Task2gfSweepOutcome::reclaimed).sum();
            assertThat(totalReclaimed).isEqualTo(5);
            assertThat(quota.heldSeats(USER_A.value(), CHUSEOK)).isZero();
            assertThat(quota.heldSeats(USER_B.value(), CHUSEOK)).isZero();
        }

        /** ★ 최종 불변식 — 카운터와 실제 활성 좌석 수가 일치해야 한다. */
        @Test
        void 최종_quota_와_활성_좌석_수가_일치한다() throws Exception {
            List<SeatId> a = holdSeats(USER_A, 4, "A");
            List<SeatId> b = holdSeats(USER_B, 3, "B");
            // A 는 2석만 만료, B 는 전부 만료 → A 는 2석이 계속 활성이어야 한다.
            expireNow(a.subList(0, 2));
            expireNow(b);

            assertAllSawSameCandidates(raceSweepers(3), 5);

            assertThat(quota.heldSeats(USER_A.value(), CHUSEOK))
                    .as("A 의 카운터가 남은 활성 좌석 수와 같아야 한다")
                    .isEqualTo((int) activeSeatsOf(USER_A))
                    .isEqualTo(2);
            assertThat(quota.heldSeats(USER_B.value(), CHUSEOK))
                    .isEqualTo((int) activeSeatsOf(USER_B))
                    .isZero();
        }

        /** 실패한 스위퍼가 하나도 없어야 한다 — 경합은 정상 동작이지 오류가 아니다. */
        @Test
        void 경합해도_어떤_스위퍼도_실패하지_않는다() throws Exception {
            List<SeatId> seats = holdSeats(USER_A, 4, "A");
            expireNow(seats);

            List<Task2gfSweepOutcome> outcomes = raceSweepers(4);

            assertAllSawSameCandidates(outcomes, 4);
            assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.failed())
                    .as("데드락이나 drift 없이 끝나야 한다: %s", outcome)
                    .isEmpty());
        }
    }

    private static List<Long> versionsOf(List<SeatId> seats) {
        return seats.stream()
                .map(seat -> jdbc().queryForObject(
                        "SELECT version FROM seat_inventory WHERE id = ?", Long.class, seat.value()))
                .toList();
    }

    /**
     * §2 ★ I-11 — 다중 스위퍼와 확정의 3자 경쟁.
     */
    @Nested
    @DisplayName("§2 ★ 확정과의 경쟁")
    class 확정과의_경쟁 {

        @Test
        void 확정된_좌석은_어느_스위퍼도_회수하지_않는다() throws Exception {
            List<SeatId> seats = holdSeats(USER_A, 4, "A");
            SeatId sold = seats.get(0);
            String holdId = jdbc().queryForObject(
                    "SELECT hold_id FROM seat_inventory WHERE id = ?", String.class, sold.value());

            // 상태 전이를 우회하지 않는다 (2G-C 와 같은 방식).
            // startPayment 는 expires_at > NOW(3) 을 요구하므로 만료보다 먼저 해야 한다.
            assertThat(paymentRepository.startPayment(sold, HoldId.of(holdId)))
                    .isEqualTo(com.railgate.reservation.hold.SeatPaymentOutcome.STARTED);
            expireNow(seats);

            // 스위퍼 0 이 후보를 읽은 직후 그 좌석이 확정된다.
            // confirm 에는 expires_at 조건이 없다 — 만료된 PAYING 도 확정된다.
            IntFunction<Runnable> hook = index -> index != 0 ? () -> { } : () ->
                    assertThat(paymentRepository.confirm(sold, HoldId.of(holdId),
                            new com.railgate.reservation.ReservationId(777L)))
                            .isEqualTo(SeatConfirmationOutcome.CONFIRMED);

            List<Task2gfSweepOutcome> outcomes = raceSweepers(2, hook);

            // 배리어 덕분에 두 스위퍼 모두 확정 "전" 상태의 후보 4개를 읽었다.
            // 확정은 그 뒤 훅에서 일어나고, 정산은 확정이 끝난 뒤 시작된다.
            assertAllSawSameCandidates(outcomes, 4);

            assertThat(jdbc().queryForObject(
                    "SELECT status FROM seat_inventory WHERE id = ?", String.class, sold.value()))
                    .as("I-11: SOLD 는 상태 술어에서 배제된다")
                    .isEqualTo("SOLD");

            int totalReclaimed = outcomes.stream()
                    .mapToInt(Task2gfSweepOutcome::reclaimed).sum();
            assertThat(totalReclaimed).as("확정된 하나를 뺀 3석만 회수된다").isEqualTo(3);
            assertThat(quota.heldSeats(USER_A.value(), CHUSEOK))
                    .as("확정분은 만료 경로가 줄이지 않는다 — confirm 경로 quota 감소는 미구현이다")
                    .isEqualTo(1);
        }
    }

    /**
     * §3 잡 락 없이도 정합성이 유지되는가.
     *
     * <p>여기서 확인하는 것은 <b>정합성</b>이지 효율이 아니다. 중복 실행이 헛일을 만드는 것은
     * 그대로이며, 그 비용을 줄일지는 실제 중복 비율을 측정한 뒤 판단할 문제다.
     */
    @Nested
    @DisplayName("§3 잡 락 없는 다중 스위퍼")
    class 잡_락_없이 {

        @Test
        void 스위퍼_수를_늘려도_최종_상태가_같다() throws Exception {
            List<SeatId> seats = holdSeats(USER_A, 4, "A");
            expireNow(seats);

            List<Task2gfSweepOutcome> outcomes = raceSweepers(6);

            assertAllSawSameCandidates(outcomes, 4);
            assertThat(outcomes.stream().mapToInt(Task2gfSweepOutcome::reclaimed).sum())
                    .as("스위퍼가 6대여도 회수는 4석뿐이다")
                    .isEqualTo(4);
            assertThat(quota.heldSeats(USER_A.value(), CHUSEOK)).isZero();
            assertThat(countByStatus("HELD")).isZero();
        }

        /**
         * 중복 실행의 대가는 헛일이다 — 정합성이 아니라 효율 문제임을 관측한다.
         *
         * <p>대부분의 스위퍼는 후보를 읽고도 회수하지 못한다. 그것은 오류가 아니다.
         */
        @Test
        void 회수하지_못한_스위퍼도_오류가_아니다() throws Exception {
            List<SeatId> seats = holdSeats(USER_A, 4, "A");
            expireNow(seats);

            List<Task2gfSweepOutcome> outcomes = raceSweepers(4);

            assertAllSawSameCandidates(outcomes, 4);
            long winners = outcomes.stream().filter(o -> o.reclaimed() > 0).count();
            System.out.printf(
                    "[Task 2G-F] 다중 스위퍼 4대 — 회수한 스위퍼 %d대, 총 회수 %d석, 실패 %d건%n",
                    winners, outcomes.stream().mapToInt(Task2gfSweepOutcome::reclaimed).sum(),
                    outcomes.stream().mapToInt(o -> o.failed().size()).sum());

            assertThat(winners).isBetween(1L, 4L);
            assertThat(outcomes).allSatisfy(o -> assertThat(o.failed()).isEmpty());
        }
    }
}
