package com.railgate.reservation.infra.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.infra.MySqlTestSupport;
import com.railgate.reservation.infra.seat.JdbcMultiSeatHoldRepository;
import com.railgate.reservation.infra.seat.ExpiryCandidate;
import com.railgate.reservation.infra.seat.JdbcSeatExpiryRepository;
import com.railgate.reservation.infra.seat.JdbcSeatHoldRepository;
import com.railgate.reservation.infra.seat.JdbcSeatReleaseRepository;
import com.railgate.reservation.seat.SeatId;
import com.railgate.reservation.seat.SeatUnavailableException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ★ I-12 (1인당 4석 상한) 경쟁 조건 실험 — Task 2G-A.
 *
 * <h2>이 테스트는 구현이 아니라 실험이다</h2>
 *
 * <p>운영 마이그레이션·저장소·서비스를 만들기 <b>전에</b> 다음 질문에 실측으로 답한다.
 * <ul>
 *   <li>집계({@code SELECT COUNT}) 방식은 왜 상한을 깨뜨리는가?</li>
 *   <li>카운터 행의 조건부 UPDATE 는 같은 사용자 요청을 어떻게 직렬화하는가?</li>
 *   <li>카운터 행이 아직 없을 때의 동시 생성 경쟁은 어떻게 원자화하는가?</li>
 *   <li>quota 증가와 좌석 선점은 어떤 트랜잭션으로 묶여야 하는가?</li>
 * </ul>
 *
 * <p>따라서 quota 구현은 <b>테스트 소스에만</b> 있고({@link Task2gQuotaCounter}),
 * 테이블도 {@code task2g_} 접두사를 쓴다.
 *
 * <h2>§1 은 "통과하면 좋은" 테스트가 아니다</h2>
 *
 * <p><b>§1 은 잘못된 구현의 결함을 재현한다.</b> 그 테스트가 통과한다는 것은
 * "6석이 만들어지는 것을 관측했다" 는 뜻이지 <b>올바른 동작이라는 뜻이 아니다</b>.
 * 올바른 계약은 §2 부터다 (CLAUDE.md 규칙 27).
 *
 * <p>동시성은 {@link CyclicBarrier}/{@link CountDownLatch} 로 순서를 고정한다.
 * {@code Thread.sleep} 이나 우연한 타이밍에 의존하지 않는다 (규칙 25).
 */
@Timeout(120)
@DisplayName("I-12 quota 경쟁 조건 실험 (Task 2G-A)")
class UserHoldQuotaRaceReproductionTest extends MySqlTestSupport {

    private static final long SCHEDULE_ID = 1L;
    /** 실험용 범위 키. event_id / schedule_id 결정은 보류했다 — 실험 문서 §9. */
    private static final long SCOPE_ID = 1L;
    private static final Duration HOLD_DURATION = Duration.ofMinutes(5);

    private static final UserId USER = new UserId(7L);
    private static final UserId OTHER_USER = new UserId(9L);
    private static final HoldId OTHER_HOLD = HoldId.of("11111111-2222-4333-8444-555555555555");

    private JdbcMultiSeatHoldRepository seatRepository;
    private JdbcSeatHoldRepository singleSeatRepository;
    private Task2gQuotaCounter quota;
    private List<Long> seatIds;

    @BeforeEach
    void setUp() {
        // DDL 은 암묵적 커밋을 일으키므로 반드시 트랜잭션 밖에서 실행한다.
        Task2gQuotaCounter.createTable(jdbc());
        Task2gQuotaCounter.truncate(jdbc());

        seatRepository = new JdbcMultiSeatHoldRepository(
                dataSource(), transactionManager(), HOLD_DURATION);
        singleSeatRepository = new JdbcSeatHoldRepository(dataSource(), HOLD_DURATION);
        quota = new Task2gQuotaCounter(dataSource());

        seatIds = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            seatIds.add(insertAvailableSeat(SCHEDULE_ID, i + "A"));
        }
    }

    private List<SeatId> seats(int... indexes) {
        List<SeatId> result = new ArrayList<>();
        for (int i : indexes) {
            result.add(new SeatId(seatIds.get(i)));
        }
        return result;
    }

    /** 이 사용자가 실제로 점유한 활성 좌석 수. quota 카운터와 대조하는 기준값이다. */
    private long activeSeatsOf(UserId user) {
        return jdbc().queryForObject("""
                SELECT COUNT(*) FROM seat_inventory
                 WHERE held_by = ? AND status IN ('HELD', 'PAYING')
                """, Long.class, user.value());
    }

    /** 두 작업자를 배리어로 같은 지점에 세운 뒤 함께 진행시킨다. */
    private record RaceResult(int succeeded, int rejected, List<String> errors) { }

    private RaceResult race(Runnable first, Runnable second) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<String> errors = new CopyOnWriteArrayList<>();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Runnable work : List.of(first, second)) {
                pool.submit(() -> {
                    try {
                        work.run();
                        succeeded.incrementAndGet();
                    } catch (QuotaRejected e) {
                        rejected.incrementAndGet();
                    } catch (Exception e) {
                        errors.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(done.await(90, TimeUnit.SECONDS)).as("두 작업자 종료").isTrue();
        }
        return new RaceResult(succeeded.get(), rejected.get(), errors);
    }

    /** quota 확보 실패를 나타내는 테스트 전용 신호. 운영 예외를 추가하지 않는다. */
    private static final class QuotaRejected extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (BrokenBarrierException | java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException(e);
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("1. ★ 실패 재현 — SELECT COUNT 후 판단하면 상한이 깨진다")
    class 집계_방식_실패_재현 {

        /**
         * <b>이 테스트가 통과하는 것은 결함을 관측했다는 뜻이다.</b>
         *
         * <p>같은 사용자의 3석 요청 두 개가 동시에 오면, 각 트랜잭션의 일관된 읽기는
         * <b>다른 트랜잭션의 미커밋 홀드를 보지 못한다.</b> 둘 다 "내 것 0석" 을 읽고
         * "0 + 3 ≤ 4" 로 통과한 뒤 각자 3석을 잡는다. 최종 6석 — I-12 위반이다.
         *
         * <p>좌석 집합이 서로 겹치지 않으므로 <b>좌석 행 잠금은 아무것도 막지 못한다.</b>
         * 좌석 수준의 정확성만으로는 사용자 수준 상한을 얻을 수 없다는 뜻이다.
         *
         * <p>배리어로 "둘 다 COUNT 를 읽은 뒤에 UPDATE 를 시작한다" 는 순서를 고정하므로
         * 실행할 때마다 같은 결과가 나온다. 확률에 기대지 않는다.
         */
        @Test
        void 집계_검사는_미커밋_홀드를_보지_못해_6석을_허용한다() throws Exception {
            CyclicBarrier bothRead = new CyclicBarrier(2);
            List<Long> observedCounts = new CopyOnWriteArrayList<>();

            // 각 작업자는 자신의 트랜잭션 안에서 COUNT 를 읽고, 배리어를 지난 뒤 선점한다.
            Runnable first = () -> naiveHold(seats(0, 1, 2), bothRead, observedCounts);
            Runnable second = () -> naiveHold(seats(3, 4, 5), bothRead, observedCounts);

            RaceResult result = race(first, second);

            assertThat(result.errors()).as("시스템 오류 없이 둘 다 성공해야 결함이 재현된다").isEmpty();
            assertThat(result.succeeded()).as("두 요청 모두 통과한다").isEqualTo(2);
            assertThat(observedCounts)
                    .as("둘 다 '내 것 0석' 을 봤다 — 서로의 미커밋 홀드가 보이지 않는다")
                    .containsExactly(0L, 0L);

            assertThat(activeSeatsOf(USER))
                    .as("★ I-12 위반: 상한 4석인데 6석을 점유했다")
                    .isEqualTo(6);
        }

        /** 잘못된 순서: COUNT 로 검사 → (그 사이가 열려 있다) → 선점. */
        private void naiveHold(List<SeatId> plan, CyclicBarrier bothRead, List<Long> observed) {
            TransactionTemplate tx = newTransactionTemplate();
            tx.executeWithoutResult(status -> {
                long current = activeSeatsOf(USER);
                observed.add(current);
                if (current + plan.size() > Task2gQuotaCounter.MAX_SEATS) {
                    throw new QuotaRejected();
                }
                // 두 트랜잭션이 모두 검사를 마친 뒤에 갱신을 시작하게 만든다.
                await(bothRead);
                seatRepository.holdAll(plan, HoldId.newId(), USER);
            });
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("2. 조건부 카운터 행 — 검사와 증가를 원자화")
    class 조건부_카운터_행 {

        /**
         * 이 절은 <b>카운터 행이 이미 있는</b> 경우를 다룬다. 행 자체의 생성 경쟁은 §3 이다.
         * 그래서 행은 트랜잭션 밖에서 미리 커밋해 둔다.
         */
        @BeforeEach
        void 카운터_행을_미리_만든다() {
            quota.ensureRow(USER.value(), SCOPE_ID);
        }

        /**
         * 올바른 순서: 조건부 증가 → 성공한 경우에만 좌석 선점.
         *
         * <p>배리어는 {@code tryAcquire} <b>직전</b>에 둔다. 그 뒤부터는 카운터 행의
         * 행 잠금이 두 트랜잭션을 직렬화한다 — 진 쪽은 이긴 쪽이 커밋할 때까지 대기했다가
         * 갱신된 값으로 조건을 다시 평가한다. 배리어를 잠금 획득 뒤에 두면 <b>테스트가</b>
         * 교착한다(잠금을 쥔 채 상대를 기다리게 되므로).
         */
        private void quotaGuardedHold(List<SeatId> plan, CyclicBarrier beforeAcquire) {
            TransactionTemplate tx = newTransactionTemplate();
            tx.executeWithoutResult(status -> {
                if (beforeAcquire != null) {
                    await(beforeAcquire);
                }
                if (!quota.tryAcquire(USER.value(), SCOPE_ID, plan.size())) {
                    throw new QuotaRejected();
                }
                seatRepository.holdAll(plan, HoldId.newId(), USER);
            });
        }

        @Test
        void 같은_사용자의_3석_요청_두_개_중_하나만_성공한다() throws Exception {
            CyclicBarrier beforeAcquire = new CyclicBarrier(2);

            RaceResult result = race(
                    () -> quotaGuardedHold(seats(0, 1, 2), beforeAcquire),
                    () -> quotaGuardedHold(seats(3, 4, 5), beforeAcquire));

            assertThat(result.errors()).as("시스템 오류 / deadlock / lock timeout").isEmpty();
            assertThat(result.succeeded()).as("quota 확보 성공").isEqualTo(1);
            assertThat(result.rejected()).as("상한 초과로 거부").isEqualTo(1);
        }

        @Test
        void 성공한_요청만_좌석을_선점해_최종_활성_좌석은_3석이다() throws Exception {
            CyclicBarrier beforeAcquire = new CyclicBarrier(2);

            race(() -> quotaGuardedHold(seats(0, 1, 2), beforeAcquire),
                    () -> quotaGuardedHold(seats(3, 4, 5), beforeAcquire));

            assertThat(activeSeatsOf(USER)).as("실제 점유 좌석").isEqualTo(3);
        }

        @Test
        void 카운터와_실제_좌석_수가_일치한다() throws Exception {
            CyclicBarrier beforeAcquire = new CyclicBarrier(2);

            race(() -> quotaGuardedHold(seats(0, 1, 2), beforeAcquire),
                    () -> quotaGuardedHold(seats(3, 4, 5), beforeAcquire));

            assertThat(quota.heldSeats(USER.value(), SCOPE_ID).longValue())
                    .as("카운터가 실제 활성 좌석 수와 어긋나면 상한 검사가 무의미해진다")
                    .isEqualTo(activeSeatsOf(USER));
        }

        @Test
        void 남은_자리에_정확히_들어맞는_요청은_성공한다() {
            assertThat(quota.tryAcquire(USER.value(), SCOPE_ID, 1)).isTrue();

            assertThat(quota.tryAcquire(USER.value(), SCOPE_ID, 3))
                    .as("1 + 3 = 4 는 상한과 같으므로 허용된다")
                    .isTrue();
            assertThat(quota.heldSeats(USER.value(), SCOPE_ID)).isEqualTo(4);
        }

        @Test
        void 상한에_도달하면_추가_요청은_거부된다() {
            assertThat(quota.tryAcquire(USER.value(), SCOPE_ID, 4)).isTrue();

            assertThat(quota.tryAcquire(USER.value(), SCOPE_ID, 1))
                    .as("4 + 1 > 4 이므로 affected_rows = 0")
                    .isFalse();
            assertThat(quota.heldSeats(USER.value(), SCOPE_ID)).as("증가하지 않았다").isEqualTo(4);
        }

        @Test
        void quota_거부시_좌석_SQL_은_실행되지_않는다() {
            quota.tryAcquire(USER.value(), SCOPE_ID, 4);

            assertThatThrownBy(() -> quotaGuardedHold(seats(0, 1, 2), null))
                    .isInstanceOf(QuotaRejected.class);

            for (int i : new int[] {0, 1, 2}) {
                long id = seatIds.get(i);
                assertThat(statusOf(id)).as("좌석 상태").isEqualTo("AVAILABLE");
                assertThat(holdIdOf(id)).isNull();
                assertThat(versionOf(id)).as("UPDATE 자체가 없었다").isZero();
            }
        }

        @Test
        void 서로_다른_사용자는_각자의_행에서_독립적으로_성공한다() throws Exception {
            CyclicBarrier beforeAcquire = new CyclicBarrier(2);

            quota.ensureRow(OTHER_USER.value(), SCOPE_ID);

            RaceResult result = race(
                    () -> quotaGuardedHoldFor(USER, seats(0, 1, 2), beforeAcquire),
                    () -> quotaGuardedHoldFor(OTHER_USER, seats(3, 4, 5), beforeAcquire));

            assertThat(result.errors()).isEmpty();
            assertThat(result.succeeded()).as("서로 다른 카운터 행이라 둘 다 성공").isEqualTo(2);
            assertThat(activeSeatsOf(USER)).isEqualTo(3);
            assertThat(activeSeatsOf(OTHER_USER)).isEqualTo(3);
        }

        private void quotaGuardedHoldFor(UserId user, List<SeatId> plan, CyclicBarrier barrier) {
            TransactionTemplate tx = newTransactionTemplate();
            tx.executeWithoutResult(status -> {
                await(barrier);
                if (!quota.tryAcquire(user.value(), SCOPE_ID, plan.size())) {
                    throw new QuotaRejected();
                }
                seatRepository.holdAll(plan, HoldId.newId(), user);
            });
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("3. 최초 카운터 행 생성 경쟁")
    class 최초_행_생성 {

        @Test
        void 동시에_첫_요청이_와도_행은_하나만_생긴다() throws Exception {
            CyclicBarrier beforeInsert = new CyclicBarrier(2);

            RaceResult result = race(
                    () -> firstRequest(seats(0, 1, 2), beforeInsert),
                    () -> firstRequest(seats(3, 4, 5), beforeInsert));

            assertThat(result.errors())
                    .as("중복 키 예외가 정상 흐름으로 새어나오면 안 된다")
                    .isEmpty();
            assertThat(quota.rowCount(USER.value(), SCOPE_ID)).as("카운터 행 수").isEqualTo(1);
        }

        @Test
        void 두_요청이_각각_3석이면_하나만_성공하고_최종은_3석이다() throws Exception {
            CyclicBarrier beforeInsert = new CyclicBarrier(2);

            RaceResult result = race(
                    () -> firstRequest(seats(0, 1, 2), beforeInsert),
                    () -> firstRequest(seats(3, 4, 5), beforeInsert));

            assertThat(result.succeeded()).isEqualTo(1);
            assertThat(result.rejected()).isEqualTo(1);
            assertThat(quota.heldSeats(USER.value(), SCOPE_ID)).isEqualTo(3);
            assertThat(activeSeatsOf(USER)).isEqualTo(3);
        }

        /** 행이 전혀 없는 상태에서 시작한다. INSERT ... ON DUPLICATE KEY UPDATE 로 원자 확보. */
        private void firstRequest(List<SeatId> plan, CyclicBarrier beforeInsert) {
            TransactionTemplate tx = newTransactionTemplate();
            tx.executeWithoutResult(status -> {
                await(beforeInsert);
                quota.ensureRow(USER.value(), SCOPE_ID);
                if (!quota.tryAcquire(USER.value(), SCOPE_ID, plan.size())) {
                    throw new QuotaRejected();
                }
                seatRepository.holdAll(plan, HoldId.newId(), USER);
            });
        }

        /**
         * {@code INSERT IGNORE} 가 무엇까지 삼키는지 실측한다.
         *
         * <p>중복 키만 무시하는 것이 아니라면 다른 데이터 오류도 조용히 사라진다.
         * 결과는 실험 문서에 기록한다.
         */
        @Test
        void INSERT_IGNORE_는_중복_키가_아닌_오류도_삼키는지_확인한다() {
            // CHECK 제약(0..4)을 위반하는 값을 IGNORE 로 넣어본다.
            int affected = jdbc().update(
                    "INSERT IGNORE INTO " + Task2gQuotaCounter.TABLE
                            + " (user_id, quota_scope_id, held_seats) VALUES (?, ?, ?)",
                    USER.value(), SCOPE_ID, 99);

            // 삽입되지 않았는데 예외도 나지 않는다면, 오류가 경고로 낮춰진 것이다.
            assertThat(affected).as("IGNORE 는 CHECK 위반 행을 건너뛴다").isZero();
            assertThat(quota.rowCount(USER.value(), SCOPE_ID))
                    .as("행이 만들어지지 않았지만 호출자는 실패를 알 수 없다")
                    .isZero();
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("4. ★ quota 와 좌석의 트랜잭션 원자성")
    class 트랜잭션_원자성 {

        @Test
        void 좌석_선점이_실패하면_quota_증가도_롤백된다() {
            // 카운터 행은 미리 커밋해 둔다. 그래야 롤백 대상이 '증가분' 임이 분명해진다.
            quota.ensureRow(USER.value(), SCOPE_ID);
            // 요청 좌석 중 하나를 다른 사용자가 먼저 잡는다 → holdAll 이 경합으로 실패한다.
            singleSeatRepository.hold(new SeatId(seatIds.get(1)), OTHER_HOLD, OTHER_USER);

            TransactionTemplate tx = newTransactionTemplate();

            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                assertThat(quota.tryAcquire(USER.value(), SCOPE_ID, 3))
                        .as("quota 확보는 성공한다").isTrue();
                seatRepository.holdAll(seats(0, 1, 2), HoldId.newId(), USER);
            }))
                    .as("좌석 경합은 예상 가능한 정상 실패다 (규칙 21)")
                    .isInstanceOf(SeatUnavailableException.class);

            assertThat(quota.heldSeats(USER.value(), SCOPE_ID))
                    .as("★ 예외가 최상위 경계 밖으로 나가 quota 증가도 함께 사라진다")
                    .isZero();
            assertThat(activeSeatsOf(USER)).as("요청자 좌석").isZero();
        }

        @Test
        void 예외를_트랜잭션_안에서_잡으면_quota_만_남는다() {
            // ★ 이것은 권장 방식이 아니라 위험의 재현이다.
            //   savepoint 는 좌석만 되돌린다. 앱 서비스가 경합 예외를 트랜잭션 안에서
            //   성공으로 삼키면 좌석 없이 카운터만 오른다.
            quota.ensureRow(USER.value(), SCOPE_ID);
            singleSeatRepository.hold(new SeatId(seatIds.get(1)), OTHER_HOLD, OTHER_USER);

            TransactionTemplate tx = newTransactionTemplate();
            tx.executeWithoutResult(status -> {
                quota.tryAcquire(USER.value(), SCOPE_ID, 3);
                try {
                    seatRepository.holdAll(seats(0, 1, 2), HoldId.newId(), USER);
                } catch (SeatUnavailableException swallowed) {
                    // 앱 서비스가 이렇게 하면 안 된다는 것을 보이는 것이 목적이다.
                }
            });

            assertThat(activeSeatsOf(USER)).as("좌석은 savepoint 로 되돌아갔다").isZero();
            assertThat(quota.heldSeats(USER.value(), SCOPE_ID))
                    .as("★ 위험: 좌석 없이 카운터만 3 으로 남았다")
                    .isEqualTo(3);
        }

        @Test
        void 성공하면_quota_와_좌석이_함께_커밋된다() {
            TransactionTemplate tx = newTransactionTemplate();
            tx.executeWithoutResult(status -> {
                quota.ensureRow(USER.value(), SCOPE_ID);
                quota.tryAcquire(USER.value(), SCOPE_ID, 3);
                seatRepository.holdAll(seats(0, 1, 2), HoldId.newId(), USER);
            });

            assertThat(quota.heldSeats(USER.value(), SCOPE_ID)).isEqualTo(3);
            assertThat(activeSeatsOf(USER)).isEqualTo(3);
        }

        @Test
        void 외부가_롤백하면_quota_와_좌석이_함께_사라진다() {
            quota.ensureRow(USER.value(), SCOPE_ID);

            TransactionTemplate tx = newTransactionTemplate();
            tx.executeWithoutResult(status -> {
                quota.tryAcquire(USER.value(), SCOPE_ID, 3);
                seatRepository.holdAll(seats(0, 1, 2), HoldId.newId(), USER);
                status.setRollbackOnly();
            });

            assertThat(quota.heldSeats(USER.value(), SCOPE_ID)).isZero();
            assertThat(activeSeatsOf(USER)).isZero();
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("5. 감소량 — 실제 변경 행 수 기준")
    class 감소량 {

        private final HoldId hold = HoldId.newId();
        private JdbcSeatExpiryRepository expiry;
        private JdbcSeatReleaseRepository release;

        /**
         * 공통 사전 상태: 같은 사용자가 <b>하나의 홀드로 3석</b>을 보유하고 quota 도 3.
         * 좌석과 quota 증가는 같은 최상위 트랜잭션에서 커밋한다.
         */
        @BeforeEach
        void 세_좌석을_보유한다() {
            expiry = new JdbcSeatExpiryRepository(dataSource());
            release = new JdbcSeatReleaseRepository(dataSource());

            inTransaction(() -> {
                quota.ensureRow(USER.value(), SCOPE_ID);
                quota.tryAcquire(USER.value(), SCOPE_ID, 3);
                seatRepository.holdAll(seats(0, 1, 2), hold, USER);
            });

            assertThat(activeSeatsOf(USER)).isEqualTo(3);
            assertThat(quota.heldSeats(USER.value(), SCOPE_ID)).isEqualTo(3);
        }

        /**
         * 선행 만료 경로. <b>좌석 상태를 직접 덮어쓰지 않는다.</b>
         *
         * <p>테스트 픽스처는 {@code expires_at} 을 과거로 미는 것까지만 하고, 회수는
         * 운영 저장소 {@link JdbcSeatExpiryRepository#expire} 가 수행한다. 그래야
         * {@code version} 증가와 홀드 필드 제거 같은 기존 계약을 우회하지 않는다.
         *
         * <p>회수와 quota 감소는 <b>같은 최상위 트랜잭션</b>에 둔다.
         */
        private void 한_좌석을_만료시킨다() {
            jdbc().update(
                    "UPDATE seat_inventory SET expires_at = NOW(3) - INTERVAL 1 MINUTE WHERE id = ?",
                    seatIds.get(0));

            int[] reclaimed = new int[1];
            inTransaction(() -> {
                List<ExpiryCandidate> candidates = expiry.findExpiredCandidates(10);
                reclaimed[0] = expiry.expire(candidates);
                // 실제 회수 수만큼만 줄인다.
                quota.release(USER.value(), SCOPE_ID, reclaimed[0]);
            });

            assertThat(reclaimed[0]).as("실제 회수된 좌석").isEqualTo(1);
            assertThat(statusOf(seatIds.get(0))).as("회수된 좌석").isEqualTo("AVAILABLE");
            assertThat(holdIdOf(seatIds.get(0))).as("홀드 정보 제거").isNull();
            assertThat(versionOf(seatIds.get(0))).as("만료 저장소가 version 을 올렸다").isEqualTo(2L);
            assertThat(activeSeatsOf(USER)).as("남은 활성 좌석").isEqualTo(2);
            assertThat(quota.heldSeats(USER.value(), SCOPE_ID)).as("quota").isEqualTo(2);
        }

        @Test
        void 실제_해제된_좌석_수만큼_감소하면_카운터와_좌석이_일치한다() {
            한_좌석을_만료시킨다();

            int[] released = new int[1];
            inTransaction(() -> {
                released[0] = release.releaseAll(hold, USER);
                // 해제와 quota 감소가 같은 최상위 트랜잭션에 있다.
                quota.release(USER.value(), SCOPE_ID, released[0]);
            });

            assertThat(released[0]).as("실제 해제된 좌석 수").isEqualTo(2);
            assertThat(activeSeatsOf(USER)).as("최종 활성 좌석").isZero();
            assertThat(quota.heldSeats(USER.value(), SCOPE_ID)).as("최종 quota").isZero();
        }

        /**
         * ★ <b>권장 동작이 아니라 결함 재현이다.</b>
         *
         * <p>선행 만료로 quota 는 이미 2 인데, 앱이 <b>원래 요청 좌석 수 3</b> 으로 줄이려 하면
         * 음수 방어 조건({@code held_seats - 3 >= 0}) 때문에 {@code affected_rows = 0} 으로 거부된다.
         * 그 반환값을 무시하고 좌석 해제를 커밋하면 <b>좌석 0석 / quota 2</b> 의 드리프트가 남는다.
         *
         * <p>사용자는 좌석을 하나도 갖고 있지 않은데 카운터상 2석을 쓴 것으로 집계된다.
         */
        @Test
        void 요청_좌석_수로_감소하고_실패를_무시하면_quota_드리프트가_남는다() {
            한_좌석을_만료시킨다();

            int[] released = new int[1];
            boolean[] decreased = new boolean[1];
            inTransaction(() -> {
                released[0] = release.releaseAll(hold, USER);
                // 잘못된 감소량: 실제 해제 수(2)가 아니라 원 요청 수(3).
                decreased[0] = quota.release(USER.value(), SCOPE_ID, 3);
                // 반환값을 확인하지 않고 그대로 커밋한다.
            });

            assertThat(released[0]).as("실제 해제된 좌석 수").isEqualTo(2);
            assertThat(decreased[0]).as("2 - 3 < 0 이므로 감소가 거부됐다").isFalse();

            assertThat(activeSeatsOf(USER)).as("좌석은 전부 풀렸다").isZero();
            assertThat(quota.heldSeats(USER.value(), SCOPE_ID))
                    .as("★ 드리프트: 좌석 0석인데 카운터는 2 로 남았다")
                    .isEqualTo(2);
        }

        @Test
        void 감소는_음수가_되지_않는다() {
            // 음수 방어가 없으면 카운터가 음수가 되어 이후 상한 검사가 무의미해진다.
            assertThat(quota.release(USER.value(), SCOPE_ID, 4))
                    .as("3 - 4 < 0 이므로 거부된다")
                    .isFalse();
            assertThat(quota.heldSeats(USER.value(), SCOPE_ID)).isEqualTo(3);
        }
    }
}
