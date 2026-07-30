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

/**
 * 좌석 상태 머신의 정상 전이와 홀드 소유권 규칙.
 *
 * 이 테스트가 완결하는 불변식은 I-14(전이표 준수) 뿐이다.
 * I-8(좌석당 확정 1건)은 단일 스레드 객체로 보장할 수 없다.
 * 실제 강제는 DB 행 잠금 + 조건부 UPDATE 이며 TASK-2 에서 다룬다.
 */
@DisplayName("Seat - 좌석 상태 머신")
class SeatTest {

    private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");
    private static final Instant HOLD_EXPIRES_AT = NOW.plusSeconds(300);
    private static final Instant PAYMENT_DEADLINE = NOW.plusSeconds(600);

    private static final HoldId HOLD = HoldId.of("11111111-1111-1111-1111-111111111111");
    private static final HoldId OTHER_HOLD = HoldId.of("22222222-2222-2222-2222-222222222222");
    private static final UserId USER = new UserId(1L);
    private static final ReservationId RESERVATION = new ReservationId(100L);

    private static Seat availableSeat() {
        return Seat.available(new SeatId(1L), new SeatNo("12A"));
    }

    private static Seat heldSeat() {
        Seat seat = availableSeat();
        seat.hold(HOLD, USER, HOLD_EXPIRES_AT);
        return seat;
    }

    private static Seat payingSeat() {
        Seat seat = heldSeat();
        seat.startPayment(HOLD, PAYMENT_DEADLINE);
        return seat;
    }

    private static Seat soldSeat() {
        Seat seat = payingSeat();
        seat.confirm(HOLD, RESERVATION);
        return seat;
    }

    @Nested
    @DisplayName("정상 전이")
    class 정상_전이 {

        @Test
        void 새로_만든_좌석은_판매_가능_상태다() {
            assertThat(availableSeat().status()).isEqualTo(SeatStatus.AVAILABLE);
        }

        @Test
        void 판매_가능한_좌석을_선점하면_HELD_가_된다() {
            Seat seat = availableSeat();

            seat.hold(HOLD, USER, HOLD_EXPIRES_AT);

            assertThat(seat.status()).isEqualTo(SeatStatus.HELD);
            assertThat(seat.isHeldBy(HOLD)).isTrue();
            assertThat(seat.expiresAt()).contains(HOLD_EXPIRES_AT);
        }

        @Test
        void 선점된_좌석의_결제를_시작하면_PAYING_이_된다() {
            Seat seat = heldSeat();

            seat.startPayment(HOLD, PAYMENT_DEADLINE);

            assertThat(seat.status()).isEqualTo(SeatStatus.PAYING);
        }

        @Test
        void 결제를_시작하면_만료_시각이_결제_데드라인까지_연장된다() {
            Seat seat = heldSeat();

            seat.startPayment(HOLD, PAYMENT_DEADLINE);

            assertThat(seat.expiresAt()).contains(PAYMENT_DEADLINE);
        }

        @Test
        void 결제_중인_좌석을_확정하면_SOLD_가_된다() {
            Seat seat = payingSeat();

            seat.confirm(HOLD, RESERVATION);

            assertThat(seat.status()).isEqualTo(SeatStatus.SOLD);
            assertThat(seat.reservationId()).contains(RESERVATION);
        }

        @Test
        void 확정된_좌석은_더_이상_만료_시각을_갖지_않는다() {
            assertThat(soldSeat().expiresAt()).isEmpty();
        }

        @Test
        void 확정된_좌석은_더_이상_활성_홀드가_아니다() {
            // 홀드는 "만료될 수 있는 임시 점유" 다. 확정된 좌석에는 그런 것이 없다.
            // 홀드 이력이 필요하면 seat_state_log 에 남기고, 활성 상태 필드에는 남기지 않는다.
            Seat seat = soldSeat();

            assertThat(seat.isHeldBy(HOLD)).isFalse();
            assertThat(seat.holdId()).isEmpty();
            assertThat(seat.heldBy()).isEmpty();
        }

        @Test
        void 선점된_좌석을_해제하면_다시_판매_가능해진다() {
            Seat seat = heldSeat();

            seat.release(HOLD);

            assertThat(seat.status()).isEqualTo(SeatStatus.AVAILABLE);
            assertThat(seat.expiresAt()).isEmpty();
        }

        @Test
        void 결제_중인_좌석을_해제하면_다시_판매_가능해진다() {
            Seat seat = payingSeat();

            seat.release(HOLD);

            assertThat(seat.status()).isEqualTo(SeatStatus.AVAILABLE);
        }

        @Test
        void 해제된_좌석은_홀드_정보를_남기지_않는다() {
            Seat seat = heldSeat();

            seat.release(HOLD);

            assertThat(seat.holdId()).isEmpty();
            assertThat(seat.heldBy()).isEmpty();
        }
    }

    @Nested
    @DisplayName("홀드 소유권")
    class 홀드_소유권 {

        // CLAUDE.md 규칙 18: 해제는 대상 기준이 아니라 hold_id 기준이어야 한다.
        // 응답 유실 후 재요청이 남의 홀드를 파괴하는 사고를 막는다.

        @Test
        void 다른_홀드의_좌석은_해제할_수_없다() {
            Seat seat = heldSeat();

            assertThatThrownBy(() -> seat.release(OTHER_HOLD))
                    .isInstanceOf(HoldOwnershipException.class);
        }

        @Test
        void 다른_홀드의_좌석은_결제를_시작할_수_없다() {
            Seat seat = heldSeat();

            assertThatThrownBy(() -> seat.startPayment(OTHER_HOLD, PAYMENT_DEADLINE))
                    .isInstanceOf(HoldOwnershipException.class);
        }

        @Test
        void 다른_홀드의_좌석은_확정할_수_없다() {
            Seat seat = payingSeat();

            assertThatThrownBy(() -> seat.confirm(OTHER_HOLD, RESERVATION))
                    .isInstanceOf(HoldOwnershipException.class);
        }

        @Test
        void 확정은_상태를_바꾸기_전에_기존_홀드로_소유권을_검사한다() {
            // 확정 성공 시 holdId 를 지우므로, 검사가 변경 뒤에 오면 영영 검사할 수 없게 된다.
            Seat seat = payingSeat();

            assertThatThrownBy(() -> seat.confirm(OTHER_HOLD, RESERVATION))
                    .isInstanceOf(HoldOwnershipException.class);

            assertThat(seat.status()).isEqualTo(SeatStatus.PAYING);
            assertThat(seat.isHeldBy(HOLD)).isTrue();
            assertThat(seat.reservationId()).isEmpty();
        }

        @Test
        void 소유권_위반은_좌석_상태를_바꾸지_않는다() {
            Seat seat = heldSeat();

            assertThatThrownBy(() -> seat.release(OTHER_HOLD))
                    .isInstanceOf(HoldOwnershipException.class);

            assertThat(seat.status()).isEqualTo(SeatStatus.HELD);
            assertThat(seat.isHeldBy(HOLD)).isTrue();
        }
    }

    @Nested
    @DisplayName("경합")
    class 경합 {

        @Test
        void 이미_선점된_좌석은_다시_선점할_수_없다() {
            Seat seat = heldSeat();

            assertThatThrownBy(() -> seat.hold(OTHER_HOLD, new UserId(2L), HOLD_EXPIRES_AT))
                    .isInstanceOf(SeatAlreadyHeldException.class);
        }

        @Test
        void 판매된_좌석은_선점할_수_없다() {
            Seat seat = soldSeat();

            assertThatThrownBy(() -> seat.hold(OTHER_HOLD, new UserId(2L), HOLD_EXPIRES_AT))
                    .isInstanceOf(SeatAlreadyHeldException.class);
        }

        @Test
        void 경합_실패는_기존_홀드를_유지한다() {
            Seat seat = heldSeat();

            assertThatThrownBy(() -> seat.hold(OTHER_HOLD, new UserId(2L), HOLD_EXPIRES_AT))
                    .isInstanceOf(SeatAlreadyHeldException.class);

            assertThat(seat.isHeldBy(HOLD)).isTrue();
        }
    }

    @Nested
    @DisplayName("값 객체")
    class 값_객체 {

        @Test
        void 좌석_식별자는_양수여야_한다() {
            assertThatThrownBy(() -> new SeatId(0L)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void 좌석_번호는_비어_있을_수_없다() {
            assertThatThrownBy(() -> new SeatNo(" ")).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void 홀드_식별자는_비어_있을_수_없다() {
            assertThatThrownBy(() -> HoldId.of("")).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
