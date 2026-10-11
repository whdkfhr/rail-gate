package com.railgate.reservation.application.hold.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.application.hold.HoldSeatsCommand;
import com.railgate.reservation.application.hold.HoldSeatsResult;
import com.railgate.reservation.application.hold.HoldSeatsService;
import com.railgate.reservation.application.hold.ReleaseHoldCommand;
import com.railgate.reservation.application.hold.ReleaseHoldService;
import com.railgate.reservation.application.hold.idempotency.StoredHoldOutcome.Rejected;
import com.railgate.reservation.application.hold.idempotency.StoredHoldOutcome.RejectionCode;
import com.railgate.reservation.application.hold.idempotency.StoredHoldOutcome.Succeeded;
import com.railgate.reservation.application.hold.idempotency.Task2iaFaultPoints.Point;
import com.railgate.reservation.application.hold.idempotency.Task2iaFaultPoints.SimulatedCrash;
import com.railgate.reservation.infra.quota.JdbcUserHoldQuotaRepository;
import com.railgate.reservation.infra.saleevent.JdbcSaleEventScopeRepository;
import com.railgate.reservation.infra.seat.JdbcMultiSeatHoldRepository;
import com.railgate.reservation.seat.SeatId;
import com.railgate.reservation.support.MySqlSpringTestSupport;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ★ Task 2I-A — 선점 멱등성의 <b>장애 구간</b>을 실제 MySQL 에서 재현한다.
 *
 * <p>테스트 전용 테이블 {@code task2ia_idempotency_key} 와 테스트 전용 구현 두 개를 쓴다.
 * 본 작업은 <b>Spring 이 배선한 실제 {@link HoldSeatsService}</b> 다 (§5 의 풀 크기 1 실험만 예외).
 *
 * <ul>
 *   <li>§1 <b>RED 대조군</b> — 규칙 16 을 글자 그대로 구현하고 fence 를 TX2 에서만 확인하는
 *       {@link Task2iaNaiveIdempotentHold} 로 결과 유실과 중복 실행을 재현한다</li>
 *   <li>§2~§5 <b>선택 구현</b> — 성공 결과를 TX1 에서 fence 조건부로 기록하는
 *       {@link Task2iaAtomicCompletionIdempotentHold}</li>
 * </ul>
 *
 * <p>순서는 래치와 테스트 전용 실패 주입({@link Task2iaFaultPoints})으로 고정한다. 리스 만료는
 * {@code Thread.sleep} 이 아니라 <b>키 행의 {@code lease_until} 을 DB 시각 기준 과거로 옮겨</b> 만든다.
 * 만료 판정은 언제나 DB 의 {@code NOW(3)} 이다.
 */
@DisplayName("Task 2I-A — 선점 멱등성 장애 구간 (MySQL)")
class HoldIdempotencyFailureWindowTest extends MySqlSpringTestSupport {

    private static final long SCHEDULE = 7_700L;
    private static final UserId USER_A = new UserId(11L);
    private static final UserId USER_B = new UserId(22L);
    private static final IdempotencyKey KEY = new IdempotencyKey("retry-key-0001");
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final long AWAIT_SECONDS = 10;

    @Autowired
    private HoldSeatsService holdSeats;

    @Autowired
    private ReleaseHoldService releaseHold;

    @Autowired
    private DataSourceTransactionManager transactionManager;

    private Task2iaIdempotencyStore store;
    private final AtomicInteger executions = new AtomicInteger();
    private ExecutorService pool;

    @BeforeEach
    void prepareIdempotencyTable() {
        Task2iaIdempotencyStore.createTableIfAbsent(jdbc());
        jdbc().execute("DELETE FROM " + Task2iaIdempotencyStore.TABLE);
        store = new Task2iaIdempotencyStore(dataSource());
        executions.set(0);
        pool = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void shutdownPool() {
        pool.shutdownNow();
    }

    // ------------------------------------------------------------------ 픽스처

    private Task2iaNaiveIdempotentHold naive(Task2iaFaultPoints faults) {
        return new Task2iaNaiveIdempotentHold(holdSeats, store, transactionManager, LEASE, counting(faults));
    }

    private Task2iaAtomicCompletionIdempotentHold atomic(Task2iaFaultPoints faults) {
        return new Task2iaAtomicCompletionIdempotentHold(
                holdSeats, store, transactionManager, LEASE, counting(faults));
    }

    /** 본 작업(TX1) 진입 횟수를 센다. 상태 변경 횟수가 아니라 <b>실행 시도</b> 횟수다. */
    private Task2iaFaultPoints counting(Task2iaFaultPoints delegate) {
        return point -> {
            if (point == Point.TX1_BEFORE_WORK) {
                executions.incrementAndGet();
            }
            delegate.at(point);
        };
    }

    private static Task2iaFaultPoints crashAt(Point target) {
        return point -> {
            if (point == target) {
                throw new SimulatedCrash(point);
            }
        };
    }

    private static Task2iaFaultPoints failAt(Point target, RuntimeException failure) {
        return point -> {
            if (point == target) {
                throw failure;
            }
        };
    }

    /** 그 지점에 도달하면 알리고, 풀어 줄 때까지 기다린다. */
    private static Task2iaFaultPoints pauseAt(Point target, CountDownLatch reached, CountDownLatch resume) {
        return point -> {
            if (point == target) {
                reached.countDown();
                await(resume);
            }
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(AWAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("래치 대기 시간 초과");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static <T> T get(Future<T> future) throws Exception {
        return future.get(AWAIT_SECONDS, TimeUnit.SECONDS);
    }

    private static HoldSeatsCommand command(UserId user, long... seats) {
        return new HoldSeatsCommand(user, Arrays.stream(seats).mapToObj(SeatId::new).toList());
    }

    /** 리스를 DB 시각 기준 과거로 옮긴다. 시간을 기다리지 않고 "리스 만료" 를 만든다. */
    private void expireLease(UserId user, IdempotencyKey key) {
        int updated = jdbc().update("""
                UPDATE task2ia_idempotency_key
                   SET lease_until = NOW(3) - INTERVAL 1 SECOND
                 WHERE user_id = ? AND operation = 'HOLD_SEATS' AND idempotency_key = ?
                   AND state = 'IN_PROGRESS'
                """, user.value(), key.value());
        assertThat(updated).as("만료시킬 진행 중 키가 있어야 한다").isEqualTo(1);
    }

    private Map<String, Object> keyRow(UserId user, IdempotencyKey key) {
        return jdbc().queryForMap("""
                SELECT state, fence, result_type, hold_id,
                       CAST(lease_until IS NOT NULL AND lease_until <= NOW(3) AS SIGNED) AS lease_expired
                  FROM task2ia_idempotency_key
                 WHERE user_id = ? AND operation = 'HOLD_SEATS' AND idempotency_key = ?
                """, user.value(), key.value());
    }

    private long keyRowCount() {
        return jdbc().queryForObject("SELECT COUNT(*) FROM task2ia_idempotency_key", Long.class);
    }

    private HoldId seatHoldId(long seat) {
        String raw = holdIdOf(seat);
        return raw == null ? null : HoldId.of(raw);
    }

    private static HoldId holdIdOf(Task2iaHoldResponse response) {
        StoredHoldOutcome outcome = switch (response) {
            case Task2iaHoldResponse.Executed executed -> executed.outcome();
            case Task2iaHoldResponse.Replayed replayed -> replayed.outcome();
            default -> throw new AssertionError("성공 결과가 없다: " + response);
        };
        return ((Succeeded) outcome).holdId();
    }

    private static Task2iaHoldResponse executedRejection(RejectionCode code) {
        return new Task2iaHoldResponse.Executed(new Rejected(code));
    }

    // ------------------------------------------------------------------ §1 RED

    @Nested
    @DisplayName("§1 ★ RED 대조군 — 순진한 3단 트랜잭션 (fence 는 TX2 에서만)")
    class 순진한_구현 {

        /**
         * TX1 커밋 직후 죽으면 키는 IN_PROGRESS 로 남는다. 리스가 만료되면 인수자가 본 작업을 다시
         * 실행하고, 자신의 홀드 때문에 경합 거절을 받아 그것을 "최초 결과" 로 굳힌다.
         * 사용자는 좌석을 잡아 놓고도 영원히 실패를 받는다 — <b>결과 유실</b>.
         */
        @Test
        void TX1_커밋_직후_중단되면_성공_결과가_유실되고_경합_거절로_굳는다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long b = insertAvailableSeat(SCHEDULE, "1B");

            assertThatThrownBy(() -> naive(crashAt(Point.AFTER_TX1_COMMIT)).hold(KEY, command(USER_A, a, b)))
                    .isInstanceOf(SimulatedCrash.class);
            HoldId committed = seatHoldId(a);
            assertThat(committed).as("TX1 은 커밋됐다").isNotNull();
            assertThat(keyRow(USER_A, KEY).get("state")).isEqualTo("IN_PROGRESS");

            assertThat(naive(Task2iaFaultPoints.NONE).hold(KEY, command(USER_A, a, b)))
                    .as("리스가 살아 있는 동안은 진행 중")
                    .isEqualTo(new Task2iaHoldResponse.InProgress());

            expireLease(USER_A, KEY);
            Task2iaHoldResponse retried = naive(Task2iaFaultPoints.NONE).hold(KEY, command(USER_A, a, b));

            assertThat(retried).as("★ 재실행이 자기 홀드에 막혀 거절된다")
                    .isEqualTo(executedRejection(RejectionCode.SEAT_UNAVAILABLE));
            assertThat(executions.get()).as("★ 본 작업이 두 번 실행됐다").isEqualTo(2);
            assertThat(keyRow(USER_A, KEY))
                    .as("★ 저장된 '최초 결과' 가 실제 결과(선점 성공)와 다르다")
                    .containsEntry("state", "COMPLETED")
                    .containsEntry("result_type", "SEAT_UNAVAILABLE");
            assertThat(seatHoldId(a)).as("좌석은 아무도 holdId 를 모르는 홀드가 쥐고 있다").isEqualTo(committed);
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).isEqualTo(2);
        }

        /**
         * 같은 조건에서 첫 홀드가 그사이 풀리면(해제·만료) 재실행이 <b>다시 선점을 커밋한다</b>.
         * 한 키에 상태 변경이 두 번 — I-18 위반. 좌석 CAS 는 같은 좌석이 이미 잡혀 있을 때만 막는다.
         */
        @Test
        void TX1_커밋_직후_중단되고_홀드가_풀리면_재실행이_다시_선점한다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long b = insertAvailableSeat(SCHEDULE, "1B");

            assertThatThrownBy(() -> naive(crashAt(Point.AFTER_TX1_COMMIT)).hold(KEY, command(USER_A, a, b)))
                    .isInstanceOf(SimulatedCrash.class);
            HoldId first = seatHoldId(a);
            // 해제·만료 배치가 첫 홀드를 회수한 상황. ReleaseHoldService 로 quota 까지 정합하게 만든다.
            assertThat(releaseHold.release(new ReleaseHoldCommand(first, USER_A)).releasedSeats()).isEqualTo(2);

            expireLease(USER_A, KEY);
            Task2iaHoldResponse retried = naive(Task2iaFaultPoints.NONE).hold(KEY, command(USER_A, a, b));

            HoldId second = holdIdOf(retried);
            assertThat(second).as("★ 같은 키로 두 번째 선점이 커밋됐다").isNotEqualTo(first);
            assertThat(seatHoldId(a)).isEqualTo(second);
            assertThat(executions.get()).isEqualTo(2);
        }

        /**
         * ★ fence 를 TX2 에서만 확인하면 <b>리스를 빼앗긴 구 작업자의 TX1 이 그대로 커밋된다.</b>
         * TX2 는 그 결과를 버릴 뿐 상태 변경을 되돌리지 못하고, 인수자는 구 작업자의 홀드에 막혀 거절을 기록한다.
         */
        @Test
        void TX2_에서만_fence_를_확인하면_구_작업자의_TX1_커밋을_막지_못한다() throws Exception {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long b = insertAvailableSeat(SCHEDULE, "1B");
            CountDownLatch oldInTx1 = new CountDownLatch(1);
            CountDownLatch resumeOld = new CountDownLatch(1);
            CountDownLatch newOwnsKey = new CountDownLatch(1);
            CountDownLatch resumeNew = new CountDownLatch(1);

            Future<Task2iaHoldResponse> old = pool.submit(() ->
                    naive(pauseAt(Point.TX1_BEFORE_WORK, oldInTx1, resumeOld)).hold(KEY, command(USER_A, a, b)));
            await(oldInTx1);
            expireLease(USER_A, KEY);
            Future<Task2iaHoldResponse> taker = pool.submit(() ->
                    naive(pauseAt(Point.AFTER_TX0, newOwnsKey, resumeNew)).hold(KEY, command(USER_A, a, b)));
            await(newOwnsKey);
            assertThat(keyRow(USER_A, KEY).get("fence")).as("인수자가 fence 2 를 가졌다").isEqualTo(2L);

            resumeOld.countDown();
            HoldId stale = holdIdOf(get(old));
            assertThat(seatHoldId(a)).as("★ fence 1 의 TX1 이 인수 이후에 커밋됐다").isEqualTo(stale);
            assertThat(keyRow(USER_A, KEY).get("state")).as("TX2 는 fence 불일치로 기록하지 못했다")
                    .isEqualTo("IN_PROGRESS");

            resumeNew.countDown();
            assertThat(get(taker)).isEqualTo(executedRejection(RejectionCode.SEAT_UNAVAILABLE));
            assertThat(keyRow(USER_A, KEY)).containsEntry("result_type", "SEAT_UNAVAILABLE");
            assertThat(seatHoldId(a)).as("좌석은 결과가 버려진 홀드가 쥐고 있다").isEqualTo(stale);
        }
    }

    // ------------------------------------------------------------------ §2 장애 구간

    @Nested
    @DisplayName("§2 ★ 선택 구현 — 장애 구간")
    class 장애_구간 {

        @Test
        void TX0_이후_중단되면_리스_동안은_진행_중이고_만료_뒤_인수해_한_번만_실행한다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");

            assertThatThrownBy(() -> atomic(crashAt(Point.AFTER_TX0)).hold(KEY, command(USER_A, a)))
                    .isInstanceOf(SimulatedCrash.class);
            assertThat(statusOf(a)).isEqualTo("AVAILABLE");

            assertThat(atomic(Task2iaFaultPoints.NONE).hold(KEY, command(USER_A, a)))
                    .isEqualTo(new Task2iaHoldResponse.InProgress());
            assertThat(executions.get()).isZero();

            expireLease(USER_A, KEY);
            Task2iaHoldResponse taken = atomic(Task2iaFaultPoints.NONE).hold(KEY, command(USER_A, a));

            assertThat(taken).isInstanceOf(Task2iaHoldResponse.Executed.class);
            assertThat(seatHoldId(a)).isEqualTo(holdIdOf(taken));
            assertThat(keyRow(USER_A, KEY)).containsEntry("fence", 2L).containsEntry("state", "COMPLETED");
            assertThat(executions.get()).isEqualTo(1);
        }

        /** TX1 이 롤백된 업무 실패는 TX2 에 저장하고, 좌석이 나중에 비어도 같은 키로는 재실행하지 않는다. */
        @Test
        void TX1_롤백된_업무_실패는_저장되고_재요청에_그대로_재생된다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long b = insertAvailableSeat(SCHEDULE, "1B");
            HoldSeatsResult other = holdSeats.hold(command(USER_B, b));

            assertThat(atomic(Task2iaFaultPoints.NONE).hold(KEY, command(USER_A, a, b)))
                    .isEqualTo(executedRejection(RejectionCode.SEAT_UNAVAILABLE));
            assertThat(statusOf(a)).as("부분 선점 없음").isEqualTo("AVAILABLE");
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).as("quota 행도 롤백").isEqualTo(-1);

            releaseHold.release(new ReleaseHoldCommand(other.holdId(), USER_B));

            assertThat(atomic(Task2iaFaultPoints.NONE).hold(KEY, command(USER_A, a, b)))
                    .as("저장된 업무 실패는 최종이다 — 새로 시도하려면 새 키")
                    .isEqualTo(new Task2iaHoldResponse.Replayed(new Rejected(RejectionCode.SEAT_UNAVAILABLE)));
            assertThat(statusOf(b)).isEqualTo("AVAILABLE");
            assertThat(executions.get()).isEqualTo(1);
        }

        @Test
        void 상한_초과도_업무_실패로_저장된다() {
            long[] four = {
                insertAvailableSeat(SCHEDULE, "1A"), insertAvailableSeat(SCHEDULE, "1B"),
                insertAvailableSeat(SCHEDULE, "1C"), insertAvailableSeat(SCHEDULE, "1D")};
            long fifth = insertAvailableSeat(SCHEDULE, "2A");
            holdSeats.hold(command(USER_A, four));

            assertThat(atomic(Task2iaFaultPoints.NONE).hold(KEY, command(USER_A, fifth)))
                    .isEqualTo(executedRejection(RejectionCode.QUOTA_EXCEEDED));
            assertThat(keyRow(USER_A, KEY)).containsEntry("result_type", "QUOTA_EXCEEDED");
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).isEqualTo(4);
        }

        /**
         * ★ RED §1 첫 번째 시나리오와 같은 장애. 성공 결과가 TX1 과 함께 커밋됐으므로
         * <b>리스 만료를 기다리지 않고</b> 즉시 최초 결과가 재생된다.
         */
        @Test
        void TX1_커밋_직후_중단돼도_재요청은_최초_holdId_를_재생한다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long b = insertAvailableSeat(SCHEDULE, "1B");

            assertThatThrownBy(() -> atomic(crashAt(Point.AFTER_TX1_COMMIT)).hold(KEY, command(USER_A, a, b)))
                    .isInstanceOf(SimulatedCrash.class);
            HoldId committed = seatHoldId(a);

            Task2iaHoldResponse retried = atomic(Task2iaFaultPoints.NONE).hold(KEY, command(USER_A, a, b));

            assertThat(retried).isInstanceOf(Task2iaHoldResponse.Replayed.class);
            assertThat(holdIdOf(retried)).isEqualTo(committed);
            assertThat(executions.get()).as("본 작업은 한 번만").isEqualTo(1);
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).isEqualTo(2);
        }

        /**
         * ★ RED §1 두 번째 시나리오와 같은 장애. 첫 홀드가 풀린 뒤에도 재실행하지 않는다.
         * <b>재생은 현재 상태가 아니다</b> — 돌려준 holdId 는 이미 해제됐을 수 있다.
         */
        @Test
        void TX1_커밋_직후_중단되고_홀드가_풀려도_다시_선점하지_않는다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");

            assertThatThrownBy(() -> atomic(crashAt(Point.AFTER_TX1_COMMIT)).hold(KEY, command(USER_A, a)))
                    .isInstanceOf(SimulatedCrash.class);
            HoldId first = seatHoldId(a);
            releaseHold.release(new ReleaseHoldCommand(first, USER_A));

            Task2iaHoldResponse retried = atomic(Task2iaFaultPoints.NONE).hold(KEY, command(USER_A, a));

            assertThat(holdIdOf(retried)).isEqualTo(first);
            assertThat(statusOf(a)).as("★ 두 번째 선점이 없다").isEqualTo("AVAILABLE");
            assertThat(executions.get()).isEqualTo(1);
        }

        /**
         * TX2(업무 실패 기록)가 실패하면 키는 IN_PROGRESS 로 남는다. 리스가 만료되면 인수해 다시 실행한다.
         * 첫 시도의 TX1 은 롤백됐으므로 <b>재실행이 상태를 두 번 바꾸지 않는다.</b>
         */
        @Test
        void TX2_실패_후_재요청은_리스_만료_뒤_안전하게_재실행된다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long b = insertAvailableSeat(SCHEDULE, "1B");
            HoldSeatsResult other = holdSeats.hold(command(USER_B, b));
            RuntimeException tx2Failure = new IllegalStateException("주입된 TX2 실패");

            assertThatThrownBy(() -> atomic(failAt(Point.IN_TX2, tx2Failure)).hold(KEY, command(USER_A, a, b)))
                    .isSameAs(tx2Failure);
            assertThat(keyRow(USER_A, KEY)).containsEntry("state", "IN_PROGRESS").containsEntry("fence", 1L);
            assertThat(statusOf(a)).as("첫 시도는 아무것도 바꾸지 않았다").isEqualTo("AVAILABLE");
            assertThat(atomic(Task2iaFaultPoints.NONE).hold(KEY, command(USER_A, a, b)))
                    .isEqualTo(new Task2iaHoldResponse.InProgress());

            releaseHold.release(new ReleaseHoldCommand(other.holdId(), USER_B));
            expireLease(USER_A, KEY);
            Task2iaHoldResponse retried = atomic(Task2iaFaultPoints.NONE).hold(KEY, command(USER_A, a, b));

            assertThat(retried).isInstanceOf(Task2iaHoldResponse.Executed.class);
            assertThat(seatHoldId(a)).isEqualTo(holdIdOf(retried));
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).isEqualTo(2);
        }

        /**
         * 완료 기록까지 마친 뒤 커밋 전에 재시도 가능한 장애가 나면 TX1 이 통째로 롤백된다.
         * 결과를 저장하지 않고 리스를 즉시 만료시켜, 다음 요청이 기다리지 않고 인수한다.
         */
        @Test
        void 재시도_가능한_장애는_결과를_저장하지_않고_키를_즉시_넘긴다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            RuntimeException lockFailure = new CannotAcquireLockException("주입된 잠금 대기 초과");

            assertThatThrownBy(() -> atomic(failAt(Point.TX1_BEFORE_COMMIT, lockFailure))
                    .hold(KEY, command(USER_A, a)))
                    .isSameAs(lockFailure);
            assertThat(statusOf(a)).isEqualTo("AVAILABLE");
            assertThat(keyRow(USER_A, KEY))
                    .as("완료 기록도 함께 롤백됐고, 리스는 즉시 만료됐다")
                    .containsEntry("state", "IN_PROGRESS")
                    .containsEntry("lease_expired", 1L);

            Task2iaHoldResponse retried = atomic(Task2iaFaultPoints.NONE).hold(KEY, command(USER_A, a));

            assertThat(retried).isInstanceOf(Task2iaHoldResponse.Executed.class);
            assertThat(keyRow(USER_A, KEY)).containsEntry("fence", 2L);
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------ §3 리스 인수

    @Nested
    @DisplayName("§3 ★ 선택 구현 — 살아 있는 구 작업자와 인수자")
    class 리스_인수 {

        /** §1 세 번째 시나리오와 같은 교차. fence 를 TX1 에서 확인하면 구 작업자의 선점이 롤백된다. */
        @Test
        void 리스를_빼앗긴_구_작업자의_TX1_은_롤백되고_인수자만_커밋한다() throws Exception {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long b = insertAvailableSeat(SCHEDULE, "1B");
            CountDownLatch oldInTx1 = new CountDownLatch(1);
            CountDownLatch resumeOld = new CountDownLatch(1);
            CountDownLatch newOwnsKey = new CountDownLatch(1);
            CountDownLatch resumeNew = new CountDownLatch(1);

            Future<Task2iaHoldResponse> old = pool.submit(() ->
                    atomic(pauseAt(Point.TX1_BEFORE_WORK, oldInTx1, resumeOld)).hold(KEY, command(USER_A, a, b)));
            await(oldInTx1);
            expireLease(USER_A, KEY);
            Future<Task2iaHoldResponse> taker = pool.submit(() ->
                    atomic(pauseAt(Point.AFTER_TX0, newOwnsKey, resumeNew)).hold(KEY, command(USER_A, a, b)));
            await(newOwnsKey);

            resumeOld.countDown();
            assertThat(get(old)).isEqualTo(new Task2iaHoldResponse.Superseded());
            assertThat(statusOf(a)).as("★ 구 작업자의 선점은 남지 않았다").isEqualTo("AVAILABLE");
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).as("quota 도 롤백").isEqualTo(-1);

            resumeNew.countDown();
            HoldId winner = holdIdOf(get(taker));
            assertThat(seatHoldId(a)).isEqualTo(winner);
            assertThat(keyRow(USER_A, KEY)).containsEntry("fence", 2L).containsEntry("state", "COMPLETED");
            assertThat(holdIdOf(atomic(Task2iaFaultPoints.NONE).hold(KEY, command(USER_A, a, b))))
                    .isEqualTo(winner);
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).isEqualTo(2);
        }

        /** 구 작업자의 늦은 업무 실패 기록이 인수자의 성공 결과를 덮지 않는다. */
        @Test
        void 늦은_결과_기록은_새_결과를_덮지_않는다() throws Exception {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long b = insertAvailableSeat(SCHEDULE, "1B");
            HoldSeatsResult other = holdSeats.hold(command(USER_B, b));
            CountDownLatch oldBeforeTx2 = new CountDownLatch(1);
            CountDownLatch resumeOld = new CountDownLatch(1);

            Future<Task2iaHoldResponse> old = pool.submit(() ->
                    atomic(pauseAt(Point.BEFORE_TX2, oldBeforeTx2, resumeOld)).hold(KEY, command(USER_A, a, b)));
            await(oldBeforeTx2);
            releaseHold.release(new ReleaseHoldCommand(other.holdId(), USER_B));
            expireLease(USER_A, KEY);
            HoldId winner = holdIdOf(atomic(Task2iaFaultPoints.NONE).hold(KEY, command(USER_A, a, b)));

            resumeOld.countDown();
            assertThat(get(old)).isEqualTo(new Task2iaHoldResponse.Superseded());
            assertThat(keyRow(USER_A, KEY))
                    .containsEntry("result_type", "SUCCEEDED")
                    .containsEntry("hold_id", winner.asString())
                    .containsEntry("fence", 2L);
        }

        /**
         * ★★ PR #28 리뷰 P2 — <b>키 행이 삭제·재생성된 뒤 돌아온 구 작업자</b>는 새 요청의 행을 완료할 수 없다.
         *
         * <p>구 작업자 A 가 TX1 진입 직전에 멈춘 사이: 리스 만료 → B 인수·완료 → B 의 홀드 해제 →
         * 보존 기간 뒤 키 행 삭제 → 같은 키·같은 요청의 새 작업자 C 가 행을 다시 만들고 TX0 직후 멈춘다.
         * 새 행도 fence 1 이라 fence 만 보면 A 와 C 를 구별할 수 없다.
         *
         * <p>A 를 재개하면 A 의 선점은 좌석이 비어 있으므로 성공하지만, 완료 기록이 0 건이어야 TX1 전체
         * (좌석·quota)가 롤백되고 Superseded 가 된다. 그 뒤 C 가 정상 완료해야 한다.
         */
        @Test
        void 키_행이_재생성된_뒤_돌아온_구_작업자는_새_행을_완료하지_못하고_롤백된다() throws Exception {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long b = insertAvailableSeat(SCHEDULE, "1B");
            CountDownLatch oldInTx1 = new CountDownLatch(1);
            CountDownLatch resumeOld = new CountDownLatch(1);
            CountDownLatch newOwnsKey = new CountDownLatch(1);
            CountDownLatch resumeNew = new CountDownLatch(1);

            Future<Task2iaHoldResponse> old = pool.submit(() ->
                    atomic(pauseAt(Point.TX1_BEFORE_WORK, oldInTx1, resumeOld)).hold(KEY, command(USER_A, a, b)));
            await(oldInTx1);
            expireLease(USER_A, KEY);
            HoldId takerHold = holdIdOf(atomic(Task2iaFaultPoints.NONE).hold(KEY, command(USER_A, a, b)));
            releaseHold.release(new ReleaseHoldCommand(takerHold, USER_A));
            jdbc().update("""
                    UPDATE task2ia_idempotency_key SET completed_at = NOW(3) - INTERVAL 2 DAY
                     WHERE user_id = ? AND state = 'COMPLETED'
                    """, USER_A.value());
            assertThat(jdbc().update("""
                    DELETE FROM task2ia_idempotency_key
                     WHERE state = 'COMPLETED' AND completed_at <= NOW(3) - INTERVAL 1 DAY
                    """)).as("보존 기간이 지난 완료 행 삭제").isEqualTo(1);
            Future<Task2iaHoldResponse> recreated = pool.submit(() ->
                    atomic(pauseAt(Point.AFTER_TX0, newOwnsKey, resumeNew)).hold(KEY, command(USER_A, a, b)));
            await(newOwnsKey);
            assertThat(keyRow(USER_A, KEY)).containsEntry("fence", 1L).containsEntry("state", "IN_PROGRESS");

            resumeOld.countDown();
            assertThat(get(old)).as("★ 과거 세대의 작업자").isEqualTo(new Task2iaHoldResponse.Superseded());
            assertThat(statusOf(a)).as("★ 구 작업자의 선점은 롤백됐다").isEqualTo("AVAILABLE");
            assertThat(statusOf(b)).isEqualTo("AVAILABLE");
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).as("해제 뒤 0 그대로").isZero();
            assertThat(keyRow(USER_A, KEY)).containsEntry("state", "IN_PROGRESS").containsEntry("fence", 1L);

            resumeNew.countDown();
            Task2iaHoldResponse fresh = get(recreated);
            assertThat(fresh).isInstanceOf(Task2iaHoldResponse.Executed.class);
            HoldId freshHold = holdIdOf(fresh);
            assertThat(seatHoldId(a)).isEqualTo(freshHold);
            assertThat(keyRow(USER_A, KEY))
                    .containsEntry("state", "COMPLETED")
                    .containsEntry("result_type", "SUCCEEDED")
                    .containsEntry("hold_id", freshHold.asString());
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).isEqualTo(2);
        }

        /**
         * ★ 완료 기록을 쥔 TX1 이 커밋 전이면 키 행이 X 잠금 상태다. 같은 키의 다른 요청은 TX0 의 중복
         * 검사에서 <b>기다렸다가</b> 커밋된 결과를 재생한다 — 그 사이에 인수할 수 없다.
         */
        @Test
        void 완료_기록을_쥔_TX1_뒤의_요청은_잠금을_기다렸다가_재생한다() throws Exception {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            CountDownLatch oldInTx1 = new CountDownLatch(1);
            CountDownLatch resumeOldWork = new CountDownLatch(1);
            CountDownLatch oldBeforeCommit = new CountDownLatch(1);
            CountDownLatch resumeOldCommit = new CountDownLatch(1);
            Task2iaFaultPoints oldFaults = point -> {
                if (point == Point.TX1_BEFORE_WORK) {
                    oldInTx1.countDown();
                    await(resumeOldWork);
                } else if (point == Point.TX1_BEFORE_COMMIT) {
                    oldBeforeCommit.countDown();
                    await(resumeOldCommit);
                }
            };

            Future<Task2iaHoldResponse> old = pool.submit(() -> atomic(oldFaults).hold(KEY, command(USER_A, a)));
            await(oldInTx1);
            expireLease(USER_A, KEY); // 리스는 만료됐지만 구 작업자는 살아 있다.
            resumeOldWork.countDown();
            await(oldBeforeCommit);   // 구 작업자가 키 행을 COMPLETED 로 바꾸고 잠금을 쥐었다.

            Future<Task2iaHoldResponse> waiter = pool.submit(() ->
                    atomic(Task2iaFaultPoints.NONE).hold(KEY, command(USER_A, a)));
            awaitLockWait();
            resumeOldCommit.countDown();

            HoldId committed = holdIdOf(get(old));
            Task2iaHoldResponse waited = get(waiter);
            assertThat(waited).isInstanceOf(Task2iaHoldResponse.Replayed.class);
            assertThat(holdIdOf(waited)).isEqualTo(committed);
            assertThat(executions.get()).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------ §4 동시성·격리

    @Nested
    @DisplayName("§4 ★ 선택 구현 — 동시 요청·불일치·사용자 격리")
    class 동시성과_격리 {

        @Test
        void 같은_키_동시_요청은_한_번만_실행되고_나머지는_진행_중이거나_재생이다() throws Exception {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long b = insertAvailableSeat(SCHEDULE, "1B");
            int threads = 32;
            ExecutorService many = Executors.newFixedThreadPool(threads);
            try {
                CountDownLatch ready = new CountDownLatch(threads);
                CountDownLatch go = new CountDownLatch(1);
                List<Future<Task2iaHoldResponse>> futures = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    Callable<Task2iaHoldResponse> call = () -> {
                        ready.countDown();
                        await(go);
                        return atomic(Task2iaFaultPoints.NONE).hold(KEY, command(USER_A, b, a));
                    };
                    futures.add(many.submit(call));
                }
                await(ready);
                go.countDown();

                List<Task2iaHoldResponse> responses = new ArrayList<>();
                for (Future<Task2iaHoldResponse> future : futures) {
                    responses.add(get(future)); // ★ 예외(=500 후보)가 하나라도 있으면 여기서 실패한다
                }

                assertThat(executions.get()).as("★ 본 작업은 한 번").isEqualTo(1);
                List<Task2iaHoldResponse> executed = responses.stream()
                        .filter(Task2iaHoldResponse.Executed.class::isInstance).toList();
                assertThat(executed).hasSize(1);
                HoldId winner = holdIdOf(executed.getFirst());
                assertThat(responses).allSatisfy(response -> {
                    if (response instanceof Task2iaHoldResponse.Replayed) {
                        assertThat(holdIdOf(response)).isEqualTo(winner);
                    } else if (!(response instanceof Task2iaHoldResponse.Executed)) {
                        assertThat(response).isEqualTo(new Task2iaHoldResponse.InProgress());
                    }
                });
                assertThat(seatHoldId(a)).isEqualTo(winner);
                assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).isEqualTo(2);
            } finally {
                many.shutdownNow();
            }
        }

        @Test
        void 같은_키_다른_요청은_거부하고_좌석_순서만_다르면_재생한다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long b = insertAvailableSeat(SCHEDULE, "1B");
            long c = insertAvailableSeat(SCHEDULE, "1C");

            HoldId first = holdIdOf(atomic(Task2iaFaultPoints.NONE).hold(KEY, command(USER_A, a, b)));

            assertThat(atomic(Task2iaFaultPoints.NONE).hold(KEY, command(USER_A, a, c)))
                    .isEqualTo(new Task2iaHoldResponse.FingerprintMismatch());
            assertThat(statusOf(c)).as("불일치 요청은 실행되지 않는다").isEqualTo("AVAILABLE");
            assertThat(holdIdOf(atomic(Task2iaFaultPoints.NONE).hold(KEY, command(USER_A, b, a))))
                    .isEqualTo(first);
            assertThat(executions.get()).isEqualTo(1);
        }

        @Test
        void 진행_중인_키에_다른_요청을_실으면_인수하지_않고_불일치다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long c = insertAvailableSeat(SCHEDULE, "1C");
            assertThatThrownBy(() -> atomic(crashAt(Point.AFTER_TX0)).hold(KEY, command(USER_A, a)))
                    .isInstanceOf(SimulatedCrash.class);
            expireLease(USER_A, KEY);

            assertThat(atomic(Task2iaFaultPoints.NONE).hold(KEY, command(USER_A, c)))
                    .isEqualTo(new Task2iaHoldResponse.FingerprintMismatch());
            assertThat(keyRow(USER_A, KEY)).containsEntry("fence", 1L);
            assertThat(statusOf(c)).isEqualTo("AVAILABLE");
        }

        @Test
        void 다른_사용자의_같은_키는_결과를_공유하지_않는다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long b = insertAvailableSeat(SCHEDULE, "1B");

            HoldId ofA = holdIdOf(atomic(Task2iaFaultPoints.NONE).hold(KEY, command(USER_A, a)));
            Task2iaHoldResponse ofBSameSeat = atomic(Task2iaFaultPoints.NONE).hold(KEY, command(USER_B, a));
            HoldId ofB = holdIdOf(atomic(Task2iaFaultPoints.NONE)
                    .hold(new IdempotencyKey("retry-key-0002"), command(USER_B, b)));

            assertThat(ofBSameSeat).as("★ B 는 A 의 holdId 를 재생받지 않는다")
                    .isEqualTo(executedRejection(RejectionCode.SEAT_UNAVAILABLE));
            assertThat(ofB).isNotEqualTo(ofA);
            assertThat(holdIdOf(atomic(Task2iaFaultPoints.NONE).hold(KEY, command(USER_A, a)))).isEqualTo(ofA);
            assertThat(keyRowCount()).isEqualTo(3);
        }

        /** 보존 기간이 지나 행이 삭제되면 같은 키는 새 요청이다. 보장 범위는 보존 기간까지다. */
        @Test
        void 보존_기간이_지나_삭제된_키는_새_요청으로_실행된다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            atomic(Task2iaFaultPoints.NONE).hold(KEY, command(USER_A, a));
            jdbc().update("DELETE FROM task2ia_idempotency_key WHERE user_id = ?", USER_A.value());

            assertThat(atomic(Task2iaFaultPoints.NONE).hold(KEY, command(USER_A, a)))
                    .as("최초 결과를 더 이상 재생하지 못한다")
                    .isEqualTo(executedRejection(RejectionCode.SEAT_UNAVAILABLE));
            assertThat(executions.get()).isEqualTo(2);
        }
    }

    // ------------------------------------------------------------------ §5 커넥션

    @Nested
    @DisplayName("§5 커넥션 수와 바깥 트랜잭션")
    class 커넥션 {

        @Test
        void 바깥_트랜잭션_안에서_부르면_키를_만들기_전에_거부한다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            TransactionTemplate outer = new TransactionTemplate(transactionManager);

            assertThatThrownBy(() -> outer.executeWithoutResult(status ->
                    atomic(Task2iaFaultPoints.NONE).hold(KEY, command(USER_A, a))))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(keyRowCount()).isZero();
        }

        /**
         * ★ 커넥션이 하나뿐인 풀에서 성공·재생·업무 실패·인수가 모두 끝난다. TX0·TX1·TX2 가 겹쳐 커넥션을
         * 두 개 잡는 순간이 있으면 풀 대기 시간 초과로 실패한다.
         *
         * <p>여기서는 {@link HoldSeatsService} 를 직접 {@code new} 한다. 프록시가 없지만 TX1 의
         * {@link TransactionTemplate} 이 같은 DataSource 에 트랜잭션을 열어 두므로 저장소들이 그 커넥션에
         * 참여한다 — quota 저장소의 참여 검사도 통과한다.
         */
        @Test
        void 커넥션_하나로_모든_경로가_끝난다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long b = insertAvailableSeat(SCHEDULE, "1B");
            HikariDataSource single = singleConnectionPool();
            try {
                DataSourceTransactionManager singleTm = new DataSourceTransactionManager(single);
                HoldSeatsService unproxied = new HoldSeatsService(
                        new JdbcSaleEventScopeRepository(single),
                        new JdbcUserHoldQuotaRepository(single),
                        new JdbcMultiSeatHoldRepository(single, singleTm, Duration.ofMinutes(5)));
                Task2iaIdempotencyStore singleStore = new Task2iaIdempotencyStore(single);
                Task2iaAtomicCompletionIdempotentHold service = new Task2iaAtomicCompletionIdempotentHold(
                        unproxied, singleStore, singleTm, LEASE, counting(Task2iaFaultPoints.NONE));
                Task2iaAtomicCompletionIdempotentHold crashing = new Task2iaAtomicCompletionIdempotentHold(
                        unproxied, singleStore, singleTm, LEASE, crashAt(Point.AFTER_TX0));

                HoldId held = holdIdOf(service.hold(KEY, command(USER_A, a)));
                assertThat(holdIdOf(service.hold(KEY, command(USER_A, a)))).isEqualTo(held);
                assertThat(service.hold(new IdempotencyKey("retry-key-0003"), command(USER_B, a)))
                        .isEqualTo(executedRejection(RejectionCode.SEAT_UNAVAILABLE));

                IdempotencyKey crashed = new IdempotencyKey("retry-key-0004");
                assertThatThrownBy(() -> crashing.hold(crashed, command(USER_B, b)))
                        .isInstanceOf(SimulatedCrash.class);
                expireLease(USER_B, crashed);
                assertThat(service.hold(crashed, command(USER_B, b)))
                        .isInstanceOf(Task2iaHoldResponse.Executed.class);

                assertThat(seatHoldId(a)).isEqualTo(held);
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

    // ------------------------------------------------------------------ 잠금 대기 관측

    /**
     * 다른 세션이 InnoDB 잠금을 기다리기 시작할 때까지 관측한다. 시간을 재는 것이 아니라
     * {@code performance_schema.data_lock_waits} 에 대기가 나타나는 것을 확인한다.
     * 이 표는 PROCESS 계열 권한이 필요해 root 로 읽는다 (Testcontainers 는 root 암호를 앱 계정과 같게 둔다).
     */
    private void awaitLockWait() throws SQLException, TimeoutException {
        HikariDataSource app = (HikariDataSource) dataSource();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
        try (Connection root = DriverManager.getConnection(app.getJdbcUrl(), "root", app.getPassword());
             Statement statement = root.createStatement()) {
            while (System.nanoTime() < deadline) {
                try (ResultSet rs = statement.executeQuery(
                        "SELECT COUNT(*) FROM performance_schema.data_lock_waits")) {
                    rs.next();
                    if (rs.getLong(1) > 0) {
                        return;
                    }
                }
                Thread.onSpinWait();
            }
        }
        throw new TimeoutException("잠금 대기가 관측되지 않았다");
    }
}
