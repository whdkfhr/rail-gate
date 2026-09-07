package com.railgate.reservation.infra.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.infra.MySqlTestSupport;
import com.railgate.reservation.infra.quota.Task2gfExpirySweeper.FailureKind;
import com.railgate.reservation.infra.quota.Task2gfExpirySweeper.TransactionMode;
import com.railgate.reservation.infra.seat.JdbcMultiSeatHoldRepository;
import com.railgate.reservation.infra.seat.JdbcSeatExpiryRepository;
import com.railgate.reservation.seat.SeatId;
import java.sql.SQLException;
import java.sql.SQLTransactionRollbackException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ★ 실험 E — 데드락 재시도 정책 (Task 2G-F).
 *
 * <h2>결정해야 하는 것</h2>
 *
 * <p>2G-C §10-6 — <b>재시도 횟수와 백오프.</b> 스위퍼는 배치라 사용자 요청과 다른 정책이 가능하다.
 *
 * <h2>분류를 먼저 정해야 한다</h2>
 *
 * <p>"실패하면 재시도" 로는 부족하다. 재시도가 의미 있는 실패와 그렇지 않은 실패를 구분하지
 * 않으면, <b>quota drift 를 3번 반복해 확인하면서 배치만 느려진다.</b>
 *
 * <p>특히 2G-B 가 관측한 것 — <b>NESTED savepoint 가 MySQL 1213(deadlock) 을 1305 로 덮는다.</b>
 * 최상위 예외만 보고 분류하면 재시도해야 할 데드락을 영구 실패로 처리한다.
 * 그래서 {@link Task2gfFailureClassifier} 는 <b>cause 사슬 전체</b>를 훑는다.
 *
 * <h2>느린 테스트를 만들지 않는다</h2>
 *
 * <p>백오프는 주입 가능한 전략으로 두고 테스트는 <b>0 지연</b>을 쓴다. 실제 대기 시간에
 * 의존하면 테스트가 느려지기만 하고 검증되는 것은 없다. 운영 계약(상한 있는 백오프)은
 * 문서로만 정한다.
 */
@Timeout(300)
@DisplayName("실험 E — 데드락 재시도 정책 (Task 2G-F)")
class ExpirySweeperRetryContractTest extends MySqlTestSupport {

    private static final Duration HOLD_DURATION = Duration.ofMinutes(5);
    private static final int TIMEOUT_SECONDS = 60;

    private static final long CHUSEOK = 9001L;
    private static final long SCHEDULE = 101L;
    private static final UserId USER_A = new UserId(11L);
    private static final UserId USER_B = new UserId(22L);

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

    // ------------------------------------------------------------------

    /**
     * §1 ★ 실제 MySQL 데드락을 결정적으로 만든다.
     *
     * <p>두 트랜잭션이 같은 두 quota 행을 <b>반대 순서</b>로 잠근다. 이것이 2G-B 가
     * "quota 키 오름차순" 을 정한 이유이며, 그 순서를 어기면 무슨 일이 나는지를 여기서 본다.
     */
    @Nested
    @DisplayName("§1 ★ 실제 데드락 재현과 분류")
    class 데드락_재현 {

        @Test
        void 반대_순서로_잠그면_MySQL_1213_이_발생한다() throws Exception {
            quota.ensureRow(USER_A.value(), CHUSEOK);
            quota.ensureRow(USER_B.value(), CHUSEOK);

            List<Throwable> failures = raceOppositeLockOrder();

            assertThat(failures)
                    .as("한쪽이 데드락 피해자가 돼야 한다")
                    .hasSize(1);
            Throwable deadlock = failures.get(0);

            assertThat(Task2gfFailureClassifier.hasVendorCode(
                    deadlock, Task2gfFailureClassifier.MYSQL_DEADLOCK))
                    .as("cause 사슬에 vendor code 1213 이 있어야 한다: %s", deadlock)
                    .isTrue();
            assertThat(Task2gfFailureClassifier.classify(deadlock))
                    .isEqualTo(FailureKind.DEADLOCK);
            assertThat(Task2gfFailureClassifier.isRetryable(FailureKind.DEADLOCK)).isTrue();
        }

        /**
         * ★ 최상위 예외 타입만으로는 분류할 수 없다.
         *
         * <p>실제로 어떤 Spring 예외로 올라오는지 기록한다. 2G-B 가 관측한 1305 덮어쓰기가
         * 이 경로에서 일어나는지도 함께 남긴다.
         */
        @Test
        void 최상위_예외_타입에_의존하지_않는다() throws Exception {
            quota.ensureRow(USER_A.value(), CHUSEOK);
            quota.ensureRow(USER_B.value(), CHUSEOK);

            Throwable deadlock = raceOppositeLockOrder().get(0);

            System.out.printf("[Task 2G-F] 데드락 최상위 예외 = %s / root = %s%n",
                    deadlock.getClass().getSimpleName(), rootOf(deadlock).getClass().getSimpleName());

            // 최상위 타입이 무엇이든 cause 를 훑으면 분류된다.
            assertThat(Task2gfFailureClassifier.classify(deadlock))
                    .isEqualTo(FailureKind.DEADLOCK);
        }

        /** 두 트랜잭션이 quota 행을 반대 순서로 잠근다. 데드락 피해자의 예외를 돌려준다. */
        private List<Throwable> raceOppositeLockOrder() throws Exception {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch firstLockDone = new CountDownLatch(2);
            List<Throwable> failures = new CopyOnWriteArrayList<>();

            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<Future<?>> futures = new ArrayList<>();
            try {
                for (int i = 0; i < 2; i++) {
                    boolean ascending = i == 0;
                    futures.add(pool.submit(() -> {
                        TransactionTemplate tx = new TransactionTemplate(
                                new DataSourceTransactionManager(dataSource()));
                        long first = ascending ? USER_A.value() : USER_B.value();
                        long second = ascending ? USER_B.value() : USER_A.value();
                        try {
                            tx.executeWithoutResult(status -> {
                                quota.lockQuotaRow(first, CHUSEOK);
                                firstLockDone.countDown();
                                try {
                                    // 양쪽이 첫 행을 잡은 뒤에야 두 번째를 시도한다.
                                    if (!firstLockDone.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                                        throw new IllegalStateException("출발 동기화 실패");
                                    }
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException(e);
                                }
                                quota.lockQuotaRow(second, CHUSEOK);
                            });
                        } catch (RuntimeException e) {
                            failures.add(e);
                        }
                        return null;
                    }));
                }
                assertThat(ready.getCount()).isEqualTo(2);
                pool.shutdown();
                assertThat(pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        .as("데드락 재현이 %d초 안에 끝나야 한다", TIMEOUT_SECONDS)
                        .isTrue();
            } finally {
                pool.shutdownNow();
            }
            for (Future<?> future : futures) {
                future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
            return List.copyOf(failures);
        }

        private static Throwable rootOf(Throwable failure) {
            Throwable root = failure;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            return root;
        }
    }

    /**
     * §2 분류 계약 — 무엇을 재시도하고 무엇을 재시도하지 않는가.
     */
    @Nested
    @DisplayName("§2 실패 분류")
    class 실패_분류 {

        @Test
        void 일시적_실패만_재시도_대상이다() {
            assertThat(Task2gfFailureClassifier.isRetryable(FailureKind.DEADLOCK)).isTrue();
            assertThat(Task2gfFailureClassifier.isRetryable(FailureKind.LOCK_WAIT_TIMEOUT)).isTrue();
            assertThat(Task2gfFailureClassifier.isRetryable(
                    FailureKind.TRANSIENT_TRANSACTION_ROLLBACK))
                    .as("원인은 몰라도 드라이버가 일시적이라고 알려 준 것은 사실이다")
                    .isTrue();
            assertThat(Task2gfFailureClassifier.isRetryable(FailureKind.TRANSIENT_LOCK_FAILURE))
                    .as("Spring 이 잠금 실패로 번역한 것도 일시적이다")
                    .isTrue();

            assertThat(Task2gfFailureClassifier.isRetryable(FailureKind.QUOTA_ROW_MISSING)).isFalse();
            assertThat(Task2gfFailureClassifier.isRetryable(FailureKind.QUOTA_DECREASE_REJECTED))
                    .isFalse();
            assertThat(Task2gfFailureClassifier.isRetryable(FailureKind.CORRUPT_CANDIDATE)).isFalse();
            assertThat(Task2gfFailureClassifier.isRetryable(FailureKind.UNKNOWN))
                    .as("모르는 실패를 재시도하면 원인을 감춘 채 부하만 늘린다")
                    .isFalse();
        }

        /** vendor code 가 있으면 원인을 특정할 수 있다. 두 코드는 서로 다른 신호다. */
        @Test
        void vendor_code_1213_은_DEADLOCK_이다() {
            SQLException deadlock = new SQLException("Deadlock found", "40001",
                    Task2gfFailureClassifier.MYSQL_DEADLOCK);

            FailureKind kind = Task2gfFailureClassifier.classify(
                    new IllegalStateException("정산 실패", deadlock));

            assertThat(kind).isEqualTo(FailureKind.DEADLOCK);
            assertThat(Task2gfFailureClassifier.isRetryable(kind)).isTrue();
        }

        @Test
        void vendor_code_1205_는_LOCK_WAIT_TIMEOUT_이다() {
            SQLException timeout = new SQLException("Lock wait timeout exceeded", "HY000",
                    Task2gfFailureClassifier.MYSQL_LOCK_WAIT_TIMEOUT);

            FailureKind kind = Task2gfFailureClassifier.classify(
                    new IllegalStateException("정산 실패", timeout));

            assertThat(kind).isEqualTo(FailureKind.LOCK_WAIT_TIMEOUT);
            assertThat(Task2gfFailureClassifier.isRetryable(kind)).isTrue();
        }

        /** ★ 2G-B 의 관측 — savepoint 가 1213 을 1305 로 덮어도 분류돼야 한다. */
        @Test
        void savepoint_가_덮은_데드락도_cause_에서_찾아낸다() {
            SQLException deadlock = new SQLException(
                    "Deadlock found when trying to get lock", "40001",
                    Task2gfFailureClassifier.MYSQL_DEADLOCK);
            SQLException masked = new SQLException(
                    "XAER_RMFAIL: rollback to savepoint failed", "XAE05",
                    Task2gfFailureClassifier.MYSQL_XA_ROLLBACK_FAIL, deadlock);
            RuntimeException wrapped = new IllegalStateException("정산 실패",
                    new org.springframework.jdbc.UncategorizedSQLException("settle", "UPDATE", masked));

            assertThat(Task2gfFailureClassifier.classify(wrapped))
                    .as("최상위는 1305 지만 원인은 1213 이다")
                    .isEqualTo(FailureKind.DEADLOCK);
        }

        @Test
        void lock_wait_timeout_을_구분한다() {
            SQLException timeout = new SQLException(
                    "Lock wait timeout exceeded", "HY000",
                    Task2gfFailureClassifier.MYSQL_LOCK_WAIT_TIMEOUT);

            assertThat(Task2gfFailureClassifier.classify(
                    new IllegalStateException("정산 실패", timeout)))
                    .isEqualTo(FailureKind.LOCK_WAIT_TIMEOUT);
        }

        /**
         * ★ vendor code 없는 JDBC 롤백 예외를 데드락으로 세지 않는다.
         *
         * <p>{@code SQLTransactionRollbackException} 은 deadlock 뿐 아니라 직렬화 실패 등
         * 다른 일시적 롤백도 나타낸다. 이것을 {@code DEADLOCK} 으로 세면 <b>데드락 지표가
         * 부풀려져</b> 잠금 순서를 의심하며 엉뚱한 곳을 고치게 된다.
         *
         * <p>재시도는 한다 — 원인은 몰라도 <b>일시적이라는 것은 드라이버가 말해 줬다.</b>
         */
        @Test
        void vendor_code_없는_JDBC_롤백_예외는_별도_분류다() {
            FailureKind kind = Task2gfFailureClassifier.classify(
                    new IllegalStateException("정산 실패",
                            new SQLTransactionRollbackException("rolled back")));

            assertThat(kind)
                    .as("원인을 특정할 수 없으므로 데드락으로 세지 않는다")
                    .isEqualTo(FailureKind.TRANSIENT_TRANSACTION_ROLLBACK);
            assertThat(kind).isNotEqualTo(FailureKind.DEADLOCK);
            assertThat(Task2gfFailureClassifier.isRetryable(kind))
                    .as("분류를 나눈 것은 지표 때문이지 재시도 여부 때문이 아니다")
                    .isTrue();
        }

        /** 같은 JDBC 타입이라도 vendor code 가 붙어 있으면 그것이 이긴다. */
        @Test
        void JDBC_롤백_예외에_1213_이_있으면_DEADLOCK_이다() {
            SQLTransactionRollbackException withCode = new SQLTransactionRollbackException(
                    "Deadlock found", "40001", Task2gfFailureClassifier.MYSQL_DEADLOCK);

            assertThat(Task2gfFailureClassifier.classify(
                    new IllegalStateException("정산 실패", withCode)))
                    .as("vendor code 가 있으면 원인을 특정할 수 있다")
                    .isEqualTo(FailureKind.DEADLOCK);
        }

        /**
         * ★ vendor code 없는 generic Spring 잠금 예외는 deadlock 도 timeout 도 아니다.
         *
         * <p>{@code PessimisticLockingFailureException} 이 알려 주는 것은 <b>"잠금을 얻지
         * 못했다" 까지다.</b> 1205 처럼 보유 시간이 길어서인지, 1213 처럼 순서가 어긋나서인지
         * 말해 주지 않는다.
         *
         * <p>이것을 {@code LOCK_WAIT_TIMEOUT} 으로 세면 <b>timeout 지표가 부풀려져</b>
         * 트랜잭션 길이나 타임아웃 설정을 의심하게 만든다 — 원인이 거기에 없을 수도 있는데도.
         */
        @Test
        void vendor_code_없는_generic_잠금_예외는_별도_분류다() {
            FailureKind kind = Task2gfFailureClassifier.classify(
                    new CannotAcquireLockException("잠금 실패", new SQLException()));

            assertThat(kind)
                    .as("근거 없이 deadlock 도 timeout 도 아니다")
                    .isEqualTo(FailureKind.TRANSIENT_LOCK_FAILURE);
            assertThat(Task2gfFailureClassifier.isRetryable(kind))
                    .as("다만 재시도 대상인 것은 같다")
                    .isTrue();
        }

        /** ★ 네 일시적 분류가 서로 다르다. 지표가 섞이지 않는다는 뜻이다. */
        @Test
        void 네_일시적_분류가_서로_다르다() {
            assertThat(List.of(
                    FailureKind.DEADLOCK,
                    FailureKind.LOCK_WAIT_TIMEOUT,
                    FailureKind.TRANSIENT_TRANSACTION_ROLLBACK,
                    FailureKind.TRANSIENT_LOCK_FAILURE))
                    .doesNotHaveDuplicates()
                    .allSatisfy(kind -> assertThat(Task2gfFailureClassifier.isRetryable(kind))
                            .as("%s 는 재시도 대상이다", kind)
                            .isTrue());
        }

        /** ★ vendor code 가 있으면 generic 예외 클래스보다 그것을 우선한다 — 1213. */
        @Test
        void generic_잠금_예외에_1213_이_있으면_DEADLOCK_이다() {
            SQLException deadlock = new SQLException("deadlock", "40001",
                    Task2gfFailureClassifier.MYSQL_DEADLOCK);

            assertThat(Task2gfFailureClassifier.classify(
                    new CannotAcquireLockException("잠금 실패", deadlock)))
                    .as("클래스는 generic 이지만 코드는 1213 이다")
                    .isEqualTo(FailureKind.DEADLOCK);
        }

        /** ★ 같은 generic 예외라도 1205 가 있으면 LOCK_WAIT_TIMEOUT 이다. */
        @Test
        void generic_잠금_예외에_1205_가_있으면_LOCK_WAIT_TIMEOUT_이다() {
            SQLException timeout = new SQLException("Lock wait timeout exceeded", "HY000",
                    Task2gfFailureClassifier.MYSQL_LOCK_WAIT_TIMEOUT);

            assertThat(Task2gfFailureClassifier.classify(
                    new CannotAcquireLockException("잠금 실패", timeout)))
                    .as("클래스는 generic 이지만 코드는 1205 다")
                    .isEqualTo(FailureKind.LOCK_WAIT_TIMEOUT);
        }

        /** 무결성 오류와 문법 오류는 재시도하지 않는다. 재시도해도 결과가 같다. */
        @Test
        void 무결성_오류는_재시도하지_않는다() {
            FailureKind kind = Task2gfFailureClassifier.classify(
                    new DataIntegrityViolationException("check 위반", new SQLException("", "23000", 3819)));

            assertThat(kind).isEqualTo(FailureKind.UNKNOWN);
            assertThat(Task2gfFailureClassifier.isRetryable(kind)).isFalse();
        }

        @Test
        void 업무_실패를_잠금_실패로_오분류하지_않는다() {
            assertThat(Task2gfFailureClassifier.classify(
                    new Task2gfExpirySweeper.QuotaRowMissingException("행 없음")))
                    .isEqualTo(FailureKind.QUOTA_ROW_MISSING);
            assertThat(Task2gfFailureClassifier.classify(
                    new Task2gfExpirySweeper.QuotaDecreaseRejectedException("감소 거부")))
                    .isEqualTo(FailureKind.QUOTA_DECREASE_REJECTED);
        }

        @Test
        void null_은_거부한다() {
            assertThatThrownBy(() -> Task2gfFailureClassifier.classify(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /**
     * §3 재시도 단위 — 실패한 사용자 그룹만, 이미 커밋된 그룹은 다시 하지 않는다.
     */
    @Nested
    @DisplayName("§3 재시도 단위와 횟수")
    class 재시도_단위 {

        @Test
        void 업무_실패는_한_번만_시도한다() {
            List<SeatId> a = holdSeats(USER_A, 2, "A");
            List<SeatId> b = holdSeats(USER_B, 2, "B");
            expireNow(a);
            expireNow(b);
            jdbc().update("DELETE FROM task2g_user_hold_quota WHERE user_id = ?", USER_B.value());

            AtomicInteger backoffCalls = new AtomicInteger();
            Task2gfSweepOutcome outcome = new Task2gfExpirySweeper(
                    dataSource(), expiryRepository, quota,
                    TransactionMode.PER_USER_GROUP, attempt -> backoffCalls.incrementAndGet())
                    .sweep(10);

            assertThat(outcome.failed()).hasSize(1);
            assertThat(outcome.failed().get(0).attempts()).isEqualTo(1);
            assertThat(backoffCalls.get())
                    .as("재시도하지 않으므로 백오프도 부르지 않는다")
                    .isZero();
            assertThat(outcome.totalAttempts())
                    .as("A 1회 + B 1회")
                    .isEqualTo(2);
        }

        /**
         * ★ 이미 커밋된 그룹은 다시 실행하지 않는다.
         *
         * <p>덤으로 관측되는 것 — <b>실패 그룹은 좌석 UPDATE 를 아예 보내지 않는다.</b>
         * quota 행 잠금이 좌석보다 먼저이므로(quota → seat, 2G-B) 행이 없으면
         * 좌석을 건드리기 전에 멈춘다. 롤백할 좌석 변경 자체가 생기지 않는다.
         */
        @Test
        void 성공한_그룹은_다시_실행하지_않는다() {
            List<SeatId> a = holdSeats(USER_A, 3, "A");
            List<SeatId> b = holdSeats(USER_B, 2, "B");
            expireNow(a);
            expireNow(b);
            jdbc().update("DELETE FROM task2g_user_hold_quota WHERE user_id = ?", USER_B.value());

            Task2gfSweepOutcome outcome = Task2gfExpirySweeper.of(
                    dataSource(), expiryRepository, quota, TransactionMode.PER_USER_GROUP)
                    .sweep(10);

            assertThat(outcome.succeeded()).hasSize(1);
            assertThat(outcome.sql().seatUpdates())
                    .as("A 그룹 1회뿐이다. 성공 그룹을 재실행하면 2 이상이 된다")
                    .isEqualTo(1);
            assertThat(outcome.sql().quotaUpdates())
                    .as("quota 감소도 A 그룹 1회뿐이다")
                    .isEqualTo(1);
            assertThat(quota.heldSeats(USER_A.value(), CHUSEOK)).isZero();
        }

        /**
         * 최대 시도 횟수 상수. <b>이것만으로 재시도 계약을 증명하지 않는다</b> —
         * 실제 루프 동작은 §4 가 실패 주입으로 검증한다.
         */
        @Test
        void 최대_시도_횟수_상수는_3이다() {
            assertThat(Task2gfExpirySweeper.MAX_ATTEMPTS).isEqualTo(3);
        }
    }

    /**
     * §4 ★ 실제 재시도 루프 — 실패를 주입해 {@code settlePerGroup} 을 돌린다.
     *
     * <p>§2 는 <b>분류</b>만 검증한다. 분류가 맞아도 루프가 재시도하지 않거나, 이미 커밋된
     * 그룹을 다시 실행하거나, 같은 트랜잭션에서 재시도하면 계약은 깨진다.
     * <b>그것을 상수 확인으로는 알 수 없다.</b>
     *
     * <p>실패는 정산 트랜잭션 <b>안에서</b> 던진다. 그래야 그 트랜잭션이 롤백되고
     * 다음 시도가 새 트랜잭션을 연다. 예외의 cause 사슬에는 실제 MySQL vendor code 를
     * 넣어 분류기가 인식하게 한다 — 분류 경로를 우회하지 않는다.
     */
    @Nested
    @DisplayName("§4 ★ 실제 재시도 루프")
    class 재시도_루프 {

        /** 분류기가 인식하는 것과 같은 cause 사슬을 가진 잠금 실패. */
        private static RuntimeException lockFailure(int vendorCode, String message) {
            SQLException sqlException = new SQLException(message,
                    vendorCode == Task2gfFailureClassifier.MYSQL_DEADLOCK ? "40001" : "HY000",
                    vendorCode);
            return new org.springframework.jdbc.UncategorizedSQLException(
                    "settle", "UPDATE seat_inventory", sqlException);
        }

        /** 실패 주입 스위퍼. backoff 호출 횟수도 함께 센다. */
        private Task2gfExpirySweeper sweeperWith(
                Task2gfExpirySweeper.FailureInjector injector, AtomicInteger backoffCalls) {
            return new Task2gfExpirySweeper(
                    dataSource(), expiryRepository, quota, TransactionMode.PER_USER_GROUP,
                    attempt -> backoffCalls.incrementAndGet(), injector);
        }

        private static Task2gfExpirySweeper.GroupKey keyOf(UserId user) {
            return new Task2gfExpirySweeper.GroupKey(CHUSEOK, user.value());
        }

        private static long activeSeatsOf(UserId user) {
            return jdbc().queryForObject("""
                    SELECT COUNT(*) FROM seat_inventory
                     WHERE held_by = ? AND status IN ('HELD','PAYING')
                    """, Long.class, user.value());
        }

        /** A — 첫 시도 1213, 두 번째 시도 성공. */
        @Test
        void A_두_번째_시도에_성공하면_실패로_기록되지_않는다() {
            List<SeatId> seats = holdSeats(USER_A, 3, "A");
            expireNow(seats);

            AtomicInteger backoffCalls = new AtomicInteger();
            AtomicInteger injected = new AtomicInteger();
            Task2gfSweepOutcome outcome = sweeperWith((key, attempt) -> {
                if (attempt == 1) {
                    injected.incrementAndGet();
                    throw lockFailure(Task2gfFailureClassifier.MYSQL_DEADLOCK,
                            "Deadlock found when trying to get lock");
                }
            }, backoffCalls).sweep(10);

            assertThat(injected.get()).as("첫 시도에서만 실패시켰다").isEqualTo(1);
            assertThat(outcome.totalAttempts()).as("1회 실패 + 1회 성공").isEqualTo(2);
            assertThat(backoffCalls.get()).as("재시도 사이에 한 번 대기한다").isEqualTo(1);

            assertThat(outcome.failed()).as("결국 성공했으므로 실패 그룹이 아니다").isEmpty();
            assertThat(outcome.succeeded()).containsExactly(java.util.Map.entry(keyOf(USER_A), 3));
            assertThat(outcome.reclaimed()).isEqualTo(3);
            assertThat(quota.heldSeats(USER_A.value(), CHUSEOK))
                    .as("두 번째 시도가 정상 정산했다")
                    .isZero();
            assertThat(activeSeatsOf(USER_A)).isZero();
        }

        /** B — 1205 를 세 번 연속. 네 번째 시도는 없다. */
        @Test
        void B_세_번째_시도까지_실패하면_멈춘다() {
            List<SeatId> seats = holdSeats(USER_A, 3, "A");
            expireNow(seats);

            AtomicInteger backoffCalls = new AtomicInteger();
            AtomicInteger attemptsSeen = new AtomicInteger();
            Task2gfSweepOutcome outcome = sweeperWith((key, attempt) -> {
                attemptsSeen.incrementAndGet();
                throw lockFailure(Task2gfFailureClassifier.MYSQL_LOCK_WAIT_TIMEOUT,
                        "Lock wait timeout exceeded; try restarting transaction");
            }, backoffCalls).sweep(10);

            assertThat(attemptsSeen.get())
                    .as("★ 네 번째 시도는 없다")
                    .isEqualTo(Task2gfExpirySweeper.MAX_ATTEMPTS);
            assertThat(outcome.totalAttempts()).isEqualTo(3);
            assertThat(backoffCalls.get())
                    .as("마지막 실패 뒤에는 대기하지 않는다 — 3회 시도에 대기는 2회")
                    .isEqualTo(2);

            assertThat(outcome.failed()).singleElement().satisfies(failure -> {
                assertThat(failure.key()).isEqualTo(keyOf(USER_A));
                assertThat(failure.kind()).isEqualTo(FailureKind.LOCK_WAIT_TIMEOUT);
                assertThat(failure.attempts()).isEqualTo(Task2gfExpirySweeper.MAX_ATTEMPTS);
            });
            assertThat(outcome.reclaimed()).isZero();

            assertThat(activeSeatsOf(USER_A))
                    .as("좌석은 그대로 잡혀 있다")
                    .isEqualTo(3);
            assertThat(quota.heldSeats(USER_A.value(), CHUSEOK))
                    .as("카운터도 그대로다")
                    .isEqualTo(3);
        }

        /** C — 성공한 그룹은 다시 실행하지 않는다. */
        @Test
        void C_성공한_이전_그룹은_재실행하지_않는다() {
            List<SeatId> a = holdSeats(USER_A, 3, "A");
            List<SeatId> b = holdSeats(USER_B, 2, "B");
            expireNow(a);
            expireNow(b);

            AtomicInteger backoffCalls = new AtomicInteger();
            List<String> settleLog = new java.util.ArrayList<>();
            Task2gfSweepOutcome outcome = sweeperWith((key, attempt) -> {
                settleLog.add(key + "#" + attempt);
                // B 그룹만 첫 시도에 실패시킨다. A 는 항상 성공한다.
                if (key.userId() == USER_B.value() && attempt == 1) {
                    throw lockFailure(Task2gfFailureClassifier.MYSQL_DEADLOCK, "Deadlock found");
                }
            }, backoffCalls).sweep(10);

            assertThat(settleLog)
                    .as("★ A 는 한 번뿐이고 B 만 재시도된다")
                    .containsExactly(
                            keyOf(USER_A) + "#1",
                            keyOf(USER_B) + "#1",
                            keyOf(USER_B) + "#2");
            assertThat(backoffCalls.get()).isEqualTo(1);

            assertThat(outcome.succeeded())
                    .containsEntry(keyOf(USER_A), 3)
                    .containsEntry(keyOf(USER_B), 2);
            assertThat(outcome.failed()).isEmpty();

            // SQL 계수로도 확인한다 — A 의 좌석 회수와 quota 감소가 각각 한 번뿐이어야 한다.
            // 주입 훅이 settleGroup 앞에서 던지므로 B 의 실패한 시도는 좌석 UPDATE 를
            // 아예 보내지 않는다. 롤백할 좌석 변경이 생기지 않는다는 뜻이기도 하다.
            assertThat(outcome.sql().seatUpdates())
                    .as("A 1회 + B 성공 1회. 실패한 시도는 좌석을 건드리지 않았다")
                    .isEqualTo(2);
            assertThat(outcome.sql().quotaUpdates())
                    .as("A 1회 + B 1회")
                    .isEqualTo(2);
            assertThat(outcome.reclaimed())
                    .as("A 를 두 번 회수했다면 5 를 넘었을 것이다")
                    .isEqualTo(5);
            assertThat(quota.heldSeats(USER_A.value(), CHUSEOK)).isZero();
            assertThat(quota.heldSeats(USER_B.value(), CHUSEOK)).isZero();
        }

        /**
         * D — 재시도마다 새 트랜잭션이다.
         *
         * <p>같은 트랜잭션에서 재시도하면 첫 시도의 롤백 표시가 남아 두 번째 시도가
         * 커밋되지 않는다. 그래서 <b>이전 트랜잭션이 끝난 뒤에 다음 시도가 시작되는지</b>를
         * {@code TransactionSynchronization} 으로 관찰한다.
         */
        @Test
        void D_재시도마다_새_트랜잭션에서_시작한다() {
            List<SeatId> seats = holdSeats(USER_A, 3, "A");
            expireNow(seats);

            List<String> timeline = new java.util.ArrayList<>();
            AtomicInteger backoffCalls = new AtomicInteger();

            Task2gfExpirySweeper sweeper = new Task2gfExpirySweeper(
                    dataSource(), expiryRepository, quota, TransactionMode.PER_USER_GROUP,
                    attempt -> {
                        backoffCalls.incrementAndGet();
                        // ★ 대기 시점에는 이전 트랜잭션이 이미 끝나 있어야 한다.
                        timeline.add("backoff#" + attempt + " active="
                                + TransactionSynchronizationManager.isActualTransactionActive());
                    },
                    (key, attempt) -> {
                        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                                .as("주입 훅은 트랜잭션 안에서 실행돼야 한다")
                                .isTrue();
                        timeline.add("attempt#" + attempt + " begin");
                        TransactionSynchronizationManager.registerSynchronization(
                                new TransactionSynchronization() {
                                    @Override
                                    public void afterCompletion(int status) {
                                        timeline.add("attempt#" + attempt + " completed status="
                                                + (status == STATUS_ROLLED_BACK
                                                        ? "ROLLED_BACK" : "COMMITTED"));
                                    }
                                });
                        if (attempt == 1) {
                            throw lockFailure(
                                    Task2gfFailureClassifier.MYSQL_DEADLOCK, "Deadlock found");
                        }
                    });

            Task2gfSweepOutcome outcome = sweeper.sweep(10);

            assertThat(timeline)
                    .as("★ 실패한 시도가 끝난 뒤에 backoff, 그 뒤에 새 시도가 시작된다")
                    .containsExactly(
                            "attempt#1 begin",
                            "attempt#1 completed status=ROLLED_BACK",
                            "backoff#1 active=false",
                            "attempt#2 begin",
                            "attempt#2 completed status=COMMITTED");
            assertThat(backoffCalls.get()).isEqualTo(1);
            assertThat(outcome.sql().transactions())
                    .as("트랜잭션 2개 — 시도마다 하나씩")
                    .isEqualTo(2);
            assertThat(outcome.succeeded()).containsExactly(java.util.Map.entry(keyOf(USER_A), 3));
        }

        /**
         * ★ E — vendor code 없는 transient rollback 도 재시도되지만 데드락으로 기록되지 않는다.
         *
         * <p>A 와 같은 흐름이되 주입하는 예외만 다르다. 재시도 <b>동작</b>은 같고
         * <b>분류</b>는 달라야 한다는 것이 이 테스트의 요점이다.
         */
        @Test
        void E_vendor_code_없는_transient_rollback_도_재시도된다() {
            List<SeatId> seats = holdSeats(USER_A, 3, "A");
            expireNow(seats);

            AtomicInteger backoffCalls = new AtomicInteger();
            List<FailureKind> classified = new java.util.ArrayList<>();
            Task2gfSweepOutcome outcome = sweeperWith((key, attempt) -> {
                if (attempt == 1) {
                    // vendor code 를 붙이지 않는다 — 드라이버가 롤백만 알린 상황이다.
                    RuntimeException failure = new org.springframework.jdbc.UncategorizedSQLException(
                            "settle", "UPDATE seat_inventory",
                            new SQLTransactionRollbackException("could not serialize access"));
                    classified.add(Task2gfFailureClassifier.classify(failure));
                    throw failure;
                }
            }, backoffCalls).sweep(10);

            assertThat(classified)
                    .as("★ 데드락이 아니라 별도 분류로 기록된다")
                    .containsExactly(FailureKind.TRANSIENT_TRANSACTION_ROLLBACK);
            assertThat(classified).doesNotContain(FailureKind.DEADLOCK);

            assertThat(outcome.totalAttempts()).as("1회 실패 + 1회 성공").isEqualTo(2);
            assertThat(backoffCalls.get()).isEqualTo(1);

            assertThat(outcome.failed()).as("결국 성공했으므로 실패 그룹이 아니다").isEmpty();
            assertThat(outcome.succeeded()).containsExactly(java.util.Map.entry(keyOf(USER_A), 3));
            assertThat(outcome.reclaimed()).isEqualTo(3);
            assertThat(quota.heldSeats(USER_A.value(), CHUSEOK)).isZero();
            assertThat(activeSeatsOf(USER_A)).isZero();
        }

        /**
         * ★ F — vendor code 없는 generic Spring 잠금 예외도 재시도된다.
         *
         * <p>E 와 같은 흐름이되 주입하는 예외 타입만 다르다. 재시도 <b>동작</b>은 같고
         * <b>분류</b>는 timeout 이 아니어야 한다는 것이 요점이다.
         */
        @Test
        void F_vendor_code_없는_generic_잠금_예외도_재시도된다() {
            List<SeatId> seats = holdSeats(USER_A, 3, "A");
            expireNow(seats);

            AtomicInteger backoffCalls = new AtomicInteger();
            List<FailureKind> classified = new java.util.ArrayList<>();
            Task2gfSweepOutcome outcome = sweeperWith((key, attempt) -> {
                if (attempt == 1) {
                    // vendor code 를 붙이지 않는다 — Spring 이 잠금 실패로 번역만 한 상황이다.
                    RuntimeException failure =
                            new CannotAcquireLockException("잠금 실패", new SQLException());
                    classified.add(Task2gfFailureClassifier.classify(failure));
                    throw failure;
                }
            }, backoffCalls).sweep(10);

            assertThat(classified)
                    .as("★ timeout 이 아니라 별도 분류로 기록된다")
                    .containsExactly(FailureKind.TRANSIENT_LOCK_FAILURE);
            assertThat(classified)
                    .doesNotContain(FailureKind.LOCK_WAIT_TIMEOUT, FailureKind.DEADLOCK);

            assertThat(outcome.totalAttempts()).as("1회 실패 + 1회 성공").isEqualTo(2);
            assertThat(backoffCalls.get()).isEqualTo(1);

            assertThat(outcome.failed()).as("결국 성공했으므로 실패 그룹이 아니다").isEmpty();
            assertThat(outcome.succeeded()).containsExactly(java.util.Map.entry(keyOf(USER_A), 3));
            assertThat(outcome.reclaimed()).isEqualTo(3);
            assertThat(quota.heldSeats(USER_A.value(), CHUSEOK)).isZero();
            assertThat(activeSeatsOf(USER_A)).isZero();
        }

        /**
         * ★ 네 일시적 원인이 최종 실패 결과에서 서로 구분된다.
         *
         * <p>모두 재시도 대상이지만 <b>실패로 끝났을 때 남는 이름이 달라야</b>
         * 알람과 지표가 원인을 구분할 수 있다. 이름이 섞이면 데드락 지표나 timeout 지표가
         * 부풀려져 엉뚱한 곳을 고치게 된다.
         */
        @Test
        void 네_일시적_원인이_실패_결과에서_구분된다() {
            record Case(String label, java.util.function.Supplier<RuntimeException> failure,
                    FailureKind expected) { }

            List<Case> cases = List.of(
                    new Case("1213",
                            () -> lockFailure(Task2gfFailureClassifier.MYSQL_DEADLOCK, "Deadlock found"),
                            FailureKind.DEADLOCK),
                    new Case("1205",
                            () -> lockFailure(Task2gfFailureClassifier.MYSQL_LOCK_WAIT_TIMEOUT,
                                    "Lock wait timeout exceeded"),
                            FailureKind.LOCK_WAIT_TIMEOUT),
                    new Case("code 없는 JDBC rollback",
                            () -> new org.springframework.jdbc.UncategorizedSQLException(
                                    "settle", "UPDATE",
                                    new SQLTransactionRollbackException("rolled back")),
                            FailureKind.TRANSIENT_TRANSACTION_ROLLBACK),
                    new Case("code 없는 Spring generic lock",
                            () -> new CannotAcquireLockException("잠금 실패", new SQLException()),
                            FailureKind.TRANSIENT_LOCK_FAILURE));

            List<FailureKind> observed = new java.util.ArrayList<>();
            for (Case testCase : cases) {
                Task2gQuotaCounter.truncate(jdbc());
                jdbc().execute("DELETE FROM seat_inventory");
                List<SeatId> seats = holdSeats(USER_A, 2, "A");
                expireNow(seats);

                AtomicInteger backoffCalls = new AtomicInteger();
                Task2gfSweepOutcome outcome = sweeperWith(
                        (key, attempt) -> {
                            throw testCase.failure().get();
                        }, backoffCalls).sweep(10);

                assertThat(outcome.failed())
                        .as("%s 는 3회 시도 후 %s 로 남아야 한다", testCase.label(), testCase.expected())
                        .singleElement()
                        .satisfies(failure -> {
                            assertThat(failure.kind()).isEqualTo(testCase.expected());
                            assertThat(failure.attempts())
                                    .isEqualTo(Task2gfExpirySweeper.MAX_ATTEMPTS);
                        });
                assertThat(backoffCalls.get()).isEqualTo(2);
                observed.add(outcome.failed().get(0).kind());
            }

            assertThat(observed)
                    .as("★ 네 원인이 서로 다른 이름으로 남는다 — 지표가 섞이지 않는다")
                    .doesNotHaveDuplicates()
                    .containsExactly(
                            FailureKind.DEADLOCK,
                            FailureKind.LOCK_WAIT_TIMEOUT,
                            FailureKind.TRANSIENT_TRANSACTION_ROLLBACK,
                            FailureKind.TRANSIENT_LOCK_FAILURE);
        }

        /** 업무 실패는 루프를 돌지 않는다 — 주입 훅이 한 번만 불린다. */
        @Test
        void 업무_실패는_재시도_루프를_돌지_않는다() {
            List<SeatId> seats = holdSeats(USER_A, 3, "A");
            expireNow(seats);

            AtomicInteger backoffCalls = new AtomicInteger();
            AtomicInteger attemptsSeen = new AtomicInteger();
            Task2gfSweepOutcome outcome = sweeperWith((key, attempt) -> {
                attemptsSeen.incrementAndGet();
                throw new Task2gfExpirySweeper.QuotaDecreaseRejectedException("주입된 업무 실패");
            }, backoffCalls).sweep(10);

            assertThat(attemptsSeen.get()).as("한 번만 시도한다").isEqualTo(1);
            assertThat(backoffCalls.get()).isZero();
            assertThat(outcome.failed()).singleElement().satisfies(failure -> {
                assertThat(failure.kind()).isEqualTo(FailureKind.QUOTA_DECREASE_REJECTED);
                assertThat(failure.attempts()).isEqualTo(1);
            });
        }
    }

    /** 데드락 재현 테스트가 남긴 예외를 삼키지 않았음을 확인하는 보조 검사. */
    @Test
    void 작업_예외는_Future_로_전파된다() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<?> future = pool.submit(() -> {
            throw new IllegalStateException("주입된 실패");
        });
        pool.shutdown();
        assertThat(pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasRootCauseMessage("주입된 실패");
    }
}
