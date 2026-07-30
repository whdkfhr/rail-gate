package com.railgate.reservation.seat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.ReservationId;
import com.railgate.reservation.UserId;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * FR-2.4 / I-10 / I-11 만료 규칙.
 *
 * 이 테스트가 표현하는 것과 강제하는 것을 구분한다.
 *  - 표현: "SOLD 는 만료로 풀리지 않는다" 라는 규칙
 *  - 강제: 실제 방어선은 Sweeper 의 조건부 UPDATE
 *          (WHERE status IN ('HELD','PAYING') AND expires_at <= NOW(3))
 *          동시 실행 시의 보장은 InnoDB 행 잠금이 한다. TASK-2 에서 다룬다.
 */
@DisplayName("Seat 만료 - FR-2.4 / I-10 / I-11")
class SeatExpiryTest {

    private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");
    private static final Instant EXPIRES_AT = NOW.plusSeconds(300);
    private static final Instant BEFORE_EXPIRY = EXPIRES_AT.minusSeconds(1);
    private static final Instant AT_EXPIRY = EXPIRES_AT;
    private static final Instant AFTER_EXPIRY = EXPIRES_AT.plusSeconds(1);

    private static final HoldId HOLD = HoldId.of("11111111-1111-1111-1111-111111111111");
    private static final UserId USER = new UserId(1L);
    private static final ReservationId RESERVATION = new ReservationId(100L);

    private static Seat heldSeat() {
        Seat seat = Seat.available(new SeatId(1L), new SeatNo("12A"));
        seat.hold(HOLD, USER, EXPIRES_AT);
        return seat;
    }

    @Nested
    @DisplayName("만료 판정")
    class 만료_판정 {

        @Test
        void 만료_시각_이전에는_만료되지_않았다() {
            assertThat(heldSeat().isExpiredAt(BEFORE_EXPIRY)).isFalse();
        }

        @Test
        void 만료_시각과_같으면_만료된_것으로_본다() {
            // DB 의 expires_at <= NOW(3) 과 동일한 경계 규칙을 쓴다.
            assertThat(heldSeat().isExpiredAt(AT_EXPIRY)).isTrue();
        }

        @Test
        void 만료_시각_이후에는_만료됐다() {
            assertThat(heldSeat().isExpiredAt(AFTER_EXPIRY)).isTrue();
        }

        @Test
        void 판매_가능한_좌석은_만료_대상이_아니다() {
            Seat seat = Seat.available(new SeatId(1L), new SeatNo("12A"));

            assertThat(seat.isExpiredAt(AFTER_EXPIRY)).isFalse();
        }
    }

    @Nested
    @DisplayName("만료 실행")
    class 만료_실행 {

        @Test
        void 만료된_선점_좌석은_다시_판매_가능해진다() {
            Seat seat = heldSeat();

            seat.expire(AFTER_EXPIRY);

            assertThat(seat.status()).isEqualTo(SeatStatus.AVAILABLE);
            assertThat(seat.holdId()).isEmpty();
            assertThat(seat.expiresAt()).isEmpty();
        }

        @Test
        void 만료되지_않은_좌석은_만료시킬_수_없다() {
            Seat seat = heldSeat();

            assertThatThrownBy(() -> seat.expire(BEFORE_EXPIRY))
                    .isInstanceOf(SeatStateTransitionException.class);

            assertThat(seat.status()).isEqualTo(SeatStatus.HELD);
        }

        @Test
        void 결제_데드라인이_지난_좌석은_다시_판매_가능해진다() {
            Seat seat = heldSeat();
            Instant paymentDeadline = EXPIRES_AT.plusSeconds(300);
            seat.startPayment(HOLD, paymentDeadline);

            seat.expire(paymentDeadline.plusSeconds(1));

            assertThat(seat.status()).isEqualTo(SeatStatus.AVAILABLE);
        }

        @Test
        void 결제를_시작하면_기존_선점_만료_시각으로는_만료되지_않는다() {
            // PAYING 진입 시 데드라인이 연장되므로, 원래 홀드 만료 시각이 지나도 살아있어야 한다.
            Seat seat = heldSeat();
            Instant paymentDeadline = EXPIRES_AT.plusSeconds(300);
            seat.startPayment(HOLD, paymentDeadline);

            assertThat(seat.isExpiredAt(AFTER_EXPIRY)).isFalse();
            assertThatThrownBy(() -> seat.expire(AFTER_EXPIRY))
                    .isInstanceOf(SeatStateTransitionException.class);
        }
    }

    @Nested
    @DisplayName("결제 데드라인은 만료를 앞당기지 않는다 (P-4)")
    class 결제_데드라인 {

        // P-4: PAYING 전이 시 최종 만료 시각은 max(기존 expiresAt, NOW(3) + 5분).
        // 더 이른 데드라인이 와도 거부하지 않고 기존 만료 시각을 유지한다.
        //
        // 거부하지 않는 이유: 결제 창(예: 5분)이 홀드 잔여 시간보다 짧은 순간에
        // 결제를 시작하면 정상 요청이 실패하게 된다. 정책의 목적은 "앞당기지 않는 것"이지
        // "요청을 거부하는 것"이 아니다.

        @Test
        void 데드라인이_더_뒤면_그_시각까지_연장된다() {
            Seat seat = heldSeat();
            Instant later = EXPIRES_AT.plusSeconds(300);

            seat.startPayment(HOLD, later);

            assertThat(seat.status()).isEqualTo(SeatStatus.PAYING);
            assertThat(seat.expiresAt()).contains(later);
        }

        @Test
        void 데드라인이_같으면_만료_시각이_그대로다() {
            Seat seat = heldSeat();

            seat.startPayment(HOLD, EXPIRES_AT);

            assertThat(seat.status()).isEqualTo(SeatStatus.PAYING);
            assertThat(seat.expiresAt()).contains(EXPIRES_AT);
        }

        @Test
        void 데드라인이_더_이르면_기존_만료_시각을_유지한다() {
            Seat seat = heldSeat();

            seat.startPayment(HOLD, EXPIRES_AT.minusSeconds(120));

            assertThat(seat.expiresAt()).contains(EXPIRES_AT);
        }

        @Test
        void 데드라인이_더_일러도_결제는_시작된다() {
            // 거부가 아니라 유지다. 정상 요청이 실패하지 않아야 한다.
            Seat seat = heldSeat();

            seat.startPayment(HOLD, EXPIRES_AT.minusSeconds(120));

            assertThat(seat.status()).isEqualTo(SeatStatus.PAYING);
        }

        @ParameterizedTest(name = "데드라인이 기존 만료 {0}초 지점이어도 만료는 앞당겨지지 않는다")
        @ValueSource(longs = {-3600, -300, -1, 0, 1, 300, 3600})
        void 만료_시각은_어떤_데드라인에도_앞당겨지지_않는다(long offsetSeconds) {
            Seat seat = heldSeat();
            Instant deadline = EXPIRES_AT.plusSeconds(offsetSeconds);

            seat.startPayment(HOLD, deadline);

            Instant applied = seat.expiresAt().orElseThrow();
            assertThat(applied).isAfterOrEqualTo(EXPIRES_AT);
            assertThat(applied).isEqualTo(offsetSeconds > 0 ? deadline : EXPIRES_AT);
        }

        @Test
        void 결제를_시작해도_홀드_소유권은_유지된다() {
            Seat seat = heldSeat();

            seat.startPayment(HOLD, EXPIRES_AT.minusSeconds(120));

            assertThat(seat.isHeldBy(HOLD)).isTrue();
        }

        @Test
        void 다른_홀드는_데드라인과_무관하게_거부된다() {
            Seat seat = heldSeat();
            HoldId other = HoldId.of("22222222-2222-2222-2222-222222222222");

            assertThatThrownBy(() -> seat.startPayment(other, EXPIRES_AT.plusSeconds(300)))
                    .isInstanceOf(HoldOwnershipException.class);

            assertThat(seat.status()).isEqualTo(SeatStatus.HELD);
            assertThat(seat.expiresAt()).contains(EXPIRES_AT);
        }
    }

    @Nested
    @DisplayName("★ I-11 확정된 좌석은 만료로 풀리지 않는다")
    class 확정된_좌석 {

        private Seat soldSeat() {
            Seat seat = heldSeat();
            seat.startPayment(HOLD, EXPIRES_AT.plusSeconds(300));
            seat.confirm(HOLD, RESERVATION);
            return seat;
        }

        @Test
        void 확정된_좌석은_아무리_시간이_지나도_만료_대상이_아니다() {
            Seat seat = soldSeat();

            assertThat(seat.isExpiredAt(AFTER_EXPIRY.plusSeconds(86_400))).isFalse();
        }

        @Test
        void 확정된_좌석을_만료시키려_하면_거부된다() {
            Seat seat = soldSeat();

            assertThatThrownBy(() -> seat.expire(AFTER_EXPIRY.plusSeconds(86_400)))
                    .isInstanceOf(SeatStateTransitionException.class);
        }

        @Test
        void 만료_시도가_거부되어도_확정_상태와_예약_정보는_그대로다() {
            Seat seat = soldSeat();

            assertThatThrownBy(() -> seat.expire(AFTER_EXPIRY.plusSeconds(86_400)))
                    .isInstanceOf(SeatStateTransitionException.class);

            assertThat(seat.status()).isEqualTo(SeatStatus.SOLD);
            assertThat(seat.reservationId()).contains(RESERVATION);
        }
    }
}
