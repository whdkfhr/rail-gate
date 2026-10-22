package com.railgate.reservation.application.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.application.hold.HoldSeatsCommand;
import com.railgate.reservation.application.hold.HoldSeatsResult;
import com.railgate.reservation.application.hold.HoldSeatsService;
import com.railgate.reservation.application.hold.ReleaseHoldCommand;
import com.railgate.reservation.application.hold.ReleaseHoldService;
import com.railgate.reservation.idempotency.HoldRejectionCode;
import com.railgate.reservation.idempotency.IdempotencyKey;
import com.railgate.reservation.idempotency.IdempotencyKeyPort;
import com.railgate.reservation.idempotency.StoredHoldOutcome;
import com.railgate.reservation.idempotency.StoredHoldOutcome.Rejected;
import com.railgate.reservation.idempotency.StoredHoldOutcome.Succeeded;
import com.railgate.reservation.infra.idempotency.JdbcIdempotencyKeyRepository;
import com.railgate.reservation.infra.quota.JdbcUserHoldQuotaRepository;
import com.railgate.reservation.infra.saleevent.JdbcSaleEventScopeRepository;
import com.railgate.reservation.infra.seat.JdbcMultiSeatHoldRepository;
import com.railgate.reservation.seat.SeatId;
import com.railgate.reservation.support.MySqlSpringTestSupport;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ★ Task 2I-C — <b>Spring 이 배선한</b> 멱등 선점 조정자를 실제 MySQL(V1~V7)에서 검증한다.
 *
 * <p>기본은 주입받은 빈이다. 순서 고정과 장애 주입이 필요한 테스트만 같은 빈들(트랜잭션 프록시가 붙은
 * {@link HoldSeatsService}, JDBC 저장소, 트랜잭션 관리자)로 조정자를 하나 더 만들고 <b>본 작업 앞뒤에 테스트 훅</b>을
 * 끼운다 — 운영 코드에 실패 스위치는 없다. 리스 만료는 {@code Thread.sleep} 이 아니라 DB 시각 기준으로
 * {@code lease_until} 을 옮겨 만들고, 경합은 래치로 순서를 고정한다.
 */
@DisplayName("Task 2I-C — IdempotentHoldService (MySQL)")
class IdempotentHoldServiceIntegrationTest extends MySqlSpringTestSupport {

    private static final long SCHEDULE = 7_900L;
    private static final UserId USER_A = new UserId(31L);
    private static final UserId USER_B = new UserId(32L);
    private static final IdempotencyKey KEY = new IdempotencyKey("svc-key-0001");
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final long AWAIT = 10;

    @Autowired
    private IdempotentHoldService service;

    @Autowired
    private HoldSeatsService holdSeats;

    @Autowired
    private ReleaseHoldService releaseHold;

    @Autowired
    private IdempotencyKeyPort keyPort;

    @Autowired
    private DataSourceTransactionManager transactionManager;

    @Autowired
    private MeterRegistry meters;

    private ExecutorService pool;
    private final AtomicInteger attempts = new AtomicInteger();

    @BeforeEach
    void reset() {
        jdbc().execute("DELETE FROM idempotency_key");
        pool = Executors.newFixedThreadPool(4);
        attempts.set(0);
    }

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    // ------------------------------------------------------------------ 픽스처

    private static HoldSeatsCommand command(UserId user, long... seats) {
        return new HoldSeatsCommand(user, Arrays.stream(seats).mapToObj(SeatId::new).toList());
    }

    /** 같은 빈들로 만든 조정자. 본 작업 직전에 {@code before} 를, 직후(같은 TX1 안)에 {@code after} 를 실행한다. */
    private IdempotentHoldService hooked(Runnable before, Runnable after) {
        return hooked(before, after, transactionManager);
    }

    private IdempotentHoldService hooked(Runnable before, Runnable after, DataSourceTransactionManager tm) {
        IdempotentHoldService.HoldExecutor executor = command -> {
            attempts.incrementAndGet();
            before.run();
            HoldSeatsResult result = holdSeats.hold(command);
            after.run();
            return result;
        };
        return new IdempotentHoldService(keyPort, executor, tm, LEASE, new SimpleMeterRegistry());
    }

    private static Runnable pause(CountDownLatch reached, CountDownLatch resume) {
        return () -> {
            reached.countDown();
            await(resume);
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(AWAIT, TimeUnit.SECONDS)) {
                throw new IllegalStateException("래치 대기 시간 초과");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static <T> T get(Future<T> future) throws Exception {
        return future.get(AWAIT, TimeUnit.SECONDS);
    }

    private void expireLease(UserId user, IdempotencyKey key) {
        assertThat(jdbc().update("""
                UPDATE idempotency_key SET lease_until = NOW(3) - INTERVAL 1 SECOND
                 WHERE user_id = ? AND operation = 'HOLD_SEATS' AND idempotency_key = ? AND state = 'IN_PROGRESS'
                """, user.value(), key.value())).as("만료시킬 진행 중 행").isEqualTo(1);
    }

    private Map<String, Object> keyRow(UserId user, IdempotencyKey key) {
        return jdbc().queryForMap("""
                SELECT state, generation, fence, result_type, hold_id,
                       CAST(lease_until IS NOT NULL AND lease_until <= NOW(3) AS SIGNED) AS lease_expired
                  FROM idempotency_key WHERE user_id = ? AND operation = 'HOLD_SEATS' AND idempotency_key = ?
                """, user.value(), key.value());
    }

    private long keyRowCount() {
        return jdbc().queryForObject("SELECT COUNT(*) FROM idempotency_key", Long.class);
    }

    private HoldId seatHold(long seat) {
        String raw = holdIdOf(seat);
        return raw == null ? null : HoldId.of(raw);
    }

    private static HoldId holdIdOf(IdempotentHoldResult result) {
        StoredHoldOutcome outcome = switch (result) {
            case IdempotentHoldResult.Executed executed -> executed.outcome();
            case IdempotentHoldResult.Replayed replayed -> replayed.outcome();
            default -> throw new AssertionError("성공 결과가 없다: " + result);
        };
        return ((Succeeded) outcome).holdId();
    }

    private static IdempotentHoldResult executedRejection(HoldRejectionCode code) {
        return new IdempotentHoldResult.Executed(new Rejected(code));
    }

    // ------------------------------------------------------------------ §1 배선

    @Nested
    @DisplayName("§1 실제 Spring 빈 배선")
    class 배선 {

        @Test
        void 운영_JDBC_저장소와_트랜잭션_프록시가_붙은_선점_서비스가_연결된다() {
            assertThat(AopUtils.getTargetClass(keyPort)).isEqualTo(JdbcIdempotencyKeyRepository.class);
            assertThat(AopUtils.isAopProxy(holdSeats)).as("TX1 에 REQUIRED 로 참여하는 선점 서비스").isTrue();
        }

        @Test
        void 빈으로_실행하면_선점과_성공_기록이_함께_커밋되고_메트릭이_남는다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            double before = meters.counter(IdempotentHoldService.COMMITTED, "outcome", "succeeded").count();

            IdempotentHoldResult result = service.hold(KEY, command(USER_A, a));

            assertThat(result).isInstanceOf(IdempotentHoldResult.Executed.class);
            assertThat(seatHold(a)).isEqualTo(holdIdOf(result));
            assertThat(keyRow(USER_A, KEY)).containsEntry("state", "COMPLETED")
                    .containsEntry("hold_id", holdIdOf(result).asString());
            assertThat(meters.counter(IdempotentHoldService.COMMITTED, "outcome", "succeeded").count())
                    .isEqualTo(before + 1);
        }
    }

    // ------------------------------------------------------------------ §2 재생

    @Nested
    @DisplayName("§2 ★ 재생·정규화·불일치·사용자 격리")
    class 재생 {

        @Test
        void 같은_키_같은_요청은_재생하고_좌석_순서가_달라도_같은_요청이다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long b = insertAvailableSeat(SCHEDULE, "1B");

            HoldId first = holdIdOf(service.hold(KEY, command(USER_A, a, b)));
            IdempotentHoldResult again = service.hold(KEY, command(USER_A, b, a));

            assertThat(again).isEqualTo(new IdempotentHoldResult.Replayed(
                    new Succeeded(first, new com.railgate.reservation.saleevent.SaleEventId(FIXTURE_SALE_EVENT_ID), 2)));
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).as("quota 한 번").isEqualTo(2);
        }

        @Test
        void 같은_키_다른_요청은_거부하고_실행하지_않는다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long c = insertAvailableSeat(SCHEDULE, "1C");
            service.hold(KEY, command(USER_A, a));

            assertThat(service.hold(KEY, command(USER_A, c)))
                    .isEqualTo(new IdempotentHoldResult.FingerprintMismatch());
            assertThat(statusOf(c)).isEqualTo("AVAILABLE");
        }

        @Test
        void 다른_사용자의_같은_키는_결과를_공유하지_않는다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long b = insertAvailableSeat(SCHEDULE, "1B");

            HoldId ofA = holdIdOf(service.hold(KEY, command(USER_A, a)));
            IdempotentHoldResult ofBSameSeat = service.hold(KEY, command(USER_B, a));
            HoldId ofB = holdIdOf(service.hold(new IdempotencyKey("svc-key-0002"), command(USER_B, b)));

            assertThat(ofBSameSeat).as("B 는 A 의 holdId 를 재생받지 않는다")
                    .isEqualTo(executedRejection(HoldRejectionCode.SEAT_UNAVAILABLE));
            assertThat(ofB).isNotEqualTo(ofA);
            assertThat(keyRowCount()).isEqualTo(3);
        }

        /** ★ 성공 뒤 홀드가 풀려도 같은 키는 최초 결과를 재생할 뿐 다시 선점하지 않는다. 재생은 현재 상태가 아니다. */
        @Test
        void 성공_뒤_홀드가_해제돼도_재선점하지_않고_최초_결과를_재생한다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            HoldId first = holdIdOf(service.hold(KEY, command(USER_A, a)));
            releaseHold.release(new ReleaseHoldCommand(first, USER_A));

            IdempotentHoldResult again = service.hold(KEY, command(USER_A, a));

            assertThat(again).isInstanceOf(IdempotentHoldResult.Replayed.class);
            assertThat(holdIdOf(again)).isEqualTo(first);
            assertThat(statusOf(a)).isEqualTo("AVAILABLE");
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).isZero();
        }

        @Test
        void 업무_실패는_저장되고_좌석이_비어도_재생된다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long b = insertAvailableSeat(SCHEDULE, "1B");
            HoldSeatsResult other = holdSeats.hold(command(USER_B, b));

            assertThat(service.hold(KEY, command(USER_A, a, b)))
                    .isEqualTo(executedRejection(HoldRejectionCode.SEAT_UNAVAILABLE));
            assertThat(statusOf(a)).as("부분 선점 없음").isEqualTo("AVAILABLE");
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).as("quota 행도 롤백").isEqualTo(-1);

            releaseHold.release(new ReleaseHoldCommand(other.holdId(), USER_B));
            assertThat(service.hold(KEY, command(USER_A, a, b)))
                    .isEqualTo(new IdempotentHoldResult.Replayed(new Rejected(HoldRejectionCode.SEAT_UNAVAILABLE)));
            assertThat(statusOf(b)).isEqualTo("AVAILABLE");
        }
    }

    // ------------------------------------------------------------------ §3 장애

    @Nested
    @DisplayName("§3 ★ 장애 뒤 인수와 리스 포기")
    class 장애 {

        @Test
        void TX0_뒤_중단되면_리스_동안은_진행_중이고_만료_뒤_인수해_실행한다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            Runnable crash = () -> {
                throw new AssertionError("simulated crash");
            };

            assertThatThrownBy(() -> hooked(crash, () -> { }).hold(KEY, command(USER_A, a)))
                    .isInstanceOf(AssertionError.class);
            assertThat(statusOf(a)).isEqualTo("AVAILABLE");
            assertThat(service.hold(KEY, command(USER_A, a))).isEqualTo(new IdempotentHoldResult.InProgress());

            expireLease(USER_A, KEY);
            IdempotentHoldResult taken = service.hold(KEY, command(USER_A, a));

            assertThat(taken).isInstanceOf(IdempotentHoldResult.Executed.class);
            assertThat(keyRow(USER_A, KEY)).containsEntry("fence", 2L).containsEntry("state", "COMPLETED");
            assertThat(seatHold(a)).isEqualTo(holdIdOf(taken));
        }

        @Test
        void 재시도_가능한_장애는_TX1_을_롤백하고_리스를_포기해_즉시_인수된다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            CannotAcquireLockException failure = new CannotAcquireLockException("주입된 잠금 대기 초과");

            assertThatThrownBy(() -> hooked(() -> { }, () -> {
                throw failure;
            }).hold(KEY, command(USER_A, a))).isSameAs(failure);
            assertThat(statusOf(a)).isEqualTo("AVAILABLE");
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).isEqualTo(-1);
            assertThat(keyRow(USER_A, KEY)).containsEntry("state", "IN_PROGRESS").containsEntry("lease_expired", 1L);

            assertThat(service.hold(KEY, command(USER_A, a))).isInstanceOf(IdempotentHoldResult.Executed.class);
            assertThat(keyRow(USER_A, KEY)).containsEntry("fence", 2L);
        }

        /**
         * ★ 커밋 결과 불명 — 트랜잭션 관리자 데코레이터가 TX1 의 COMMIT 을 <b>실행한 뒤</b> 실패를 보고한다.
         * 리스 포기는 완료된 행을 바꾸지 못하고 재요청은 재생한다. 실제 COMMIT 중 연결 단절은 재현하지 않았다.
         */
        @Test
        void 커밋_결과_불명이어도_완료된_행은_포기로_바뀌지_않는다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            UnknownCommitTransactionManager unknown = new UnknownCommitTransactionManager(dataSource());

            assertThatThrownBy(() -> hooked(() -> { }, unknown::armForCurrentTransaction, unknown)
                    .hold(KEY, command(USER_A, a))).isInstanceOf(TransactionSystemException.class);

            HoldId committed = seatHold(a);
            assertThat(committed).as("TX1 은 실제로 커밋됐다").isNotNull();
            assertThat(keyRow(USER_A, KEY)).containsEntry("state", "COMPLETED")
                    .containsEntry("hold_id", committed.asString()).containsEntry("fence", 1L);
            assertThat(holdIdOf(service.hold(KEY, command(USER_A, a)))).isEqualTo(committed);
        }
    }

    /** COMMIT 을 실행한 뒤 호출자에게는 실패로 보고하는 관리자. 무장한 트랜잭션 한 번에만 동작한다. */
    static final class UnknownCommitTransactionManager extends DataSourceTransactionManager {

        private static final long serialVersionUID = 1L;
        private final transient AtomicBoolean armed = new AtomicBoolean();

        UnknownCommitTransactionManager(DataSource dataSource) {
            super(dataSource);
        }

        void armForCurrentTransaction() {
            armed.set(true);
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            super.doCommit(status);
            if (armed.compareAndSet(true, false)) {
                throw new TransactionSystemException("주입된 커밋 결과 불명 (COMMIT 은 실행됨)");
            }
        }
    }

    // ------------------------------------------------------------------ §4 동시

    @Nested
    @DisplayName("§4 ★ 같은 키 동시 요청")
    class 동시 {

        @Test
        void 실행은_한_번이고_나머지는_진행_중_또는_재생이며_예외가_없다() throws Exception {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long b = insertAvailableSeat(SCHEDULE, "1B");
            int threads = 16;
            IdempotentHoldService counted = hooked(() -> { }, () -> { });
            ExecutorService many = Executors.newFixedThreadPool(threads);
            try {
                CountDownLatch ready = new CountDownLatch(threads);
                CountDownLatch go = new CountDownLatch(1);
                List<Future<IdempotentHoldResult>> futures = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    Callable<IdempotentHoldResult> call = () -> {
                        ready.countDown();
                        await(go);
                        return counted.hold(KEY, command(USER_A, b, a));
                    };
                    futures.add(many.submit(call));
                }
                await(ready);
                go.countDown();

                List<IdempotentHoldResult> results = new ArrayList<>();
                for (Future<IdempotentHoldResult> future : futures) {
                    results.add(get(future)); // 예외가 하나라도 있으면 여기서 실패한다
                }
                assertThat(attempts).as("본 작업 실행 시도").hasValue(1);
                List<IdempotentHoldResult> executed = results.stream()
                        .filter(IdempotentHoldResult.Executed.class::isInstance).toList();
                assertThat(executed).hasSize(1);
                HoldId winner = holdIdOf(executed.getFirst());
                assertThat(results).allSatisfy(r -> {
                    if (r instanceof IdempotentHoldResult.Replayed) {
                        assertThat(holdIdOf(r)).isEqualTo(winner);
                    } else if (!(r instanceof IdempotentHoldResult.Executed)) {
                        assertThat(r).isEqualTo(new IdempotentHoldResult.InProgress());
                    }
                });
                assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).isEqualTo(2);
            } finally {
                many.shutdownNow();
            }
        }
    }

    // ------------------------------------------------------------------ §5 소유권

    @Nested
    @DisplayName("§5 ★ 소유권 — 같은 세대 인수, 삭제·재생성 뒤 과거 세대")
    class 소유권 {

        /** 같은 세대 인수: 구 작업자의 성공 기록이 거절되면 그의 좌석·quota 가 롤백되고 새 작업자는 정상 완료한다. */
        @Test
        void 인수당한_구_작업자의_선점은_롤백되고_새_작업자만_커밋한다() throws Exception {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long b = insertAvailableSeat(SCHEDULE, "1B");
            CountDownLatch oldInTx1 = new CountDownLatch(1);
            CountDownLatch resumeOld = new CountDownLatch(1);
            CountDownLatch newInTx1 = new CountDownLatch(1);
            CountDownLatch resumeNew = new CountDownLatch(1);

            Future<IdempotentHoldResult> old = pool.submit(() ->
                    hooked(pause(oldInTx1, resumeOld), () -> { }).hold(KEY, command(USER_A, a, b)));
            await(oldInTx1);
            expireLease(USER_A, KEY);
            Future<IdempotentHoldResult> taker = pool.submit(() ->
                    hooked(pause(newInTx1, resumeNew), () -> { }).hold(KEY, command(USER_A, a, b)));
            await(newInTx1);
            assertThat(keyRow(USER_A, KEY)).as("인수자가 같은 세대 fence 2").containsEntry("fence", 2L);

            resumeOld.countDown();
            assertThat(get(old)).isEqualTo(new IdempotentHoldResult.OwnershipLost());
            assertThat(statusOf(a)).as("구 작업자의 좌석 롤백").isEqualTo("AVAILABLE");
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).as("quota 롤백").isEqualTo(-1);

            resumeNew.countDown();
            HoldId winner = holdIdOf(get(taker));
            assertThat(seatHold(a)).isEqualTo(winner);
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).isEqualTo(2);
            assertThat(holdIdOf(service.hold(KEY, command(USER_A, a, b)))).isEqualTo(winner);
        }

        /**
         * 삭제·재생성 뒤 지연된 과거 세대 작업자 — 성공·업무 실패·장애(포기) 세 갈래 모두 새 세대의 행을 바꾸지 못한다.
         *
         * <p>과거 세대의 행이 완료되고 보존 기간 삭제로 사라진 상황을 직접 SQL 로 만든다 (보존 기간 삭제 작업은 아직 없다).
         */
        @ParameterizedTest
        @ValueSource(strings = {"success", "rejection", "failure"})
        void 과거_세대의_지연된_기록은_새_세대의_행을_바꾸지_못한다(String mode) throws Exception {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long blocker = insertAvailableSeat(SCHEDULE, "9Z");
            CountDownLatch oldInTx1 = new CountDownLatch(1);
            CountDownLatch resumeOld = new CountDownLatch(1);
            long[] oldSeats = "rejection".equals(mode) ? new long[] {a, blocker} : new long[] {a};
            if ("rejection".equals(mode)) {
                holdSeats.hold(command(USER_B, blocker));
            }
            Runnable oldAfter = "failure".equals(mode)
                    ? () -> {
                        throw new CannotAcquireLockException("주입된 장애");
                    }
                    : () -> { };

            Future<IdempotentHoldResult> old = pool.submit(() ->
                    hooked(pause(oldInTx1, resumeOld), oldAfter).hold(KEY, command(USER_A, oldSeats)));
            await(oldInTx1);
            String oldGeneration = (String) keyRow(USER_A, KEY).get("generation");

            // 과거 세대 행이 완료됐다가 보존 기간 정리로 지워졌다.
            jdbc().update("""
                    UPDATE idempotency_key SET state = 'COMPLETED', lease_until = NULL, completed_at = NOW(3),
                           result_type = 'SEAT_UNAVAILABLE'
                     WHERE user_id = ? AND idempotency_key = ?
                    """, USER_A.value(), KEY.value());
            jdbc().update("DELETE FROM idempotency_key WHERE user_id = ? AND idempotency_key = ?",
                    USER_A.value(), KEY.value());

            // 같은 키로 새 요청이 행을 다시 만들고 실행 중이다 (세대 2, fence 1).
            CountDownLatch newInTx1 = new CountDownLatch(1);
            CountDownLatch resumeNew = new CountDownLatch(1);
            Future<IdempotentHoldResult> fresh = pool.submit(() ->
                    hooked(pause(newInTx1, resumeNew), () -> { }).hold(KEY, command(USER_A, oldSeats)));
            await(newInTx1);
            Map<String, Object> freshRow = keyRow(USER_A, KEY);
            assertThat(freshRow.get("generation")).isNotEqualTo(oldGeneration);
            assertThat(freshRow).containsEntry("fence", 1L).containsEntry("state", "IN_PROGRESS");

            resumeOld.countDown();
            if ("failure".equals(mode)) {
                assertThatThrownBy(() -> get(old)).hasCauseInstanceOf(CannotAcquireLockException.class);
            } else {
                assertThat(get(old)).isEqualTo(new IdempotentHoldResult.OwnershipLost());
            }
            assertThat(keyRow(USER_A, KEY)).as("★ 새 세대의 행은 그대로")
                    .containsEntry("generation", freshRow.get("generation"))
                    .containsEntry("state", "IN_PROGRESS")
                    .containsEntry("lease_expired", 0L);
            assertThat(statusOf(a)).as("과거 세대의 선점은 남지 않았다").isEqualTo("AVAILABLE");

            resumeNew.countDown();
            IdempotentHoldResult freshResult = get(fresh);
            if ("rejection".equals(mode)) {
                assertThat(freshResult).isEqualTo(executedRejection(HoldRejectionCode.SEAT_UNAVAILABLE));
            } else {
                assertThat(seatHold(a)).isEqualTo(holdIdOf(freshResult));
            }
        }
    }

    // ------------------------------------------------------------------ §6 경계

    @Nested
    @DisplayName("§6 바깥 트랜잭션과 커넥션")
    class 경계 {

        @Test
        void 바깥_트랜잭션_안에서_부르면_키를_만들기_전에_거부한다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");

            assertThatThrownBy(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(s -> service.hold(KEY, command(USER_A, a))))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(keyRowCount()).isZero();
            assertThat(statusOf(a)).isEqualTo("AVAILABLE");
        }

        /**
         * ★ 커넥션이 하나뿐인 풀에서 성공·재생·업무 실패·장애 포기·인수가 모두 끝난다. TX0·TX1·TX2 가 겹쳐 두 번째
         * 커넥션을 잡는 순간이 있으면 풀 대기 시간 초과로 실패한다.
         */
        @Test
        void 커넥션_하나로_모든_경로가_순차로_끝난다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long b = insertAvailableSeat(SCHEDULE, "1B");
            HikariDataSource single = singleConnectionPool();
            try {
                DataSourceTransactionManager tm = new DataSourceTransactionManager(single);
                HoldSeatsService unproxied = new HoldSeatsService(
                        new JdbcSaleEventScopeRepository(single),
                        new JdbcUserHoldQuotaRepository(single),
                        new JdbcMultiSeatHoldRepository(single, tm, Duration.ofMinutes(5)));
                JdbcIdempotencyKeyRepository keys = new JdbcIdempotencyKeyRepository(single);
                IdempotentHoldService singleService =
                        new IdempotentHoldService(keys, unproxied, tm, LEASE, new SimpleMeterRegistry());
                CannotAcquireLockException failure = new CannotAcquireLockException("주입");
                IdempotentHoldService failing = new IdempotentHoldService(keys, command -> {
                    unproxied.hold(command);
                    throw failure;
                }, tm, LEASE, new SimpleMeterRegistry());

                HoldId held = holdIdOf(singleService.hold(KEY, command(USER_A, a)));
                assertThat(holdIdOf(singleService.hold(KEY, command(USER_A, a)))).isEqualTo(held);
                assertThat(singleService.hold(new IdempotencyKey("svc-key-0003"), command(USER_B, a)))
                        .isEqualTo(executedRejection(HoldRejectionCode.SEAT_UNAVAILABLE));

                IdempotencyKey retried = new IdempotencyKey("svc-key-0004");
                assertThatThrownBy(() -> failing.hold(retried, command(USER_B, b))).isSameAs(failure);
                assertThat(singleService.hold(retried, command(USER_B, b)))
                        .as("포기된 리스를 바로 인수").isInstanceOf(IdempotentHoldResult.Executed.class);
                assertThat(keyRow(USER_B, retried)).containsEntry("fence", 2L);
                assertThat(statusOf(b)).isEqualTo("HELD");
            } finally {
                single.close();
            }
        }

        private HikariDataSource singleConnectionPool() {
            HikariDataSource app = (HikariDataSource) dataSource();
            HikariDataSource single = new HikariDataSource();
            single.setJdbcUrl(app.getJdbcUrl());
            single.setUsername(app.getUsername());
            single.setPassword(app.getPassword());
            single.setConnectionInitSql("SET SESSION innodb_lock_wait_timeout = 3");
            single.setMaximumPoolSize(1);
            single.setConnectionTimeout(2_000);
            return single;
        }
    }
}
