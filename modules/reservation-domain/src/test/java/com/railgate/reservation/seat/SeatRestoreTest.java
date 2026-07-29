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
 * DB 에서 읽은 행을 도메인 객체로 복원하는 경로.
 *
 * <p>복원은 상태 전이를 재실행하지 않는다. SOLD 좌석을 만들려고
 * hold → startPayment → confirm 을 다시 돌리면, 전이 규칙이 바뀔 때마다 복원이 깨지고
 * 존재하지 않는 홀드를 지어내야 한다.
 *
 * <p>대신 {@link SeatSnapshot} 이 "좌석이 가질 수 있는 필드 조합" 을 생성 시점에 검증한다.
 * 이 검증은 INVARIANTS.md 의 정합성 쿼리 V-5(고아 상태)를 런타임에서 탐지하는 지점이기도 하다.
 */
@DisplayName("Seat 복원 - DB 행에서 도메인 객체 재구성")
class SeatRestoreTest {

    private static final SeatId SEAT_ID = new SeatId(1L);
    private static final SeatNo SEAT_NO = new SeatNo("12A");

    private static final Instant EXPIRES_AT = Instant.parse("2026-07-28T00:05:00Z");
    private static final HoldId HOLD = HoldId.of("11111111-1111-1111-1111-111111111111");
    private static final UserId USER = new UserId(1L);
    private static final ReservationId RESERVATION = new ReservationId(100L);

    private static SeatSnapshot available() {
        return new SeatSnapshot(SEAT_ID, SEAT_NO, SeatStatus.AVAILABLE, null, null, null, null);
    }

    private static SeatSnapshot held() {
        return new SeatSnapshot(SEAT_ID, SEAT_NO, SeatStatus.HELD, HOLD, USER, EXPIRES_AT, null);
    }

    private static SeatSnapshot paying() {
        return new SeatSnapshot(SEAT_ID, SEAT_NO, SeatStatus.PAYING, HOLD, USER, EXPIRES_AT, null);
    }

    /** 확정된 좌석은 활성 홀드가 아니다. holdId / heldBy / expiresAt 이 모두 비어 있어야 한다. */
    private static SeatSnapshot sold() {
        return new SeatSnapshot(SEAT_ID, SEAT_NO, SeatStatus.SOLD, null, null, null, RESERVATION);
    }

    @Nested
    @DisplayName("정상 복원")
    class 정상_복원 {

        @Test
        void 판매_가능한_좌석을_복원한다() {
            Seat seat = Seat.restore(available());

            assertThat(seat.status()).isEqualTo(SeatStatus.AVAILABLE);
            assertThat(seat.id()).isEqualTo(SEAT_ID);
            assertThat(seat.seatNo()).isEqualTo(SEAT_NO);
            assertThat(seat.holdId()).isEmpty();
            assertThat(seat.heldBy()).isEmpty();
            assertThat(seat.expiresAt()).isEmpty();
            assertThat(seat.reservationId()).isEmpty();
        }

        @Test
        void 선점된_좌석을_복원한다() {
            Seat seat = Seat.restore(held());

            assertThat(seat.status()).isEqualTo(SeatStatus.HELD);
            assertThat(seat.isHeldBy(HOLD)).isTrue();
            assertThat(seat.heldBy()).contains(USER);
            assertThat(seat.expiresAt()).contains(EXPIRES_AT);
            assertThat(seat.reservationId()).isEmpty();
        }

        @Test
        void 결제_중인_좌석을_복원한다() {
            Seat seat = Seat.restore(paying());

            assertThat(seat.status()).isEqualTo(SeatStatus.PAYING);
            assertThat(seat.isHeldBy(HOLD)).isTrue();
            assertThat(seat.expiresAt()).contains(EXPIRES_AT);
        }

        @Test
        void 확정된_좌석을_복원한다() {
            Seat seat = Seat.restore(sold());

            assertThat(seat.status()).isEqualTo(SeatStatus.SOLD);
            assertThat(seat.reservationId()).contains(RESERVATION);
            assertThat(seat.expiresAt()).isEmpty();
        }

        @Test
        void 복원된_확정_좌석도_활성_홀드가_아니다() {
            Seat seat = Seat.restore(sold());

            assertThat(seat.isHeldBy(HOLD)).isFalse();
            assertThat(seat.holdId()).isEmpty();
            assertThat(seat.heldBy()).isEmpty();
        }
    }

    @Nested
    @DisplayName("복원된 좌석은 온전한 도메인 객체다")
    class 복원_후_행위 {

        @Test
        void 복원된_선점_좌석은_결제를_시작할_수_있다() {
            Seat seat = Seat.restore(held());

            seat.startPayment(HOLD, EXPIRES_AT.plusSeconds(300));

            assertThat(seat.status()).isEqualTo(SeatStatus.PAYING);
        }

        @Test
        void 복원된_결제_중_좌석은_확정할_수_있다() {
            Seat seat = Seat.restore(paying());

            seat.confirm(HOLD, RESERVATION);

            assertThat(seat.status()).isEqualTo(SeatStatus.SOLD);
        }

        @Test
        void 복원된_확정_좌석은_만료되지_않는다() {
            Seat seat = Seat.restore(sold());

            assertThatThrownBy(() -> seat.expire(EXPIRES_AT.plusSeconds(86_400)))
                    .isInstanceOf(SeatStateTransitionException.class);
        }

        @Test
        void 복원된_좌석도_홀드_소유권을_검사한다() {
            Seat seat = Seat.restore(held());
            HoldId other = HoldId.of("22222222-2222-2222-2222-222222222222");

            assertThatThrownBy(() -> seat.release(other))
                    .isInstanceOf(HoldOwnershipException.class);
        }
    }

    @Nested
    @DisplayName("필수 값 누락")
    class 필수_값_누락 {

        @Test
        void 좌석_식별자가_없으면_복원할_수_없다() {
            assertThatThrownBy(() -> new SeatSnapshot(
                    null, SEAT_NO, SeatStatus.AVAILABLE, null, null, null, null))
                    .isInstanceOf(SeatRestoreException.class);
        }

        @Test
        void 좌석_번호가_없으면_복원할_수_없다() {
            assertThatThrownBy(() -> new SeatSnapshot(
                    SEAT_ID, null, SeatStatus.AVAILABLE, null, null, null, null))
                    .isInstanceOf(SeatRestoreException.class);
        }

        @Test
        void 상태가_없으면_복원할_수_없다() {
            assertThatThrownBy(() -> new SeatSnapshot(
                    SEAT_ID, SEAT_NO, null, null, null, null, null))
                    .isInstanceOf(SeatRestoreException.class);
        }

        @Test
        void 스냅샷이_없으면_복원할_수_없다() {
            assertThatThrownBy(() -> Seat.restore(null))
                    .isInstanceOf(SeatRestoreException.class);
        }
    }

    @Nested
    @DisplayName("★ 상태와 필드 조합이 어긋난 데이터는 거부한다")
    class 잘못된_조합 {

        @Test
        void 판매_가능한데_홀드가_남아_있으면_거부한다() {
            assertThatThrownBy(() -> new SeatSnapshot(
                    SEAT_ID, SEAT_NO, SeatStatus.AVAILABLE, HOLD, null, null, null))
                    .isInstanceOf(SeatRestoreException.class);
        }

        @Test
        void 판매_가능한데_만료_시각이_남아_있으면_거부한다() {
            assertThatThrownBy(() -> new SeatSnapshot(
                    SEAT_ID, SEAT_NO, SeatStatus.AVAILABLE, null, null, EXPIRES_AT, null))
                    .isInstanceOf(SeatRestoreException.class);
        }

        @Test
        void 판매_가능한데_예약이_붙어_있으면_거부한다() {
            assertThatThrownBy(() -> new SeatSnapshot(
                    SEAT_ID, SEAT_NO, SeatStatus.AVAILABLE, null, null, null, RESERVATION))
                    .isInstanceOf(SeatRestoreException.class);
        }

        @Test
        void 선점_상태인데_홀드가_없으면_거부한다() {
            assertThatThrownBy(() -> new SeatSnapshot(
                    SEAT_ID, SEAT_NO, SeatStatus.HELD, null, USER, EXPIRES_AT, null))
                    .isInstanceOf(SeatRestoreException.class);
        }

        @Test
        void 선점_상태인데_점유자가_없으면_거부한다() {
            assertThatThrownBy(() -> new SeatSnapshot(
                    SEAT_ID, SEAT_NO, SeatStatus.HELD, HOLD, null, EXPIRES_AT, null))
                    .isInstanceOf(SeatRestoreException.class);
        }

        @Test
        void 선점_상태인데_만료_시각이_없으면_거부한다() {
            // 만료 시각 없는 HELD 는 영원히 풀리지 않는 좌석이 된다 (I-10 위반).
            assertThatThrownBy(() -> new SeatSnapshot(
                    SEAT_ID, SEAT_NO, SeatStatus.HELD, HOLD, USER, null, null))
                    .isInstanceOf(SeatRestoreException.class);
        }

        @Test
        void 선점_상태인데_예약이_붙어_있으면_거부한다() {
            assertThatThrownBy(() -> new SeatSnapshot(
                    SEAT_ID, SEAT_NO, SeatStatus.HELD, HOLD, USER, EXPIRES_AT, RESERVATION))
                    .isInstanceOf(SeatRestoreException.class);
        }

        @Test
        void 결제_중인데_만료_시각이_없으면_거부한다() {
            assertThatThrownBy(() -> new SeatSnapshot(
                    SEAT_ID, SEAT_NO, SeatStatus.PAYING, HOLD, USER, null, null))
                    .isInstanceOf(SeatRestoreException.class);
        }

        @Test
        void 결제_중인데_이미_예약이_붙어_있으면_거부한다() {
            assertThatThrownBy(() -> new SeatSnapshot(
                    SEAT_ID, SEAT_NO, SeatStatus.PAYING, HOLD, USER, EXPIRES_AT, RESERVATION))
                    .isInstanceOf(SeatRestoreException.class);
        }

        @Test
        void 확정_상태인데_예약이_없으면_거부한다() {
            // INVARIANTS.md 검증 쿼리 V-5(고아 상태)가 잡아내는 데이터를
            // 복원 시점에 즉시 거부한다.
            assertThatThrownBy(() -> new SeatSnapshot(
                    SEAT_ID, SEAT_NO, SeatStatus.SOLD, null, null, null, null))
                    .isInstanceOf(SeatRestoreException.class);
        }

        @Test
        void 확정_상태인데_만료_시각이_남아_있으면_거부한다() {
            // 만료될 일이 없는 좌석에 만료 시각이 남아 있으면 상태 조합이 어긋난 것이다.
            // (I-11 자체는 스위퍼의 status IN ('HELD','PAYING') 술어가 지킨다)
            assertThatThrownBy(() -> new SeatSnapshot(
                    SEAT_ID, SEAT_NO, SeatStatus.SOLD, null, null, EXPIRES_AT, RESERVATION))
                    .isInstanceOf(SeatRestoreException.class);
        }

        @Test
        void 확정_상태인데_홀드가_남아_있으면_거부한다() {
            // 확정은 홀드를 소멸시킨다. 남아 있다면 확정 경로가 홀드를 지우지 않았다는 뜻이다.
            assertThatThrownBy(() -> new SeatSnapshot(
                    SEAT_ID, SEAT_NO, SeatStatus.SOLD, HOLD, null, null, RESERVATION))
                    .isInstanceOf(SeatRestoreException.class);
        }

        @Test
        void 확정_상태인데_점유자가_남아_있으면_거부한다() {
            assertThatThrownBy(() -> new SeatSnapshot(
                    SEAT_ID, SEAT_NO, SeatStatus.SOLD, null, USER, null, RESERVATION))
                    .isInstanceOf(SeatRestoreException.class);
        }

        @Test
        void 거부_사유가_메시지에_드러난다() {
            assertThatThrownBy(() -> new SeatSnapshot(
                    SEAT_ID, SEAT_NO, SeatStatus.SOLD, null, null, null, null))
                    .hasMessageContaining("SOLD")
                    .hasMessageContaining("reservationId");
        }
    }
}
