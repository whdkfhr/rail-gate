package com.railgate.reservation.hold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.seat.Seat;
import com.railgate.reservation.seat.SeatAlreadyHeldException;
import com.railgate.reservation.seat.SeatId;
import com.railgate.reservation.seat.SeatNo;
import com.railgate.reservation.seat.SeatStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * FR-2.3 / I-9 여러 좌석 선점의 전부-또는-전무 규칙.
 *
 * 표현과 강제의 구분:
 *  - 표현: 하나라도 선점 불가면 어떤 좌석도 바뀌지 않는다는 규칙
 *  - 강제: 실제 원자성은 단일 벌크 UPDATE 의 affected_rows 검증이 한다.
 *          이 정책 객체는 단일 스레드에서만 유효하며 동시성 보장이 없다.
 *
 * 잠금 순서(lockOrder)를 도메인이 제공하는 이유는 CLAUDE.md 규칙 2 때문이다.
 * 여러 행을 잠그는 트랜잭션은 예외 없이 SeatId 오름차순으로 접근해야 데드락이 없다.
 */
@DisplayName("SeatHoldPolicy - FR-2.3 / I-9 전부-또는-전무")
class SeatHoldPolicyTest {

    private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");
    private static final Instant EXPIRES_AT = NOW.plusSeconds(300);

    private static final HoldId HOLD = HoldId.of("11111111-1111-1111-1111-111111111111");
    private static final HoldId OTHER_HOLD = HoldId.of("22222222-2222-2222-2222-222222222222");
    private static final UserId USER = new UserId(1L);

    private static Seat seat(long id) {
        return Seat.available(new SeatId(id), new SeatNo(id + "A"));
    }

    @Nested
    @DisplayName("전부 성공")
    class 전부_성공 {

        @Test
        void 모두_판매_가능하면_전부_선점된다() {
            List<Seat> seats = List.of(seat(1), seat(2), seat(3));

            SeatHoldPolicy.holdAll(seats, HOLD, USER, EXPIRES_AT);

            assertThat(seats).allSatisfy(s -> {
                assertThat(s.status()).isEqualTo(SeatStatus.HELD);
                assertThat(s.isHeldBy(HOLD)).isTrue();
            });
        }

        @Test
        void 선점된_좌석_수를_돌려준다() {
            List<Seat> seats = List.of(seat(1), seat(2));

            SeatCount held = SeatHoldPolicy.holdAll(seats, HOLD, USER, EXPIRES_AT);

            assertThat(held).isEqualTo(new SeatCount(2));
        }

        @Test
        void 한_좌석만_선점할_수_있다() {
            List<Seat> seats = List.of(seat(1));

            SeatHoldPolicy.holdAll(seats, HOLD, USER, EXPIRES_AT);

            assertThat(seats.get(0).status()).isEqualTo(SeatStatus.HELD);
        }

        @Test
        void 네_좌석까지_선점할_수_있다() {
            List<Seat> seats = List.of(seat(1), seat(2), seat(3), seat(4));

            SeatHoldPolicy.holdAll(seats, HOLD, USER, EXPIRES_AT);

            assertThat(seats).allMatch(s -> s.status() == SeatStatus.HELD);
        }
    }

    @Nested
    @DisplayName("★ 전부 실패")
    class 전부_실패 {

        @Test
        void 하나라도_선점_불가면_전체가_실패한다() {
            Seat taken = seat(2);
            taken.hold(OTHER_HOLD, new UserId(9L), EXPIRES_AT);
            List<Seat> seats = List.of(seat(1), taken, seat(3));

            assertThatThrownBy(() -> SeatHoldPolicy.holdAll(seats, HOLD, USER, EXPIRES_AT))
                    .isInstanceOf(SeatAlreadyHeldException.class);
        }

        @Test
        void 실패하면_어떤_좌석도_선점되지_않는다() {
            Seat taken = seat(2);
            taken.hold(OTHER_HOLD, new UserId(9L), EXPIRES_AT);
            Seat first = seat(1);
            Seat third = seat(3);
            List<Seat> seats = List.of(first, taken, third);

            assertThatThrownBy(() -> SeatHoldPolicy.holdAll(seats, HOLD, USER, EXPIRES_AT))
                    .isInstanceOf(SeatAlreadyHeldException.class);

            // 부분 선점이 남으면 I-9 위반이다.
            assertThat(first.status()).isEqualTo(SeatStatus.AVAILABLE);
            assertThat(third.status()).isEqualTo(SeatStatus.AVAILABLE);
            assertThat(taken.isHeldBy(OTHER_HOLD)).isTrue();
        }

        @Test
        void 마지막_좌석이_불가여도_앞_좌석들이_선점되지_않는다() {
            Seat taken = seat(3);
            taken.hold(OTHER_HOLD, new UserId(9L), EXPIRES_AT);
            Seat first = seat(1);
            Seat second = seat(2);

            assertThatThrownBy(
                    () -> SeatHoldPolicy.holdAll(List.of(first, second, taken), HOLD, USER, EXPIRES_AT))
                    .isInstanceOf(SeatAlreadyHeldException.class);

            assertThat(first.status()).isEqualTo(SeatStatus.AVAILABLE);
            assertThat(second.status()).isEqualTo(SeatStatus.AVAILABLE);
        }
    }

    @Nested
    @DisplayName("요청 검증")
    class 요청_검증 {

        @Test
        void 좌석이_없으면_선점할_수_없다() {
            assertThatThrownBy(() -> SeatHoldPolicy.holdAll(List.of(), HOLD, USER, EXPIRES_AT))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void 다섯_좌석_이상은_선점할_수_없다() {
            List<Seat> seats = List.of(seat(1), seat(2), seat(3), seat(4), seat(5));

            assertThatThrownBy(() -> SeatHoldPolicy.holdAll(seats, HOLD, USER, EXPIRES_AT))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void 같은_좌석을_중복해서_요청할_수_없다() {
            Seat duplicated = seat(1);

            assertThatThrownBy(
                    () -> SeatHoldPolicy.holdAll(List.of(duplicated, duplicated), HOLD, USER, EXPIRES_AT))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void 좌석_식별자가_같으면_다른_인스턴스여도_중복이다() {
            assertThatThrownBy(
                    () -> SeatHoldPolicy.holdAll(List.of(seat(1), seat(1)), HOLD, USER, EXPIRES_AT))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("잠금 순서 (CLAUDE.md 규칙 2)")
    class 잠금_순서 {

        @Test
        void 잠금_순서는_좌석_식별자_오름차순이다() {
            List<SeatId> unordered = List.of(new SeatId(30), new SeatId(10), new SeatId(20));

            List<SeatId> ordered = SeatHoldPolicy.lockOrder(unordered);

            assertThat(ordered).containsExactly(new SeatId(10), new SeatId(20), new SeatId(30));
        }

        @Test
        void 잠금_순서를_구해도_원본은_변하지_않는다() {
            List<SeatId> unordered = List.of(new SeatId(30), new SeatId(10));

            SeatHoldPolicy.lockOrder(unordered);

            assertThat(unordered).containsExactly(new SeatId(30), new SeatId(10));
        }

        @Test
        void 입력_순서와_무관하게_같은_잠금_순서가_나온다() {
            List<SeatId> a = List.of(new SeatId(3), new SeatId(1), new SeatId(2));
            List<SeatId> b = List.of(new SeatId(2), new SeatId(3), new SeatId(1));

            assertThat(SeatHoldPolicy.lockOrder(a)).isEqualTo(SeatHoldPolicy.lockOrder(b));
        }
    }
}
