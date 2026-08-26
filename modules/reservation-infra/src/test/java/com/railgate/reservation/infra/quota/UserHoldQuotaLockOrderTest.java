package com.railgate.reservation.infra.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.infra.MySqlTestSupport;
import com.railgate.reservation.infra.seat.JdbcMultiSeatHoldRepository;
import com.railgate.reservation.infra.seat.JdbcSeatReleaseRepository;
import com.railgate.reservation.seat.SeatId;
import com.railgate.reservation.seat.SeatUnavailableException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ★ quota 행과 좌석 행의 <b>잠금 순서</b> 실험 — Task 2G-B.
 *
 * <h2>Task 2G-A 가 남긴 가설을 검증한다</h2>
 *
 * <p>2G-A 는 다음을 <b>구조적으로 가능해 보인다</b> 고만 기록하고 재현하지 않았다.
 *
 * <pre>
 *   선점 경로 : quota 행 → seat_inventory
 *   감소 경로 : seat_inventory → quota 행      ← 순서가 반대다
 * </pre>
 *
 * <p>이 테스트는 그 사이클을 <b>결정적으로 재현</b>하고, 모든 경로를
 * {@code quota → seat} 로 통일했을 때 같은 경합이 데드락 없이 끝나는지 확인한다.
 *
 * <h2>§1 은 "통과하면 좋은" 테스트가 아니다</h2>
 *
 * <p><b>§1 이 통과한다는 것은 잘못된 순서에서 데드락을 관측했다는 뜻</b>이지
 * 올바른 동작이라는 뜻이 아니다 (CLAUDE.md 규칙 27). 올바른 계약은 §2 부터다.
 *
 * <h2>이 Task 에서 하지 않은 것</h2>
 *
 * <p>운영 저장소를 하나도 바꾸지 않았다. 통일 순서는 <b>테스트가 애플리케이션 서비스 역할을
 * 흉내내어</b> 구성한 후보 흐름이다. 만료 스위퍼의 사용자별 집계는 Task 2G-C 로 남긴다.
 */
@Timeout(180)
@DisplayName("quota ↔ seat 잠금 순서 실험 (Task 2G-B)")
class UserHoldQuotaLockOrderTest extends MySqlTestSupport {

    private static final long SCHEDULE_ID = 1L;
    /** 범위 키는 아직 미결정이다 (2G-A §9). 실험은 중립 이름을 쓴다. */
    private static final long SCOPE_ID = 1L;
    private static final Duration HOLD_DURATION = Duration.ofMinutes(5);

    private static final UserId USER = new UserId(7L);

    private JdbcMultiSeatHoldRepository holdRepository;
    private JdbcSeatReleaseRepository releaseRepository;
    private Task2gQuotaCounter quota;
    private List<Long> seatIds;
    private HoldId currentHold;

    @BeforeEach
    void setUp() {
        // DDL 은 암묵적 커밋을 일으키므로 트랜잭션 밖에서 실행한다.
        Task2gQuotaCounter.createTable(jdbc());
        Task2gQuotaCounter.truncate(jdbc());

        holdRepository = new JdbcMultiSeatHoldRepository(
                dataSource(), transactionManager(), HOLD_DURATION);
        releaseRepository = new JdbcSeatReleaseRepository(dataSource());
        quota = new Task2gQuotaCounter(dataSource());

        seatIds = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            seatIds.add(insertAvailableSeat(SCHEDULE_ID, i + "A"));
        }

        // 사용자가 2석을 보유한 상태에서 시작한다. quota 와 실제 좌석 수가 일치한다.
        currentHold = HoldId.newId();
        inTransaction(() -> {
            quota.ensureRow(USER.value(), SCOPE_ID);
            quota.tryAcquire(USER.value(), SCOPE_ID, 2);
            holdRepository.holdAll(seats(0, 1), currentHold, USER);
        });
    }

    private List<SeatId> seats(int... indexes) {
        List<SeatId> result = new ArrayList<>();
        for (int i : indexes) {
            result.add(new SeatId(seatIds.get(i)));
        }
        return result;
    }

    private long activeSeats() {
        return jdbc().queryForObject("""
                SELECT COUNT(*) FROM seat_inventory
                 WHERE held_by = ? AND status IN ('HELD', 'PAYING')
                """, Long.class, USER.value());
    }

    private int heldSeats() {
        return quota.heldSeats(USER.value(), SCOPE_ID);
    }

    // ------------------------------------------------------------------
    // 예외 분류. Spring 타입 하나에 결합하지 않고 근본 원인의 벤더 코드를 본다.

    /** 관측한 실패 하나의 분류 결과. */
    private record Failure(String thread, String springType, String sqlState, int vendorCode) {

        boolean isDeadlock() {
            // MySQL 1213 / SQLState 40001 = 데드락. 1205 / HY000 = 잠금 대기 초과.
            return vendorCode == 1213 || "40001".equals(sqlState);
        }

        boolean isLockWaitTimeout() {
            return vendorCode == 1205;
        }

        /**
         * 예상 가능한 좌석 경합인가 (규칙 21).
         *
         * <p>{@link SeatUnavailableException} 은 SQL 오류가 아니므로 벤더 코드가 없다.
         * 벤더 코드가 붙어 있으면 그것은 기술 오류이지 경합이 아니다.
         */
        boolean isExpectedSeatContention() {
            return SeatUnavailableException.class.getSimpleName().equals(springType)
                    && sqlState == null && vendorCode == 0;
        }

        @Override
        public String toString() {
            return "%s: %s (SQLState=%s, vendorCode=%d)"
                    .formatted(thread, springType, sqlState, vendorCode);
        }
    }

    /**
     * 근본 원인을 찾아 벤더 코드로 분류한다.
     *
     * <h2>원인 사슬만 보면 데드락을 놓친다 — 실측으로 알게 된 것</h2>
     *
     * <p>{@code holdAll} 은 NESTED savepoint 안에서 실행된다 (Task 2F). 그 안에서 데드락이 나면
     * InnoDB 가 <b>트랜잭션 전체를 롤백</b>하므로 savepoint 자체가 사라진다. 이어서 Spring 이
     * savepoint 로 롤백하려다 실패하고, 원래 예외를 <b>{@code TransactionSystemException} 으로
     * 덮어쓴다</b> ("Application exception overridden by rollback exception").
     *
     * <p>그 결과 밖으로 나오는 예외의 원인 사슬에는 MySQL <b>1305 (SAVEPOINT does not exist)</b>
     * 만 남고 <b>1213 (deadlock)</b> 은 보이지 않는다. 실제 데드락은
     * {@link TransactionSystemException#getApplicationException()} 쪽에 있다.
     *
     * <p>그래서 두 갈래를 모두 훑는다. 앱 계층도 같은 이유로 이 예외를 풀어봐야 하며,
     * 그러지 않으면 <b>데드락을 다른 기술 오류로 오분류</b>하게 된다 — 실험 문서의 남은 위험 참고.
     */
    private static Failure classify(String thread, Throwable thrown) {
        Failure viaCause = scan(thread, thrown, thrown);
        if (viaCause.isDeadlock() || viaCause.isLockWaitTimeout()) {
            return viaCause;
        }
        if (thrown instanceof TransactionSystemException tse
                && tse.getApplicationException() != null) {
            Failure viaApp = scan(thread, thrown, tse.getApplicationException());
            if (viaApp.isDeadlock() || viaApp.isLockWaitTimeout()) {
                return viaApp;
            }
        }
        return viaCause;
    }

    private static Failure scan(String thread, Throwable outer, Throwable from) {
        for (Throwable t = from; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return new Failure(thread, outer.getClass().getSimpleName(),
                        sql.getSQLState(), sql.getErrorCode());
            }
        }
        return new Failure(thread, outer.getClass().getSimpleName(), null, 0);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timeout — 테스트 배선 오류");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 두 작업을 동시에 돌리고 실패를 분류해 모은다. ExecutorService 종료를 보장한다. */
    private List<Failure> runBoth(Runnable first, String firstName,
            Runnable second, String secondName) {
        List<Failure> failures = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(2);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            pool.submit(() -> {
                try {
                    first.run();
                } catch (Throwable t) {
                    failures.add(classify(firstName, t));
                } finally {
                    done.countDown();
                }
            });
            pool.submit(() -> {
                try {
                    second.run();
                } catch (Throwable t) {
                    failures.add(classify(secondName, t));
                } finally {
                    done.countDown();
                }
            });
            await(done);
        }
        return failures;
    }

    // ------------------------------------------------------------------
    // 검증 헬퍼. 문자열 비교를 테스트마다 반복하지 않고 여기 모은다.

    /**
     * 통일 순서 경합에서 <b>허용되는 실패만</b> 났는지 확인한다.
     *
     * <h2>왜 "데드락만 없으면 통과" 로는 부족한가</h2>
     *
     * <p>{@link #runBoth} 는 작업 스레드의 <b>모든</b> {@link Throwable} 을 {@link Failure} 로
     * 바꾼다. 데드락(1213)과 잠금 대기 초과(1205)만 거부하면 다른 SQL 오류,
     * {@code NullPointerException}, 배선 실수, 심지어 스레드 안에서 터진 {@code AssertionError}
     * 까지 <b>조용히 통과</b>한다. 그것이 false positive 다.
     *
     * <p>그래서 허용 목록 방식으로 뒤집는다 — <b>{@link SeatUnavailableException} 외의 실패는
     * 전부 테스트 실패</b>다.
     *
     * <h2>겹치는 좌석 경합에서 허용되는 결과</h2>
     *
     * <p>두 흐름은 quota 행에서 직렬화되므로 스케줄링에 따라 둘 중 하나가 나온다.
     * <ul>
     *   <li><b>해제가 quota 를 먼저 잡음</b> → 해제·감소 후 선점 진행 → 둘 다 성공 → {@code failures = []}</li>
     *   <li><b>선점이 quota 를 먼저 잡음</b> → 아직 {@code HELD} 인 좌석을 다시 잡으려다
     *       {@code SeatUnavailableException} → quota 증가도 함께 롤백 → 그 뒤 해제 진행</li>
     * </ul>
     * 둘 다 정상이므로 크기는 0 또는 1 이다.
     */
    private static void assertUnifiedContentionFailuresAreExpected(List<Failure> failures) {
        assertThat(failures)
                .as("데드락(1213/40001)이 없어야 한다. 관측=%s", failures)
                .noneMatch(Failure::isDeadlock);
        assertThat(failures)
                .as("잠금 대기 초과(1205)도 없어야 한다. 관측=%s", failures)
                .noneMatch(Failure::isLockWaitTimeout);
        assertThat(failures)
                .as("허용되는 실패는 예상 가능한 좌석 경합뿐이다. 알 수 없는 실패 관측=%s", failures)
                .allMatch(Failure::isExpectedSeatContention);
        assertThat(failures)
                .as("두 흐름은 quota 행에서 직렬화되므로 실패는 최대 하나다. 관측=%s", failures)
                .hasSizeLessThanOrEqualTo(1);
    }

    /**
     * 역순 잠금 경합에서 <b>데드락 외의 알 수 없는 실패</b>가 없는지 확인한다.
     *
     * <p>데드락으로 한쪽이 죽으면 살아남은 쪽이 좌석 경합을 만날 수 있으므로
     * {@link SeatUnavailableException} 은 함께 나올 수 있다. 그 둘만 허용한다.
     */
    private static void assertReversedOrderFailuresAreExpected(List<Failure> failures) {
        assertThat(failures)
                .as("데드락 또는 예상 가능한 좌석 경합 외의 실패가 있으면 안 된다. 관측=%s", failures)
                .allMatch(f -> f.isDeadlock() || f.isExpectedSeatContention());
    }

    // ==================================================================

    @Nested
    @DisplayName("1. ★ 역순 잠금 — 실제 MySQL 데드락 재현")
    class 역순_잠금 {

        /**
         * 두 트랜잭션이 만드는 대기 그래프.
         *
         * <pre>
         *   TX-HOLD    : quota 잠금 보유 → seat 잠금 대기
         *   TX-RELEASE : seat  잠금 보유 → quota 잠금 대기
         * </pre>
         *
         * <p>latch 는 <b>잠금을 얻은 직후</b>에만 신호한다. 잠금을 쥔 채 서로를 기다리는
         * 위치에 두면 테스트 자체가 멈추므로, 대기는 DB 잠금에만 맡긴다.
         *
         * <p>{@code Thread.sleep} 을 쓰지 않는다. 순서는 latch 가 고정한다.
         */
        private List<Failure> 역순으로_경합시킨다() {
            CountDownLatch seatLocked = new CountDownLatch(1);
            CountDownLatch quotaLocked = new CountDownLatch(1);

            Runnable txRelease = () -> {
                TransactionTemplate tx = newTransactionTemplate();
                tx.executeWithoutResult(status -> {
                    // (1) seat 잠금 먼저 — 잘못된 순서다.
                    int released = releaseRepository.releaseAll(currentHold, USER);
                    seatLocked.countDown();

                    // (2) 상대가 quota 를 잠글 때까지 기다린 뒤 quota 로 넘어간다.
                    await(quotaLocked);
                    quota.release(USER.value(), SCOPE_ID, released);
                });
            };

            Runnable txHold = () -> {
                TransactionTemplate tx = newTransactionTemplate();
                tx.executeWithoutResult(status -> {
                    // (1) 상대가 seat 를 잠글 때까지 기다린다.
                    await(seatLocked);

                    // (2) quota 잠금 먼저 — 선점 경로의 순서다.
                    quota.tryAcquire(USER.value(), SCOPE_ID, 2);
                    quotaLocked.countDown();

                    // (3) 상대가 쥔 바로 그 좌석을 잠그려 한다 → 순환 완성.
                    holdRepository.holdAll(seats(0, 1), HoldId.newId(), USER);
                });
            };

            return runBoth(txRelease, "TX-RELEASE", txHold, "TX-HOLD");
        }

        @Test
        void 역순_잠금은_실제_MySQL_데드락을_만든다() {
            List<Failure> failures = 역순으로_경합시킨다();

            System.out.println("[Task 2G-B] 역순 잠금 관측 결과: " + failures);

            assertThat(failures)
                    .as("두 트랜잭션 중 하나는 반드시 실패한다")
                    .isNotEmpty();
            assertThat(failures)
                    .as("★ 잠금 대기 초과(1205)가 아니라 데드락(1213/40001)이어야 한다. 관측=%s",
                            failures)
                    .anyMatch(Failure::isDeadlock);
            assertThat(failures)
                    .as("데드락이 잠금 대기 초과로 관측되면 사이클 재현이 아니다")
                    .noneMatch(Failure::isLockWaitTimeout);
            assertReversedOrderFailuresAreExpected(failures);
        }

        @Test
        void 데드락_희생자와_무관하게_최종_정합성이_유지된다() {
            List<Failure> failures = 역순으로_경합시킨다();

            // 어느 트랜잭션이 희생되는지는 MySQL 이 정한다. 계약이 아니다.
            assertThat(failures).anyMatch(Failure::isDeadlock);
            assertReversedOrderFailuresAreExpected(failures);

            assertThat((long) heldSeats())
                    .as("quota 와 실제 활성 좌석 수가 일치해야 한다. 관측=%s", failures)
                    .isEqualTo(activeSeats());
            assertThat(heldSeats()).as("quota 범위").isBetween(0, Task2gQuotaCounter.MAX_SEATS);
            assertThat(activeSeats()).as("부분 선점 없이 0석 또는 2석").isIn(0L, 2L);
        }

        /**
         * 분류가 실제로 동작하는지 본다.
         *
         * <p>{@code failures} 가 비어 있으면 아무것도 검증하지 못하는 vacuous assertion 이 되므로,
         * <b>데드락이 하나 이상 관측됐다는 것을 먼저 단언</b>한 뒤 분류를 확인한다.
         */
        @Test
        void 기술적_데드락을_좌석_경합으로_잘못_분류하지_않는다() {
            List<Failure> failures = 역순으로_경합시킨다();

            assertThat(failures)
                    .as("분류를 검증하려면 실패가 실제로 있어야 한다")
                    .isNotEmpty();
            List<Failure> deadlocks = failures.stream().filter(Failure::isDeadlock).toList();
            assertThat(deadlocks).as("데드락이 하나 이상 관측돼야 한다. 관측=%s", failures).isNotEmpty();

            assertThat(deadlocks)
                    .as("데드락은 예상 가능한 좌석 경합과 겹쳐 분류되면 안 된다")
                    .noneMatch(Failure::isExpectedSeatContention);
            assertReversedOrderFailuresAreExpected(failures);
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("2. quota → seat 통일 순서")
    class 통일_순서 {

        /**
         * 해제 후보 흐름 — 애플리케이션 서비스가 할 일을 테스트가 흉내낸다.
         *
         * <p>운영 {@code JdbcSeatReleaseRepository} 는 바꾸지 않았다. 바뀐 것은
         * <b>호출 순서</b>뿐이다: quota 행을 먼저 잠그고 좌석을 나중에 잠근다.
         */
        private void 통일_해제(java.util.concurrent.atomic.AtomicInteger releasedOut,
                java.util.concurrent.atomic.AtomicInteger quotaCalls) {
            TransactionTemplate tx = newTransactionTemplate();
            tx.executeWithoutResult(status -> {
                quota.lockQuotaRow(USER.value(), SCOPE_ID);          // (1) quota 먼저
                int released = releaseRepository.releaseAll(currentHold, USER);  // (2) seat
                releasedOut.set(released);

                if (released > 0) {                                  // 0 이면 감소 SQL 자체를 실행하지 않는다
                    quotaCalls.incrementAndGet();
                    boolean ok = quota.release(USER.value(), SCOPE_ID, released);
                    if (!ok) {
                        throw new IllegalStateException(
                                "quota 감소 거부 — 좌석 해제까지 롤백한다 (released=" + released + ")");
                    }
                }
            });
        }

        /** 선점 후보 흐름. 선점은 원래부터 quota → seat 순서다. */
        private void 통일_선점(List<SeatId> plan) {
            TransactionTemplate tx = newTransactionTemplate();
            tx.executeWithoutResult(status -> {
                if (!quota.tryAcquire(USER.value(), SCOPE_ID, plan.size())) {
                    throw new QuotaRejected();
                }
                holdRepository.holdAll(plan, HoldId.newId(), USER);
            });
        }

        private List<Failure> 통일_순서로_경합시킨다(List<SeatId> holdPlan) {
            CyclicBarrier start = new CyclicBarrier(2);
            var released = new java.util.concurrent.atomic.AtomicInteger();
            var quotaCalls = new java.util.concurrent.atomic.AtomicInteger();

            return runBoth(
                    () -> {
                        await(start);
                        통일_해제(released, quotaCalls);
                    }, "TX-RELEASE",
                    () -> {
                        await(start);
                        통일_선점(holdPlan);
                    }, "TX-HOLD");
        }

        @Test
        void 같은_경합이_데드락_없이_완료된다() {
            // 역순 실험과 같은 좌석·같은 사용자로 경합시킨다.
            List<Failure> failures = 통일_순서로_경합시킨다(seats(0, 1));

            System.out.println("[Task 2G-B] 통일 순서 관측 결과: " + failures);

            assertUnifiedContentionFailuresAreExpected(failures);
        }

        @Test
        void 최종_quota_와_활성_좌석_수가_일치한다() {
            List<Failure> failures = 통일_순서로_경합시킨다(seats(0, 1));

            // 실패 목록을 무시한 채 최종 상태만 보고 통과하지 않는다.
            assertUnifiedContentionFailuresAreExpected(failures);

            assertThat((long) heldSeats()).isEqualTo(activeSeats());
            assertThat(heldSeats()).isBetween(0, Task2gQuotaCounter.MAX_SEATS);
        }

        /**
         * <b>겹치지 않는 좌석</b>을 요청하는 경우 — 선점은 반드시 성공해야 한다.
         *
         * <p>기존 홀드는 {@code seats(0,1)} 이고 신규 요청은 {@code seats(2,3)} 이라 좌석이 겹치지 않는다.
         * 두 흐름은 quota 행에서만 직렬화되므로, 정상 구현이라면 <b>어느 순서로 진행되든</b>
         * 신규 2석 선점은 성공한다 (해제 후 quota 는 0, 선점 먼저여도 2 + 2 ≤ 4).
         *
         * <p>따라서 "0석 또는 2석" 은 너무 약하다. 선점 흐름 전체가 실패해 0석이 되는 것도
         * 이 시나리오에서는 <b>허용하지 않는다.</b> 겹치는 좌석의 정상 경합 실패는
         * {@code 같은_경합이_데드락_없이_완료된다} 가 따로 다룬다.
         */
        @Test
        void 겹치지_않는_좌석_요청은_반드시_2석_전부_선점된다() {
            List<Failure> failures = 통일_순서로_경합시킨다(seats(2, 3));

            assertThat(failures)
                    .as("좌석이 겹치지 않으므로 어떤 실패도 나면 안 된다. 관측=%s", failures)
                    .isEmpty();

            long newlyHeld = jdbc().queryForObject("""
                    SELECT COUNT(*) FROM seat_inventory
                     WHERE id IN (?, ?) AND status = 'HELD'
                    """, Long.class, seatIds.get(2), seatIds.get(3));

            assertThat(newlyHeld).as("★ 부분 성공(1석)도 전체 실패(0석)도 허용하지 않는다").isEqualTo(2L);
            assertThat((long) heldSeats()).as("quota 와 실제 좌석 일치").isEqualTo(activeSeats());
        }

        @Test
        void 해제_결과가_0이면_quota_감소를_수행하지_않는다() {
            // 먼저 홀드를 전부 해제해 둔다. 그 뒤 같은 홀드로 다시 해제하면 0건이다.
            var first = new java.util.concurrent.atomic.AtomicInteger();
            var firstCalls = new java.util.concurrent.atomic.AtomicInteger();
            통일_해제(first, firstCalls);
            assertThat(first.get()).isEqualTo(2);
            assertThat(heldSeats()).isZero();

            var second = new java.util.concurrent.atomic.AtomicInteger();
            var secondCalls = new java.util.concurrent.atomic.AtomicInteger();
            통일_해제(second, secondCalls);

            assertThat(second.get()).as("자연 멱등 — 두 번째 해제는 0건").isZero();
            assertThat(secondCalls.get()).as("★ quota 감소 SQL 을 실행하지 않았다").isZero();
            assertThat(heldSeats()).as("quota 불변").isZero();
        }

        @Test
        void quota_감소가_거부되면_좌석_해제도_롤백된다() {
            // 앞선 경로가 감소를 빠뜨려 quota 가 실제보다 작아진 드리프트 상태를 만든다.
            // (좌석 2석 / quota 1)
            jdbc().update("UPDATE " + Task2gQuotaCounter.TABLE
                    + " SET held_seats = 1 WHERE user_id = ? AND quota_scope_id = ?",
                    USER.value(), SCOPE_ID);

            var released = new java.util.concurrent.atomic.AtomicInteger();
            var calls = new java.util.concurrent.atomic.AtomicInteger();

            assertThatThrownBy(() -> 통일_해제(released, calls))
                    .as("1 - 2 < 0 이라 감소가 거부되고, 흐름이 예외로 중단된다")
                    .isInstanceOf(IllegalStateException.class);

            assertThat(activeSeats()).as("★ 좌석 해제도 함께 롤백됐다").isEqualTo(2);
            assertThat(heldSeats()).as("quota 도 그대로다").isEqualTo(1);
        }

        @Test
        void 외부_롤백_시_quota_와_좌석이_함께_원복된다() {
            TransactionTemplate tx = newTransactionTemplate();
            tx.executeWithoutResult(status -> {
                quota.lockQuotaRow(USER.value(), SCOPE_ID);
                int released = releaseRepository.releaseAll(currentHold, USER);
                quota.release(USER.value(), SCOPE_ID, released);
                status.setRollbackOnly();
            });

            assertThat(activeSeats()).as("좌석 원복").isEqualTo(2);
            assertThat(heldSeats()).as("quota 원복").isEqualTo(2);
        }
    }

    /** quota 확보 실패를 나타내는 테스트 전용 신호. 운영 예외를 추가하지 않는다. */
    private static final class QuotaRejected extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
