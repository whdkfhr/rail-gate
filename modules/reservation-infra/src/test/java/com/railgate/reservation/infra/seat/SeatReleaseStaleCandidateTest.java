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
 * ★ 대상 기준 해제의 실패 재현 — 남의 홀드 파괴와 확정 좌석 되돌림.
 *
 * <p><b>스레드가 필요 없다.</b> 결함이 타이밍이 아니라
 * "해제를 좌석 기준으로 하면 그 좌석의 현재 주인이 누구든 풀린다" 는 사실 자체이기 때문이다.
 *
 * <p><b>왜 실제 장애에서 가능한가.</b>
 * 해제 요청은 네트워크를 타고 오며 응답은 유실될 수 있다. 사용자가 재시도할 무렵
 * 그 좌석은 이미 다른 사람이 잡았거나 결제를 마쳤을 수 있다.
 * 좌석 기준 해제는 그 상황을 구분하지 못한다 (CLAUDE.md 규칙 18).
 */
@Timeout(60)
@DisplayName("자발적 해제 실패 재현 - 좌석 기준 vs 홀드 기준 CAS")
class SeatReleaseStaleCandidateTest extends MySqlTestSupport {

    private static final long SCHEDULE_ID = 1L;
    private static final Duration HOLD_DURATION = Duration.ofMinutes(5);
    private static final Duration PAYMENT_DURATION = Duration.ofMinutes(5);

    private static final HoldId MINE = HoldId.of("abcdefab-cdef-4abc-8def-abcdefabcdef");
    private static final HoldId THEIRS = HoldId.of("11111111-2222-4333-8444-555555555555");
    private static final UserId OWNER = new UserId(7L);
    private static final UserId OTHER_USER = new UserId(9L);
    private static final ReservationId RESERVATION = new ReservationId(100L);

    private JdbcSeatHoldRepository holdRepository;
    private JdbcSeatPaymentRepository paymentRepository;
    private JdbcSeatReleaseRepository repository;
    private long seatId;

    @BeforeEach
    void setUp() {
        holdRepository = new JdbcSeatHoldRepository(dataSource(), HOLD_DURATION);
        paymentRepository = new JdbcSeatPaymentRepository(dataSource(), PAYMENT_DURATION);
        repository = new JdbcSeatReleaseRepository(dataSource());
        seatId = insertAvailableSeat(SCHEDULE_ID, "12A");
    }

    private void freeSeat() {
        jdbc().update("""
                UPDATE seat_inventory
                   SET status='AVAILABLE', hold_id=NULL, held_by=NULL, held_at=NULL,
                       expires_at=NULL, reservation_id=NULL
                 WHERE id = ?
                """, seatId);
    }

    @Test
    @DisplayName("A: 좌석 기준 해제는 남이 새로 잡은 홀드를 파괴한다")
    void 좌석_기준_해제는_남의_홀드를_파괴한다() {
        holdRepository.hold(new SeatId(seatId), MINE, OWNER);
        List<SeatId> staleCandidates = repository.findReleasableSeats(MINE, OWNER);
        assertThat(staleCandidates).hasSize(1);

        // 응답이 유실된 사이 내 홀드가 만료되고 다른 사용자가 같은 좌석을 잡았다.
        freeSeat();
        holdRepository.hold(new SeatId(seatId), THEIRS, OTHER_USER);

        int naiveReleased = new NaiveSeatReleaseRepository(dataSource()).release(staleCandidates);

        assertThat(naiveReleased).isEqualTo(1);
        assertThat(statusOf(seatId))
                .as("남의 홀드가 사라졌다")
                .isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("B: 홀드 기준 CAS 는 같은 조건에서 거부한다")
    void 홀드_기준_해제는_남의_홀드를_건드리지_않는다() {
        holdRepository.hold(new SeatId(seatId), MINE, OWNER);
        List<SeatId> staleCandidates = repository.findReleasableSeats(MINE, OWNER);

        freeSeat();
        holdRepository.hold(new SeatId(seatId), THEIRS, OTHER_USER);

        int released = repository.release(MINE, OWNER, staleCandidates);

        assertThat(released).as("hold_id 가 다르므로 0건").isZero();
        assertThat(statusOf(seatId)).isEqualTo("HELD");
        assertThat(holdIdOf(seatId)).isEqualTo(THEIRS.asString());
        assertThat(heldByOf(seatId)).isEqualTo(OTHER_USER.value());
    }

    @Test
    @DisplayName("C: 좌석 기준 해제는 확정된 좌석까지 되돌린다")
    void 좌석_기준_해제는_확정된_좌석을_되돌린다() {
        holdRepository.hold(new SeatId(seatId), MINE, OWNER);
        List<SeatId> staleCandidates = repository.findReleasableSeats(MINE, OWNER);

        paymentRepository.startPayment(new SeatId(seatId), MINE);
        paymentRepository.confirm(new SeatId(seatId), MINE, RESERVATION);
        assertThat(statusOf(seatId)).isEqualTo("SOLD");

        int naiveReleased = new NaiveSeatReleaseRepository(dataSource()).release(staleCandidates);

        assertThat(naiveReleased).isEqualTo(1);
        assertThat(statusOf(seatId)).as("판 좌석이 풀렸다").isEqualTo("AVAILABLE");
        assertThat(reservationIdOf(seatId)).as("예약 귀속까지 지워졌다").isNull();
    }

    @Test
    @DisplayName("D: 홀드 기준 CAS 는 확정된 좌석을 유지한다")
    void 홀드_기준_해제는_확정된_좌석을_유지한다() {
        holdRepository.hold(new SeatId(seatId), MINE, OWNER);
        List<SeatId> staleCandidates = repository.findReleasableSeats(MINE, OWNER);

        paymentRepository.startPayment(new SeatId(seatId), MINE);
        paymentRepository.confirm(new SeatId(seatId), MINE, RESERVATION);

        int released = repository.release(MINE, OWNER, staleCandidates);

        assertThat(released).as("status 술어가 SOLD 를 배제한다").isZero();
        assertThat(statusOf(seatId)).isEqualTo("SOLD");
        assertThat(reservationIdOf(seatId)).isEqualTo(RESERVATION.value());
    }

    @Test
    @DisplayName("E: 두 구현의 차이는 재요청이 도착했을 때의 최종 상태다")
    void 재요청_후_최종_상태가_두_구현의_차이다() {
        // 순진한 구현
        holdRepository.hold(new SeatId(seatId), MINE, OWNER);
        List<SeatId> stale = repository.findReleasableSeats(MINE, OWNER);
        freeSeat();
        holdRepository.hold(new SeatId(seatId), THEIRS, OTHER_USER);
        new NaiveSeatReleaseRepository(dataSource()).release(stale);
        String naiveStatus = statusOf(seatId);

        // 같은 상황을 올바른 구현으로 반복
        freeSeat();
        holdRepository.hold(new SeatId(seatId), MINE, OWNER);
        List<SeatId> stale2 = repository.findReleasableSeats(MINE, OWNER);
        freeSeat();
        holdRepository.hold(new SeatId(seatId), THEIRS, OTHER_USER);
        repository.release(MINE, OWNER, stale2);
        String correctStatus = statusOf(seatId);

        assertThat(naiveStatus).as("좌석 기준: 남의 홀드가 파괴됨").isEqualTo("AVAILABLE");
        assertThat(correctStatus).as("홀드 기준: 남의 홀드가 유지됨").isEqualTo("HELD");
    }
}
