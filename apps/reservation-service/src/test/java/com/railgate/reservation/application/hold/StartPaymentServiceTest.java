package com.railgate.reservation.application.hold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.hold.SeatPaymentOutcome;
import com.railgate.reservation.hold.SeatPaymentPort;
import com.railgate.reservation.seat.SeatId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 결제 시작 유스케이스의 <b>전달과 반환 계약</b>을 DB 없이 고정한다.
 * 실제 조건부 UPDATE·트랜잭션 참여는 {@code StartPaymentServiceIntegrationTest} 가 MySQL 로 검증한다.
 */
@DisplayName("StartPaymentService — 결제 시작 유스케이스")
class StartPaymentServiceTest {

    private static final SeatId SEAT = new SeatId(101L);
    private static final HoldId HOLD = HoldId.newId();

    private RecordingPayment seats;
    private StartPaymentService service;

    @BeforeEach
    void setUp() {
        seats = new RecordingPayment();
        service = new StartPaymentService(seats);
    }

    @Nested
    @DisplayName("§1 포트 전달값과 반환")
    class 전달과_반환 {

        @Test
        void 같은_seatId_holdId_를_한_번_전달한다() {
            service.start(new StartPaymentCommand(SEAT, HOLD));

            assertThat(seats.calls).containsExactly(new Call(SEAT, HOLD));
        }

        @Test
        void STARTED_를_그대로_돌려준다() {
            seats.outcome = SeatPaymentOutcome.STARTED;

            assertThat(service.start(new StartPaymentCommand(SEAT, HOLD))).isEqualTo(SeatPaymentOutcome.STARTED);
        }

        /** ★ NOT_STARTED 는 정상 경합 결과다. 예외로 바꾸거나 성공으로 위장하지 않는다. */
        @Test
        void NOT_STARTED_를_예외_없이_그대로_돌려준다() {
            seats.outcome = SeatPaymentOutcome.NOT_STARTED;

            assertThat(service.start(new StartPaymentCommand(SEAT, HOLD)))
                    .isEqualTo(SeatPaymentOutcome.NOT_STARTED);
            assertThat(seats.calls).as("재시도하거나 추가 조회하지 않는다").hasSize(1);
        }
    }

    @Nested
    @DisplayName("§2 입력 검증과 예외 전파")
    class 입력과_예외 {

        @Test
        void null_명령은_포트를_부르기_전에_거부한다() {
            assertThatThrownBy(() -> service.start(null)).isInstanceOf(NullPointerException.class);
            assertThat(seats.calls).isEmpty();
        }

        @Test
        void 명령의_null_필드를_거부한다() {
            assertThatThrownBy(() -> new StartPaymentCommand(null, HOLD))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("seatId");
            assertThatThrownBy(() -> new StartPaymentCommand(SEAT, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("holdId");
        }

        @Test
        void null_포트로는_만들_수_없다() {
            assertThatThrownBy(() -> new StartPaymentService(null)).isInstanceOf(NullPointerException.class);
        }

        /** 저장소 예외(잠금 대기 초과 등)를 NOT_STARTED 로 삼키지 않는다. 롤백은 경계가 맡는다. */
        @Test
        void 포트_예외를_그대로_전파한다() {
            IllegalStateException failure = new IllegalStateException("lock wait timeout");
            seats.failure = failure;

            assertThatThrownBy(() -> service.start(new StartPaymentCommand(SEAT, HOLD))).isSameAs(failure);
        }
    }

    // ------------------------------------------------------------------ 스텁

    private record Call(SeatId seatId, HoldId holdId) {
    }

    private static final class RecordingPayment implements SeatPaymentPort {
        private final List<Call> calls = new ArrayList<>();
        private SeatPaymentOutcome outcome = SeatPaymentOutcome.STARTED;
        private RuntimeException failure;

        @Override
        public SeatPaymentOutcome startPayment(SeatId seatId, HoldId holdId) {
            calls.add(new Call(seatId, holdId));
            if (failure != null) {
                throw failure;
            }
            return outcome;
        }
    }
}
