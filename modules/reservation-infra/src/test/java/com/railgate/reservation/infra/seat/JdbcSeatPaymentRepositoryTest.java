package com.railgate.reservation.infra.seat;

import static org.assertj.core.api.Assertions.assertThat;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.ReservationId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.infra.MySqlTestSupport;
import com.railgate.reservation.seat.SeatId;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * 결제 시작({@code HELD → PAYING})과 좌석 확정({@code PAYING → SOLD}) 조건부 UPDATE.
 *
 * <p>도메인 상태 머신({@code Seat})이 이미 표현한 규칙을 MySQL 이 실제로 강제하는지 검증한다.
 * 동시성 검증은 {@code SeatPaymentConcurrencyTest} 가 담당한다.
 */
@Timeout(60)
@DisplayName("JdbcSeatPaymentRepository - 결제 시작 / 좌석 확정 CAS")
class JdbcSeatPaymentRepositoryTest extends MySqlTestSupport {

    private static final long SCHEDULE_ID = 1L;
    private static final Duration HOLD_DURATION = Duration.ofMinutes(5);
    private static final Duration PAYMENT_DURATION = Duration.ofMinutes(5);

    private static final HoldId HOLD = HoldId.of("abcdefab-cdef-4abc-8def-abcdefabcdef");
    private static final HoldId OTHER_HOLD = HoldId.of("11111111-2222-4333-8444-555555555555");
    private static final UserId USER = new UserId(7L);
    private static final ReservationId RESERVATION = new ReservationId(100L);
    private static final ReservationId OTHER_RESERVATION = new ReservationId(200L);

    private JdbcSeatHoldRepository holdRepository;
    private JdbcSeatPaymentRepository paymentRepository;
    private long seatId;

    @BeforeEach
    void setUp() {
        holdRepository = new JdbcSeatHoldRepository(dataSource(), HOLD_DURATION);
        paymentRepository = new JdbcSeatPaymentRepository(dataSource(), PAYMENT_DURATION);
        seatId = insertAvailableSeat(SCHEDULE_ID, "12A");
    }

    // ------------------------------------------------------------------
    // 픽스처는 항상 실제 경로로 만든다.
    // 상태 컬럼만 직접 바꾸면 도메인이 만들 수 없는 조합(예: hold_id 없는 PAYING)이 생긴다.
    // ------------------------------------------------------------------

    private void givenHeld() {
        holdRepository.hold(new SeatId(seatId), HOLD, USER);
    }

    private void givenPaying() {
        givenHeld();
        paymentRepository.startPayment(new SeatId(seatId), HOLD);
    }

    private void givenSold() {
        givenPaying();
        paymentRepository.confirm(new SeatId(seatId), HOLD, RESERVATION);
    }

    /** 만료 시각만 조정한다. Thread.sleep 없이 만료 상황을 결정적으로 만든다. */
    private void setExpiresAt(String sqlExpression) {
        jdbc().update(
                "UPDATE seat_inventory SET expires_at = " + sqlExpression + " WHERE id = ?", seatId);
    }

    @Nested
    @DisplayName("결제 시작 HELD → PAYING")
    class 결제_시작 {

        @Test
        void 같은_홀드의_유효한_선점은_결제를_시작할_수_있다() {
            givenHeld();

            SeatPaymentOutcome outcome = paymentRepository.startPayment(new SeatId(seatId), HOLD);

            assertThat(outcome).isEqualTo(SeatPaymentOutcome.STARTED);
            assertThat(statusOf(seatId)).isEqualTo("PAYING");
        }

        @Test
        void 다른_홀드로는_결제를_시작할_수_없다() {
            givenHeld();

            SeatPaymentOutcome outcome =
                    paymentRepository.startPayment(new SeatId(seatId), OTHER_HOLD);

            assertThat(outcome).isEqualTo(SeatPaymentOutcome.NOT_STARTED);
            assertThat(statusOf(seatId)).isEqualTo("HELD");
            assertThat(holdIdOf(seatId)).isEqualTo(HOLD.asString());
        }

        @Test
        void 이미_만료된_선점은_결제를_시작할_수_없다() {
            // 만료 판정은 MySQL NOW(3) 이 한다 (CLAUDE.md 규칙 7).
            // 도메인은 현재 시각을 모르므로 이 방어는 SQL 에만 존재한다.
            givenHeld();
            setExpiresAt("DATE_SUB(NOW(3), INTERVAL 1 SECOND)");

            SeatPaymentOutcome outcome = paymentRepository.startPayment(new SeatId(seatId), HOLD);

            assertThat(outcome).isEqualTo(SeatPaymentOutcome.NOT_STARTED);
            assertThat(statusOf(seatId)).isEqualTo("HELD");
        }

        @Test
        void 만료_시각과_정확히_같은_순간에는_결제를_시작할_수_없다() {
            // expires_at > NOW(3) 이므로 경계는 배타적이다.
            // 스위퍼의 회수 조건(expires_at <= NOW(3))과 겹치지 않아야 한다.
            givenHeld();
            setExpiresAt("NOW(3)");

            SeatPaymentOutcome outcome = paymentRepository.startPayment(new SeatId(seatId), HOLD);

            assertThat(outcome).isEqualTo(SeatPaymentOutcome.NOT_STARTED);
        }

        @Test
        void 판매_가능한_좌석은_결제를_시작할_수_없다() {
            SeatPaymentOutcome outcome = paymentRepository.startPayment(new SeatId(seatId), HOLD);

            assertThat(outcome).isEqualTo(SeatPaymentOutcome.NOT_STARTED);
            assertThat(statusOf(seatId)).isEqualTo("AVAILABLE");
        }

        @Test
        void 이미_결제_중인_좌석은_다시_시작할_수_없다() {
            givenPaying();

            SeatPaymentOutcome outcome = paymentRepository.startPayment(new SeatId(seatId), HOLD);

            assertThat(outcome).isEqualTo(SeatPaymentOutcome.NOT_STARTED);
        }

        @Test
        void 판매된_좌석은_결제를_시작할_수_없다() {
            givenSold();

            SeatPaymentOutcome outcome = paymentRepository.startPayment(new SeatId(seatId), HOLD);

            assertThat(outcome).isEqualTo(SeatPaymentOutcome.NOT_STARTED);
            assertThat(statusOf(seatId)).isEqualTo("SOLD");
        }

        @Test
        void 존재하지_않는_좌석은_결제를_시작할_수_없다() {
            SeatPaymentOutcome outcome =
                    paymentRepository.startPayment(new SeatId(999_999L), HOLD);

            assertThat(outcome).isEqualTo(SeatPaymentOutcome.NOT_STARTED);
        }

        @Test
        void 결제_시작은_홀드_정보를_유지한다() {
            // PAYING 은 여전히 임시 점유다. hold_id / held_by / held_at 이 남아 있어야
            // 확정과 해제가 소유권을 확인할 수 있다.
            givenHeld();
            Long heldByBefore = heldByOf(seatId);
            var heldAtBefore = heldAtOf(seatId);

            paymentRepository.startPayment(new SeatId(seatId), HOLD);

            assertThat(holdIdOf(seatId)).isEqualTo(HOLD.asString());
            assertThat(heldByOf(seatId)).isEqualTo(heldByBefore);
            assertThat(heldAtOf(seatId)).isEqualTo(heldAtBefore);
        }

        @Test
        void 결제_시작은_version_을_증가시킨다() {
            givenHeld();
            Long before = versionOf(seatId);

            paymentRepository.startPayment(new SeatId(seatId), HOLD);

            assertThat(versionOf(seatId)).isEqualTo(before + 1);
        }
    }

    @Nested
    @DisplayName("결제 데드라인은 만료를 앞당기지 않는다 (P-4)")
    class 결제_데드라인 {

        @Test
        void 기존_만료가_더_늦으면_그대로_유지한다() {
            // GREATEST 가 없으면 1시간 남은 홀드가 5분으로 줄어든다.
            givenHeld();
            setExpiresAt("DATE_ADD(NOW(3), INTERVAL 1 HOUR)");

            paymentRepository.startPayment(new SeatId(seatId), HOLD);

            assertThat(secondsUntilExpiry(seatId))
                    .as("1시간 근처를 유지해야 한다")
                    .isGreaterThan(Duration.ofMinutes(59).toSeconds());
        }

        @Test
        void 기존_만료가_더_이르면_결제_유효_시간까지_연장한다() {
            givenHeld();
            setExpiresAt("DATE_ADD(NOW(3), INTERVAL 30 SECOND)");

            paymentRepository.startPayment(new SeatId(seatId), HOLD);

            assertThat(secondsUntilExpiry(seatId))
                    .as("NOW(3) + 결제 유효 시간(5분) 근처로 연장된다")
                    .isBetween(PAYMENT_DURATION.toSeconds() - 5, PAYMENT_DURATION.toSeconds());
        }

        @Test
        void 만료_시각은_어떤_경우에도_앞당겨지지_않는다() {
            givenHeld();
            setExpiresAt("DATE_ADD(NOW(3), INTERVAL 1 HOUR)");
            Long before = secondsUntilExpiry(seatId);

            paymentRepository.startPayment(new SeatId(seatId), HOLD);

            assertThat(secondsUntilExpiry(seatId)).isGreaterThanOrEqualTo(before - 5);
        }
    }

    @Nested
    @DisplayName("★ 좌석 확정 PAYING → SOLD")
    class 좌석_확정 {

        @Test
        void 같은_홀드의_결제_중_좌석은_확정된다() {
            givenPaying();

            SeatConfirmationOutcome outcome =
                    paymentRepository.confirm(new SeatId(seatId), HOLD, RESERVATION);

            assertThat(outcome).isEqualTo(SeatConfirmationOutcome.CONFIRMED);
            assertThat(statusOf(seatId)).isEqualTo("SOLD");
        }

        @Test
        void 확정하면_예약_식별자가_기록된다() {
            givenPaying();

            paymentRepository.confirm(new SeatId(seatId), HOLD, RESERVATION);

            assertThat(reservationIdOf(seatId)).isEqualTo(RESERVATION.value());
        }

        @Test
        void 다른_홀드로는_확정할_수_없다() {
            // hold_id 조건이 없으면 남의 홀드를 확정하게 된다 (CLAUDE.md 규칙 4).
            givenPaying();

            SeatConfirmationOutcome outcome =
                    paymentRepository.confirm(new SeatId(seatId), OTHER_HOLD, RESERVATION);

            assertThat(outcome).isEqualTo(SeatConfirmationOutcome.NOT_CONFIRMED);
            assertThat(statusOf(seatId)).isEqualTo("PAYING");
            assertThat(reservationIdOf(seatId)).isNull();
        }

        @Test
        void 선점_상태에서_바로_확정할_수_없다() {
            // HELD → SOLD 직행은 전이표에 없다 (I-14).
            givenHeld();

            SeatConfirmationOutcome outcome =
                    paymentRepository.confirm(new SeatId(seatId), HOLD, RESERVATION);

            assertThat(outcome).isEqualTo(SeatConfirmationOutcome.NOT_CONFIRMED);
            assertThat(statusOf(seatId)).isEqualTo("HELD");
        }

        @Test
        void 판매_가능한_좌석은_확정할_수_없다() {
            SeatConfirmationOutcome outcome =
                    paymentRepository.confirm(new SeatId(seatId), HOLD, RESERVATION);

            assertThat(outcome).isEqualTo(SeatConfirmationOutcome.NOT_CONFIRMED);
            assertThat(statusOf(seatId)).isEqualTo("AVAILABLE");
        }

        @Test
        void 이미_확정된_좌석의_재확정은_0건이며_예약_식별자를_덮어쓰지_않는다() {
            // 주의: 이 0건은 저장소 CAS 의 결과일 뿐 API 멱등성이 아니다.
            // 재요청에 최초 응답을 돌려주는 것은 멱등성 키 인프라의 책임이며 후속 Task 다.
            // 여기서 보장하는 것은 "이미 팔린 좌석의 주인이 바뀌지 않는다" 하나다.
            givenSold();

            SeatConfirmationOutcome outcome =
                    paymentRepository.confirm(new SeatId(seatId), HOLD, OTHER_RESERVATION);

            assertThat(outcome).isEqualTo(SeatConfirmationOutcome.NOT_CONFIRMED);
            assertThat(reservationIdOf(seatId)).isEqualTo(RESERVATION.value());
        }

        @Test
        void 존재하지_않는_좌석은_확정할_수_없다() {
            SeatConfirmationOutcome outcome =
                    paymentRepository.confirm(new SeatId(999_999L), HOLD, RESERVATION);

            assertThat(outcome).isEqualTo(SeatConfirmationOutcome.NOT_CONFIRMED);
        }
    }

    @Nested
    @DisplayName("확정 후 좌석은 활성 홀드가 아니다")
    class 확정_후_상태 {

        @Test
        void 확정하면_홀드_정보가_모두_비워진다() {
            givenSold();

            assertThat(holdIdOf(seatId)).isNull();
            assertThat(heldByOf(seatId)).isNull();
            assertThat(heldAtOf(seatId)).isNull();
        }

        @Test
        void 확정하면_만료_시각이_비워진다() {
            // 만료될 일이 없는 좌석에 만료 시각이 남아 있으면 상태 조합이 어긋난다.
            // (I-11 자체는 스위퍼의 status IN ('HELD','PAYING') 술어가 지킨다)
            givenSold();

            assertThat(expiresAtOf(seatId)).isNull();
        }

        @Test
        void 확정된_행은_스위퍼_회수_조건에_걸리지_않는다() {
            givenSold();

            Long sweepable = jdbc().queryForObject("""
                    SELECT COUNT(*) FROM seat_inventory
                     WHERE id = ?
                       AND status IN ('HELD','PAYING')
                       AND expires_at <= NOW(3)
                    """, Long.class, seatId);

            assertThat(sweepable).as("SOLD 는 상태 술어에서 제외된다").isZero();
        }

        @Test
        void 확정은_version_을_증가시킨다() {
            givenPaying();
            Long before = versionOf(seatId);

            paymentRepository.confirm(new SeatId(seatId), HOLD, RESERVATION);

            assertThat(versionOf(seatId)).isEqualTo(before + 1);
        }

        @Test
        void 확정된_좌석은_다시_선점되지_않는다() {
            givenSold();

            SeatHoldOutcome outcome =
                    holdRepository.hold(new SeatId(seatId), HoldId.newId(), new UserId(9L));

            assertThat(outcome).isEqualTo(SeatHoldOutcome.ALREADY_HELD);
            assertThat(reservationIdOf(seatId)).isEqualTo(RESERVATION.value());
        }
    }
}
