package com.railgate.reservation.seat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.ReservationId;
import com.railgate.reservation.UserId;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * I-14 상태 전이표 전수 검증.
 *
 * 4개 상태 × 5개 행위 = 20개 조합을 빠짐없이 검증한다.
 * 새 상태나 행위를 추가하면 이 표가 먼저 깨지므로, 전이 정의를 빠뜨릴 수 없다.
 *
 * <pre>
 *            hold            startPayment    confirm         release         expire
 * AVAILABLE  → HELD          ✗ transition    ✗ transition    ✗ transition    ✗ transition
 * HELD       ✗ alreadyHeld   → PAYING        ✗ transition    → AVAILABLE     → AVAILABLE
 * PAYING     ✗ alreadyHeld   ✗ transition    → SOLD          → AVAILABLE     → AVAILABLE
 * SOLD       ✗ alreadyHeld   ✗ transition    ✗ transition    ✗ transition    ✗ transition ★I-11
 * </pre>
 */
@DisplayName("Seat 전이표 - I-14 정의된 전이만 허용한다")
class SeatTransitionTableTest {

    private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");
    private static final Instant HOLD_EXPIRES_AT = NOW.plusSeconds(300);
    private static final Instant PAYMENT_DEADLINE = NOW.plusSeconds(600);
    private static final Instant AFTER_EVERYTHING = NOW.plusSeconds(9999);

    // 이름을 HOLD 로 두면 아래 SeatAction.HOLD 상수에 가려져 자기참조가 된다.
    private static final HoldId HOLD_ID = HoldId.of("11111111-1111-1111-1111-111111111111");
    private static final UserId USER = new UserId(1L);
    private static final ReservationId RESERVATION = new ReservationId(100L);

    /** 좌석에 가할 수 있는 모든 도메인 행위. */
    private enum SeatAction {
        HOLD(seat -> seat.hold(HOLD_ID, USER, HOLD_EXPIRES_AT)),
        START_PAYMENT(seat -> seat.startPayment(HOLD_ID, PAYMENT_DEADLINE)),
        CONFIRM(seat -> seat.confirm(HOLD_ID, RESERVATION)),
        RELEASE(seat -> seat.release(HOLD_ID)),
        // 만료 판정 시각은 항상 파라미터로 받는다 (CLAUDE.md 규칙 7).
        EXPIRE(seat -> seat.expire(AFTER_EVERYTHING));

        private final java.util.function.Consumer<Seat> action;

        SeatAction(java.util.function.Consumer<Seat> action) {
            this.action = action;
        }

        void applyTo(Seat seat) {
            action.accept(seat);
        }
    }

    private static Seat seatIn(SeatStatus status) {
        Seat seat = Seat.available(new SeatId(1L), new SeatNo("12A"));
        if (status == SeatStatus.AVAILABLE) {
            return seat;
        }
        seat.hold(HOLD_ID, USER, HOLD_EXPIRES_AT);
        if (status == SeatStatus.HELD) {
            return seat;
        }
        seat.startPayment(HOLD_ID, PAYMENT_DEADLINE);
        if (status == SeatStatus.PAYING) {
            return seat;
        }
        seat.confirm(HOLD_ID, RESERVATION);
        return seat;
    }

    private static Arguments allowed(SeatStatus from, SeatAction action, SeatStatus to) {
        return Arguments.of(from, action, to);
    }

    private static Arguments forbidden(SeatStatus from, SeatAction action, Class<?> exception) {
        return Arguments.of(from, action, exception);
    }

    static Stream<Arguments> 허용되는_전이() {
        return Stream.of(
                allowed(SeatStatus.AVAILABLE, SeatAction.HOLD, SeatStatus.HELD),
                allowed(SeatStatus.HELD, SeatAction.START_PAYMENT, SeatStatus.PAYING),
                allowed(SeatStatus.HELD, SeatAction.RELEASE, SeatStatus.AVAILABLE),
                allowed(SeatStatus.HELD, SeatAction.EXPIRE, SeatStatus.AVAILABLE),
                allowed(SeatStatus.PAYING, SeatAction.CONFIRM, SeatStatus.SOLD),
                allowed(SeatStatus.PAYING, SeatAction.RELEASE, SeatStatus.AVAILABLE),
                allowed(SeatStatus.PAYING, SeatAction.EXPIRE, SeatStatus.AVAILABLE));
    }

    static Stream<Arguments> 금지되는_전이() {
        return Stream.of(
                forbidden(SeatStatus.AVAILABLE, SeatAction.START_PAYMENT, SeatStateTransitionException.class),
                forbidden(SeatStatus.AVAILABLE, SeatAction.CONFIRM, SeatStateTransitionException.class),
                forbidden(SeatStatus.AVAILABLE, SeatAction.RELEASE, SeatStateTransitionException.class),
                forbidden(SeatStatus.AVAILABLE, SeatAction.EXPIRE, SeatStateTransitionException.class),

                forbidden(SeatStatus.HELD, SeatAction.HOLD, SeatAlreadyHeldException.class),
                forbidden(SeatStatus.HELD, SeatAction.CONFIRM, SeatStateTransitionException.class),

                forbidden(SeatStatus.PAYING, SeatAction.HOLD, SeatAlreadyHeldException.class),
                forbidden(SeatStatus.PAYING, SeatAction.START_PAYMENT, SeatStateTransitionException.class),

                // ★ SOLD 는 터미널이다. 어떤 행위로도 되돌릴 수 없다.
                forbidden(SeatStatus.SOLD, SeatAction.HOLD, SeatAlreadyHeldException.class),
                forbidden(SeatStatus.SOLD, SeatAction.START_PAYMENT, SeatStateTransitionException.class),
                forbidden(SeatStatus.SOLD, SeatAction.CONFIRM, SeatStateTransitionException.class),
                forbidden(SeatStatus.SOLD, SeatAction.RELEASE, SeatStateTransitionException.class),
                forbidden(SeatStatus.SOLD, SeatAction.EXPIRE, SeatStateTransitionException.class));
    }

    @ParameterizedTest(name = "{0} 상태에서 {1} 하면 {2} 가 된다")
    @MethodSource("허용되는_전이")
    void 정의된_전이는_허용된다(SeatStatus from, SeatAction action, SeatStatus expected) {
        Seat seat = seatIn(from);

        action.applyTo(seat);

        assertThat(seat.status()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} 상태에서 {1} 하면 {2} 로 거부된다")
    @MethodSource("금지되는_전이")
    void 정의되지_않은_전이는_거부된다(SeatStatus from, SeatAction action, Class<? extends Throwable> expected) {
        Seat seat = seatIn(from);

        assertThatThrownBy(() -> action.applyTo(seat)).isInstanceOf(expected);
    }

    @ParameterizedTest(name = "{0} 상태에서 거부된 {1} 는 상태를 바꾸지 않는다")
    @MethodSource("금지되는_전이")
    void 거부된_전이는_상태를_바꾸지_않는다(SeatStatus from, SeatAction action, Class<? extends Throwable> expected) {
        Seat seat = seatIn(from);

        assertThatThrownBy(() -> action.applyTo(seat)).isInstanceOf(expected);

        assertThat(seat.status()).isEqualTo(from);
    }

    @Test
    void 전이표가_모든_상태와_행위_조합을_덮는다() {
        long covered = 허용되는_전이().count() + 금지되는_전이().count();
        long total = (long) SeatStatus.values().length * SeatAction.values().length;

        assertThat(covered)
                .as("상태 %d개 × 행위 %d개 조합이 모두 표에 있어야 한다", SeatStatus.values().length,
                        SeatAction.values().length)
                .isEqualTo(total);
    }
}
