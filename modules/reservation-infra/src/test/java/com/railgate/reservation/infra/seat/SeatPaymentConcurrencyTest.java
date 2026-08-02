package com.railgate.reservation.infra.seat;

import static org.assertj.core.api.Assertions.assertThat;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.ReservationId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.infra.MySqlTestSupport;
import com.railgate.reservation.seat.SeatId;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * ★ 결제 시작과 좌석 확정의 동시성 검증.
 *
 * <p>Task 2A 가 {@code AVAILABLE → HELD} 경쟁을 막았다면, 여기서는 그 뒤 두 단계를 본다.
 * 특히 확정은 <b>I-8 의 마지막 조각</b>이다. 같은 홀드를 든 여러 요청이 서로 다른 예약으로
 * 동시에 확정하려 할 때 정확히 하나만 통과해야 "좌석은 최종적으로 한 예약에만 확정된다" 가 성립한다.
 *
 * <p>동시 출발은 {@code CountDownLatch} 로 한다. {@code Thread.sleep} 으로 타이밍을 맞추면
 * 머신 부하에 따라 결과가 달라져 아무것도 보장하지 못한다 (CLAUDE.md 규칙 25).
 */
@Timeout(120)
@DisplayName("결제 시작 / 좌석 확정 동시성")
class SeatPaymentConcurrencyTest extends MySqlTestSupport {

    private static final int CONTENDERS = 1_000;
    private static final long SCHEDULE_ID = 1L;

    private static final HoldId HOLD = HoldId.of("abcdefab-cdef-4abc-8def-abcdefabcdef");
    private static final UserId USER = new UserId(7L);

    private JdbcSeatHoldRepository holdRepository;
    private JdbcSeatPaymentRepository paymentRepository;
    private long seatId;

    @BeforeEach
    void setUp() {
        holdRepository = new JdbcSeatHoldRepository(dataSource(), Duration.ofMinutes(5));
        paymentRepository = new JdbcSeatPaymentRepository(dataSource(), Duration.ofMinutes(5));
        seatId = insertAvailableSeat(SCHEDULE_ID, "12A");
    }

    // ------------------------------------------------------------------
    // 결제 시작
    // ------------------------------------------------------------------

    @Test
    void 같은_홀드로_결제_시작을_동시에_요청하면_정확히_하나만_성공한다() throws Exception {
        holdRepository.hold(new SeatId(seatId), HOLD, USER);

        Result result = race(i -> {
            SeatPaymentOutcome outcome = paymentRepository.startPayment(new SeatId(seatId), HOLD);
            return outcome == SeatPaymentOutcome.STARTED ? String.valueOf(i) : null;
        });

        assertThat(result.succeeded()).as("성공").isEqualTo(1);
        assertThat(result.failed()).as("경합 실패 (정상)").isEqualTo(CONTENDERS - 1);
        assertThat(result.errors()).as("시스템 오류 / lock timeout / deadlock").isEmpty();
        assertThat(statusOf(seatId)).isEqualTo("PAYING");
    }

    // ------------------------------------------------------------------
    // 좌석 확정 — I-8 의 마지막 조각
    // ------------------------------------------------------------------

    @Test
    void 서로_다른_예약이_같은_좌석을_동시에_확정하면_정확히_하나만_성공한다() throws Exception {
        givenPayingSeat();

        Result result = confirmRace();

        assertThat(result.succeeded()).as("확정 성공").isEqualTo(1);
        assertThat(result.failed()).as("확정 실패 (정상)").isEqualTo(CONTENDERS - 1);
        assertThat(result.errors()).as("시스템 오류 / lock timeout / deadlock").isEmpty();
    }

    @Test
    void 확정_경합_후_DB의_예약_식별자가_실제_승자와_일치한다() throws Exception {
        givenPayingSeat();

        Result result = confirmRace();

        // 성공 응답을 받은 예약과 DB 에 기록된 예약이 다르면 정합성이 깨진 것이다.
        assertThat(statusOf(seatId)).isEqualTo("SOLD");
        assertThat(reservationIdOf(seatId)).isEqualTo(Long.valueOf(result.winner()));
    }

    @Test
    void 확정_경합_후에도_홀드와_만료_정보는_모두_비워진다() throws Exception {
        givenPayingSeat();

        confirmRace();

        assertThat(holdIdOf(seatId)).isNull();
        assertThat(heldByOf(seatId)).isNull();
        assertThat(heldAtOf(seatId)).isNull();
        assertThat(expiresAtOf(seatId)).isNull();
    }

    @Test
    void 확정_경합_후에도_좌석_행은_하나뿐이다() throws Exception {
        givenPayingSeat();

        confirmRace();

        Long rows = jdbc().queryForObject(
                "SELECT COUNT(*) FROM seat_inventory WHERE schedule_id = ? AND seat_no = ?",
                Long.class, SCHEDULE_ID, "12A");

        assertThat(rows).as("UNIQUE(schedule_id, seat_no) 가 I-8 의 구조적 전제다").isEqualTo(1L);
    }

    /** 서로 다른 {@link ReservationId} 로, 그러나 동일한 {@link HoldId} 로 경쟁시킨다. */
    private Result confirmRace() throws InterruptedException {
        return race(i -> {
            ReservationId reservationId = new ReservationId(i + 1L);
            SeatConfirmationOutcome outcome =
                    paymentRepository.confirm(new SeatId(seatId), HOLD, reservationId);
            return outcome == SeatConfirmationOutcome.CONFIRMED
                    ? String.valueOf(reservationId.value())
                    : null;
        });
    }

    private void givenPayingSeat() {
        holdRepository.hold(new SeatId(seatId), HOLD, USER);
        SeatPaymentOutcome started = paymentRepository.startPayment(new SeatId(seatId), HOLD);
        assertThat(started).isEqualTo(SeatPaymentOutcome.STARTED);
    }

    // ------------------------------------------------------------------

    /**
     * @param attempt 성공하면 승자 식별 문자열을, 실패하면 {@code null} 을 돌려준다.
     */
    private Result race(IntFunction<String> attempt) throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(CONTENDERS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CONTENDERS);

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        List<String> errors = new CopyOnWriteArrayList<>();
        var winners = ConcurrentHashMap.<String>newKeySet();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < CONTENDERS; i++) {
                int index = i;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        String winner = attempt.apply(index);
                        if (winner != null) {
                            succeeded.incrementAndGet();
                            winners.add(winner);
                        } else {
                            failed.incrementAndGet();
                        }
                    } catch (Exception e) {
                        errors.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertThat(ready.await(60, TimeUnit.SECONDS)).as("전원 준비 완료").isTrue();
            start.countDown();
            assertThat(done.await(90, TimeUnit.SECONDS)).as("전원 종료").isTrue();
        }

        assertThat(winners).as("승자는 최대 한 명이다").hasSizeLessThanOrEqualTo(1);
        String winner = winners.stream().findFirst().orElse(null);

        return new Result(succeeded.get(), failed.get(), errors, winner);
    }

    private record Result(int succeeded, int failed, List<String> errors, String winner) {
    }
}
