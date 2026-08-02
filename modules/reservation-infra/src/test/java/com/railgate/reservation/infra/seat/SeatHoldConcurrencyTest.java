package com.railgate.reservation.infra.seat;

import static org.assertj.core.api.Assertions.assertThat;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.infra.MySqlTestSupport;
import com.railgate.reservation.seat.SeatId;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * ★ 좌석 선점 경쟁 검증 — 1,000명이 하나의 좌석을 동시에 선점하면 정확히 1명만 성공한다.
 *
 * <p><b>범위.</b> 검증 대상은 {@code AVAILABLE → HELD} 경쟁이며 I-8 전체가 아니다.
 * I-8 의 "최종적으로 한 예약에만 확정" 은 확정 단계
 * ({@code PAYING → SOLD ... WHERE hold_id=?}) 가 구현된 뒤에 검증할 수 있다.
 *
 * <p><b>이 테스트가 증명하는 것과 증명하지 못하는 것.</b>
 * INVARIANTS.md 의 3층 주장 구조에서 이 테스트는 <b>결정적 검증</b> 층이다.
 * "위반이 관측되지 않았다" 를 보이는 것이며 "위반이 불가능하다" 의 증명은 아니다.
 * 구조적 논증은 {@code docs/experiments/TASK-002A-seat-hold-race.md} 에 있다.
 *
 * <p><b>동시 출발 방식</b>(CLAUDE.md 규칙 25): {@code CountDownLatch} 로 전원을 한 번에 놓는다.
 * {@code Thread.sleep} 으로 타이밍을 맞추지 않는다. 그렇게 만든 테스트는 머신 부하에 따라
 * 결과가 달라져 아무것도 보장하지 못한다.
 */
@Timeout(120)
@DisplayName("좌석 선점 동시성 - I-8 선점 단계")
class SeatHoldConcurrencyTest extends MySqlTestSupport {

    private static final int CONTENDERS = 1_000;
    private static final long SCHEDULE_ID = 1L;

    private JdbcSeatHoldRepository repository;
    private long seatId;

    @BeforeEach
    void setUp() {
        repository = new JdbcSeatHoldRepository(dataSource(), Duration.ofMinutes(5));
        seatId = insertAvailableSeat(SCHEDULE_ID, "12A");
    }

    @Test
    void 같은_좌석을_1000명이_동시에_선점하면_정확히_1명만_성공한다() throws Exception {
        ContentionResult result = runContention();

        assertThat(result.succeeded()).as("성공한 요청 수").isEqualTo(1);
        assertThat(result.contended()).as("경합 실패 수 (정상 동작)").isEqualTo(CONTENDERS - 1);
        assertThat(result.errors()).as("시스템 오류. 하나라도 있으면 실패다").isEmpty();
    }

    @Test
    void 최종_DB_상태가_실제_승자와_일치한다() throws Exception {
        ContentionResult result = runContention();

        // 성공을 응답받은 사람과 DB 에 기록된 사람이 다르면 정합성이 깨진 것이다.
        assertThat(statusOf(seatId)).isEqualTo("HELD");
        assertThat(holdIdOf(seatId)).isEqualTo(result.winnerHoldId());
        assertThat(heldByOf(seatId)).isEqualTo(result.winnerUserId());
    }

    @Test
    void 경합이_끝난_뒤에도_행은_하나뿐이다() throws Exception {
        runContention();

        Long rows = jdbc().queryForObject(
                "SELECT COUNT(*) FROM seat_inventory WHERE schedule_id = ? AND seat_no = ?",
                Long.class, SCHEDULE_ID, "12A");

        assertThat(rows).as("UNIQUE(schedule_id, seat_no) 가 I-8 의 구조적 근거다").isEqualTo(1L);
    }

    @Test
    void 단일_좌석에는_하나의_홀드만_기록된다() throws Exception {
        runContention();

        // 이 검증은 V-2(좌석당 확정 예약 1건)가 아니다.
        // V-2 는 reservation_seat 와 CONFIRMED 예약을 대상으로 하며,
        // 확정 단계(PAYING → SOLD)가 구현된 뒤에야 검증할 수 있다.
        //
        // 여기서 확인하는 것은 경합이 끝난 뒤 승자의 hold_id 가 실제로 기록되었고
        // 그 값이 하나뿐이라는 것이다. 행이 하나이므로 개수 자체는 구조적으로 1 이하이고,
        // 유의미한 신호는 "0 이 아니다"(= 홀드가 유실되지 않았다) 쪽이다.
        Long recordedHolds = jdbc().queryForObject(
                "SELECT COUNT(DISTINCT hold_id) FROM seat_inventory WHERE id = ?",
                Long.class, seatId);

        assertThat(recordedHolds).isEqualTo(1L);
    }

    // ------------------------------------------------------------------

    private ContentionResult runContention() throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(CONTENDERS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CONTENDERS);

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger contended = new AtomicInteger();
        List<String> errors = new CopyOnWriteArrayList<>();
        Map<String, Long> winners = new ConcurrentHashMap<>();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < CONTENDERS; i++) {
                long userId = i + 1L;
                HoldId holdId = HoldId.newId();

                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        SeatHoldOutcome outcome =
                                repository.hold(new SeatId(seatId), holdId, new UserId(userId));
                        if (outcome == SeatHoldOutcome.HELD) {
                            succeeded.incrementAndGet();
                            winners.put(holdId.asString(), userId);
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

            // 전원이 대기 지점에 도달한 뒤에 한 번에 출발시킨다.
            assertThat(ready.await(60, TimeUnit.SECONDS)).as("전원 준비 완료").isTrue();
            start.countDown();
            assertThat(done.await(90, TimeUnit.SECONDS)).as("전원 종료").isTrue();
        }

        assertThat(winners).as("승자 기록").hasSizeLessThanOrEqualTo(1);
        Map.Entry<String, Long> winner = winners.entrySet().stream().findFirst().orElseThrow();

        return new ContentionResult(
                succeeded.get(), contended.get(), errors, winner.getKey(), winner.getValue());
    }

    private record ContentionResult(
            int succeeded, int contended, List<String> errors, String winnerHoldId, long winnerUserId) {
    }
}
