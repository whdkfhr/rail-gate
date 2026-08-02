package com.railgate.reservation.infra.seat;

import static org.assertj.core.api.Assertions.assertThat;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.infra.MySqlTestSupport;
import com.railgate.reservation.seat.SeatId;
import java.time.Duration;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * ★ 조건부 UPDATE 가 실제로 무언가를 막고 있음을 A/B 로 보인다 (CLAUDE.md 규칙 27).
 *
 * <p><b>재현 방식.</b> {@code Thread.sleep} 을 쓰지 않는다.
 * {@link CyclicBarrier} 로 <b>전원이 상태를 읽은 뒤에야</b> UPDATE 를 시작하게 만든다.
 * 레이스 창이 타이밍이 아니라 제어 흐름으로 벌어지므로, 머신 부하와 무관하게
 * 같은 결과가 나온다.
 *
 * <pre>
 * 순진한 구현                              조건부 UPDATE
 * ───────────────────────────────────    ───────────────────────────────
 * SELECT status  (전원 AVAILABLE 관측)     (읽기 없음)
 *        │                                       │
 *   ◀ barrier ▶  전원 대기                  ◀ barrier ▶  전원 대기
 *        │                                       │
 * UPDATE (조건 없음) → N명 성공            UPDATE ... WHERE status='AVAILABLE'
 *                                          → affected_rows 로 1명만 성공
 * </pre>
 *
 * <p>핵심은 <b>조건부 UPDATE 에는 벌릴 창이 없다</b>는 것이다.
 * 검사와 갱신이 한 문장이므로 그 사이에 배리어를 넣을 지점 자체가 존재하지 않는다.
 */
@Timeout(120)
@DisplayName("좌석 선점 경쟁 조건 재현 - 순진한 구현 vs 조건부 UPDATE")
class SeatHoldRaceReproductionTest extends MySqlTestSupport {

    /**
     * 배리어 참가자 수. 커넥션 풀 크기보다 작아야 한다.
     * JdbcTemplate 이 문장마다 커넥션을 반납하므로 배리어 대기 중에는 커넥션을 쥐지 않지만,
     * 여유를 두어 풀 고갈로 인한 오탐을 배제한다.
     */
    private static final int CONTENDERS = 8;

    private static final long SCHEDULE_ID = 1L;
    private static final Duration HOLD_DURATION = Duration.ofMinutes(5);

    private long seatId;

    @BeforeEach
    void setUp() {
        assertThat(CONTENDERS).isLessThan(poolSize());
        seatId = insertAvailableSeat(SCHEDULE_ID, "12A");
    }

    @Test
    @DisplayName("A: SELECT 후 UPDATE 하면 초과 선점이 발생한다")
    void 순진한_구현은_동시_요청에서_여러_명이_선점에_성공한다() throws Exception {
        NaiveSeatHoldRepository naive = new NaiveSeatHoldRepository(
                dataSource(), HOLD_DURATION.toSeconds());
        CyclicBarrier afterRead = new CyclicBarrier(CONTENDERS);

        Outcome outcome = race((seatId, holdId, userId) ->
                naive.hold(seatId, holdId, userId, () -> awaitQuietly(afterRead)));

        assertThat(outcome.errors()).as("시스템 오류 없이 재현되어야 한다").isEmpty();
        assertThat(outcome.succeeded())
                .as("전원이 AVAILABLE 을 관측한 뒤 UPDATE 하므로 전원이 성공한다")
                .isEqualTo(CONTENDERS);

        // 초과 선점의 실체: 성공 응답은 8건인데 좌석 행은 하나이고 hold_id 는 마지막 승자 것이다.
        // 즉 7명이 "선점 성공" 을 받고도 좌석을 갖지 못했다.
        assertThat(outcome.recordedHoldIds())
                .as("DB 에 남은 hold_id 는 하나뿐이다")
                .hasSize(1);
        assertThat(outcome.succeeded())
                .as("성공 응답 수가 실제 보유자 수보다 많다 = 초과 선점")
                .isGreaterThan(1);
    }

    @Test
    @DisplayName("B: 조건부 UPDATE 는 같은 조건에서 1명만 성공한다")
    void 조건부_UPDATE_는_같은_조건에서도_정확히_1명만_성공한다() throws Exception {
        JdbcSeatHoldRepository repository =
                new JdbcSeatHoldRepository(dataSource(), HOLD_DURATION);
        CyclicBarrier beforeUpdate = new CyclicBarrier(CONTENDERS);

        Outcome outcome = race((seatId, holdId, userId) -> {
            // 순진한 구현과 동일하게 전원을 같은 지점에 모아 놓고 출발시킨다.
            // 차이는 창을 벌릴 지점이 없다는 것뿐이다.
            awaitQuietly(beforeUpdate);
            return repository.hold(seatId, holdId, userId);
        });

        assertThat(outcome.errors()).isEmpty();
        assertThat(outcome.succeeded()).as("성공").isEqualTo(1);
        assertThat(outcome.contended()).as("경합 실패 (정상)").isEqualTo(CONTENDERS - 1);
        assertThat(outcome.recordedHoldIds()).hasSize(1);
    }

    @Test
    @DisplayName("C: 두 구현의 차이는 성공 응답 수와 실제 보유자 수의 일치 여부다")
    void 조건부_UPDATE_만_성공_응답과_실제_보유자가_일치한다() throws Exception {
        NaiveSeatHoldRepository naive = new NaiveSeatHoldRepository(
                dataSource(), HOLD_DURATION.toSeconds());
        CyclicBarrier naiveBarrier = new CyclicBarrier(CONTENDERS);
        Outcome naiveOutcome = race((seatId, holdId, userId) ->
                naive.hold(seatId, holdId, userId, () -> awaitQuietly(naiveBarrier)));

        jdbc().update(
                "UPDATE seat_inventory SET status='AVAILABLE', hold_id=NULL, held_by=NULL, "
                        + "held_at=NULL, expires_at=NULL WHERE id = ?", seatId);

        JdbcSeatHoldRepository repository =
                new JdbcSeatHoldRepository(dataSource(), HOLD_DURATION);
        CyclicBarrier casBarrier = new CyclicBarrier(CONTENDERS);
        Outcome casOutcome = race((seatId, holdId, userId) -> {
            awaitQuietly(casBarrier);
            return repository.hold(seatId, holdId, userId);
        });

        assertThat(naiveOutcome.succeeded()).isNotEqualTo(naiveOutcome.recordedHoldIds().size());
        assertThat(casOutcome.succeeded()).isEqualTo(casOutcome.recordedHoldIds().size());
    }

    // ------------------------------------------------------------------

    @FunctionalInterface
    private interface HoldAttempt {
        SeatHoldOutcome apply(SeatId seatId, HoldId holdId, UserId userId);
    }

    private Outcome race(HoldAttempt attempt) throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(CONTENDERS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CONTENDERS);

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger contended = new AtomicInteger();
        List<String> errors = new CopyOnWriteArrayList<>();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < CONTENDERS; i++) {
                long userId = i + 1L;
                HoldId holdId = HoldId.newId();

                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        SeatHoldOutcome result =
                                attempt.apply(new SeatId(seatId), holdId, new UserId(userId));
                        if (result == SeatHoldOutcome.HELD) {
                            succeeded.incrementAndGet();
                        } else {
                            contended.incrementAndGet();
                        }
                    } catch (Exception e) {
                        errors.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        }

        List<String> recorded = jdbc().queryForList(
                "SELECT hold_id FROM seat_inventory WHERE id = ? AND hold_id IS NOT NULL",
                String.class, seatId);

        return new Outcome(succeeded.get(), contended.get(), errors, recorded);
    }

    private static void awaitQuietly(CyclicBarrier barrier) {
        try {
            barrier.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("배리어 대기 중 인터럽트", e);
        } catch (BrokenBarrierException | java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("배리어 실패", e);
        }
    }

    private record Outcome(
            int succeeded, int contended, List<String> errors, List<String> recordedHoldIds) {
    }
}
