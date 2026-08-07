package com.railgate.reservation.infra.seat;

import static org.assertj.core.api.Assertions.assertThat;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.ReservationId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.infra.MySqlTestSupport;
import com.railgate.reservation.seat.SeatId;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * ★ stale candidate 재현 — 후보 조회 결과를 현재 상태로 믿으면 I-11 이 깨진다.
 *
 * <p><b>이 재현에는 스레드가 필요 없다.</b> 결함이 타이밍이 아니라
 * "후보 SELECT 와 UPDATE 사이에 상태가 바뀔 수 있다" 는 사실 자체이기 때문이다.
 * 테스트는 그 창을 제어 흐름으로 열어 보인다.
 *
 * <pre>
 * 1. PAYING 좌석의 expires_at 을 과거로 만든다
 * 2. 스위퍼가 후보를 조회한다        ← 이 시점의 스냅샷
 * 3. 확정 경로가 그 좌석을 SOLD 로 확정한다   ← 창이 열려 있다
 * 4. 스위퍼가 UPDATE 를 실행한다
 * </pre>
 *
 * <p>3번이 가능한 이유는 {@link JdbcSeatPaymentRepository#confirm} 의 조건에
 * {@code expires_at} 이 없기 때문이다. <b>이미 만료된 PAYING 좌석도 확정된다.</b>
 * 즉 스위퍼가 회수하려는 바로 그 좌석이 회수 직전에 팔리는 것은 정상 경로다.
 */
@Timeout(60)
@DisplayName("stale candidate 재현 - 순진한 만료 vs 조건부 만료")
class SeatExpiryStaleCandidateTest extends MySqlTestSupport {

    private static final long SCHEDULE_ID = 1L;
    private static final Duration HOLD_DURATION = Duration.ofMinutes(5);
    private static final Duration PAYMENT_DURATION = Duration.ofMinutes(5);

    private static final HoldId HOLD = HoldId.of("abcdefab-cdef-4abc-8def-abcdefabcdef");
    private static final UserId USER = new UserId(7L);
    private static final ReservationId RESERVATION = new ReservationId(100L);

    private JdbcSeatHoldRepository holdRepository;
    private JdbcSeatPaymentRepository paymentRepository;
    private JdbcSeatExpiryRepository expiryRepository;
    private long seatId;

    @BeforeEach
    void setUp() {
        holdRepository = new JdbcSeatHoldRepository(dataSource(), HOLD_DURATION);
        paymentRepository = new JdbcSeatPaymentRepository(dataSource(), PAYMENT_DURATION);
        expiryRepository = new JdbcSeatExpiryRepository(dataSource());
        seatId = insertAvailableSeat(SCHEDULE_ID, "12A");
    }

    /** 만료된 PAYING 좌석을 만든다. 스위퍼 후보가 되는 상태다. */
    private void givenExpiredPayingSeat() {
        holdRepository.hold(new SeatId(seatId), HOLD, USER);
        paymentRepository.startPayment(new SeatId(seatId), HOLD);
        jdbc().update(
                "UPDATE seat_inventory SET expires_at = DATE_SUB(NOW(3), INTERVAL 1 SECOND) WHERE id = ?",
                seatId);
    }

    @Test
    @DisplayName("A: 좌석 ID 만 믿고 UPDATE 하면 확정된 좌석이 풀린다 (I-11 위반)")
    void 순진한_만료는_확정된_좌석을_되돌린다() {
        givenExpiredPayingSeat();

        // 1. 후보 조회 — 이 시점에는 아직 PAYING 이다.
        List<ExpiryCandidate> candidates = expiryRepository.findExpiredCandidates(100);
        assertThat(candidates).hasSize(1);

        // 2. 창이 열려 있는 동안 확정이 성공한다.
        SeatConfirmationOutcome confirmed =
                paymentRepository.confirm(new SeatId(seatId), HOLD, RESERVATION);
        assertThat(confirmed).isEqualTo(SeatConfirmationOutcome.CONFIRMED);
        assertThat(statusOf(seatId)).isEqualTo("SOLD");

        // 3. 순진한 구현이 stale 한 후보로 UPDATE 한다.
        NaiveSeatExpiryRepository naive = new NaiveSeatExpiryRepository(dataSource());
        int recovered = naive.expire(candidates);

        assertThat(recovered).as("이미 팔린 좌석을 회수했다고 보고한다").isEqualTo(1);
        assertThat(statusOf(seatId))
                .as("확정된 좌석이 다시 판매 가능 상태가 됐다 = I-11 위반")
                .isEqualTo("AVAILABLE");
        assertThat(reservationIdOf(seatId))
                .as("예약 귀속까지 지워졌다")
                .isNull();
    }

    @Test
    @DisplayName("B: 조건부 만료는 같은 조건에서 stale 후보를 거부한다")
    void 조건부_만료는_확정된_좌석을_건드리지_않는다() {
        givenExpiredPayingSeat();

        List<ExpiryCandidate> candidates = expiryRepository.findExpiredCandidates(100);
        assertThat(candidates).hasSize(1);

        SeatConfirmationOutcome confirmed =
                paymentRepository.confirm(new SeatId(seatId), HOLD, RESERVATION);
        assertThat(confirmed).isEqualTo(SeatConfirmationOutcome.CONFIRMED);

        int recovered = expiryRepository.expire(candidates);

        assertThat(recovered).as("stale 후보는 회수되지 않는다").isZero();
        assertThat(statusOf(seatId)).isEqualTo("SOLD");
        assertThat(reservationIdOf(seatId)).isEqualTo(RESERVATION.value());
    }

    @Test
    @DisplayName("C: 두 구현의 차이는 확정 이후 좌석의 최종 상태다")
    void 확정_이후_최종_상태가_두_구현의_차이다() {
        // 순진한 구현
        givenExpiredPayingSeat();
        List<ExpiryCandidate> naiveCandidates = expiryRepository.findExpiredCandidates(100);
        paymentRepository.confirm(new SeatId(seatId), HOLD, RESERVATION);
        new NaiveSeatExpiryRepository(dataSource()).expire(naiveCandidates);
        String naiveFinalStatus = statusOf(seatId);

        // 같은 좌석을 처음 상태로 되돌리고 올바른 구현으로 반복한다.
        jdbc().update("""
                UPDATE seat_inventory
                   SET status='AVAILABLE', hold_id=NULL, held_by=NULL, held_at=NULL,
                       expires_at=NULL, reservation_id=NULL
                 WHERE id = ?
                """, seatId);
        givenExpiredPayingSeat();
        List<ExpiryCandidate> candidates = expiryRepository.findExpiredCandidates(100);
        paymentRepository.confirm(new SeatId(seatId), HOLD, RESERVATION);
        expiryRepository.expire(candidates);
        String correctFinalStatus = statusOf(seatId);

        assertThat(naiveFinalStatus).as("순진한 구현: 팔린 좌석이 풀렸다").isEqualTo("AVAILABLE");
        assertThat(correctFinalStatus).as("조건부 만료: 팔린 좌석이 유지됐다").isEqualTo("SOLD");
    }

    @Test
    @DisplayName("D: 홀드가 교체된 좌석도 순진한 구현은 되돌린다")
    void 순진한_만료는_새_홀드의_좌석까지_되돌린다() {
        // 확정만이 위험한 것이 아니다. 만료 후 다른 사용자가 다시 잡은 좌석도 같은 문제다.
        givenExpiredPayingSeat();
        List<ExpiryCandidate> candidates = expiryRepository.findExpiredCandidates(100);

        // 창이 열린 사이에 회수되고 다른 사용자가 새로 잡았다고 가정한다.
        jdbc().update("""
                UPDATE seat_inventory
                   SET status='AVAILABLE', hold_id=NULL, held_by=NULL, held_at=NULL, expires_at=NULL
                 WHERE id = ?
                """, seatId);
        HoldId newHold = HoldId.of("11111111-2222-4333-8444-555555555555");
        holdRepository.hold(new SeatId(seatId), newHold, new UserId(9L));

        int naiveRecovered = new NaiveSeatExpiryRepository(dataSource()).expire(candidates);
        assertThat(naiveRecovered).isEqualTo(1);
        assertThat(statusOf(seatId)).as("남의 새 홀드가 파괴됐다").isEqualTo("AVAILABLE");

        // 올바른 구현으로 같은 상황을 재현한다.
        holdRepository.hold(new SeatId(seatId), newHold, new UserId(9L));
        int recovered = expiryRepository.expire(candidates);

        assertThat(recovered).as("hold_id 가 다르므로 거부된다").isZero();
        assertThat(statusOf(seatId)).isEqualTo("HELD");
        assertThat(holdIdOf(seatId)).isEqualTo(newHold.asString());
    }
}
