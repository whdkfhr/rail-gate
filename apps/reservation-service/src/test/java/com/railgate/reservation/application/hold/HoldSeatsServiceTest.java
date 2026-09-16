package com.railgate.reservation.application.hold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.hold.SeatHoldPort;
import com.railgate.reservation.quota.QuotaAcquireOutcome;
import com.railgate.reservation.quota.QuotaReleaseOutcome;
import com.railgate.reservation.quota.UserHoldQuotaPort;
import com.railgate.reservation.saleevent.SaleEventId;
import com.railgate.reservation.saleevent.SaleEventScopeException;
import com.railgate.reservation.saleevent.SaleEventScopePort;
import com.railgate.reservation.seat.SeatId;
import com.railgate.reservation.seat.SeatUnavailableException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 선점 유스케이스의 <b>순서와 실패 계약</b>을 DB 없이 고정한다.
 *
 * <p>여기서 검증하는 것은 "무엇을 어떤 순서로 부르는가" 와 "무엇을 거부하는가" 다.
 * 실제 원자성은 {@code HoldSeatsServiceIntegrationTest} 가 MySQL 로 검증한다.
 * <b>둘 중 하나만으로는 부족하다</b> — 순서가 맞아도 트랜잭션 경계가 없으면 깨지고,
 * 통합 테스트만으로는 어느 단계가 빠졌는지 드러나지 않는다.
 *
 * <p>Mockito 를 쓰지 않고 손으로 만든 스텁을 쓴다. 검증 대상이 <b>호출 순서</b>라
 * 기록을 직접 읽는 편이 의도가 분명하고, 스텁이 "트랜잭션 참여 검사" 같은
 * 실제 계약을 흉내 낼 수 있다.
 */
@DisplayName("HoldSeatsService — 선점 유스케이스")
class HoldSeatsServiceTest {

    private static final UserId USER = new UserId(7L);
    private static final SaleEventId CHUSEOK = new SaleEventId(9001L);

    private RecordingScope scope;
    private RecordingQuota quota;
    private RecordingSeats seats;
    private HoldSeatsService service;
    private List<String> calls;

    @BeforeEach
    void setUp() {
        calls = new ArrayList<>();
        scope = new RecordingScope(calls);
        quota = new RecordingQuota(calls);
        seats = new RecordingSeats(calls);
        service = new HoldSeatsService(scope, quota, seats);
    }

    private static List<SeatId> seatIds(long... ids) {
        List<SeatId> list = new ArrayList<>();
        for (long id : ids) {
            list.add(new SeatId(id));
        }
        return list;
    }

    @Nested
    @DisplayName("§1 요청 검증")
    class 요청_검증 {

        @Test
        void null_명령을_거부한다() {
            assertThatThrownBy(() -> service.hold(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void 빈_좌석_목록을_거부한다() {
            assertThatThrownBy(() -> new HoldSeatsCommand(USER, List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void 중복_좌석을_거부한다() {
            assertThatThrownBy(() -> new HoldSeatsCommand(USER, seatIds(1, 2, 1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("중복");
        }

        @Test
        void 상한을_넘는_좌석_수를_거부한다() {
            assertThatThrownBy(() -> new HoldSeatsCommand(USER, seatIds(1, 2, 3, 4, 5)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void 검증_실패는_DB_를_건드리지_않는다() {
            assertThatThrownBy(() -> new HoldSeatsCommand(USER, seatIds(1, 1)))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(calls).as("어떤 포트도 호출되지 않는다").isEmpty();
        }

        @Test
        void 정상_요청은_통과한다() {
            assertThatCode(() -> service.hold(new HoldSeatsCommand(USER, seatIds(3, 1, 2))))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("§2 ★ 호출 순서")
    class 호출_순서 {

        /**
         * ★ quota 를 좌석보다 <b>먼저</b> 잠근다 (TASK-002G-B).
         *
         * <p>순서가 뒤집히면 좌석을 잡은 트랜잭션이 quota 를 기다리고, quota 를 잡은
         * 트랜잭션이 좌석을 기다리는 순환이 생긴다.
         */
        @Test
        void 회차_해석_다음_quota_확보_다음_좌석_선점() {
            service.hold(new HoldSeatsCommand(USER, seatIds(1, 2)));

            assertThat(calls).containsExactly(
                    "scope.resolve",
                    "quota.ensureRow",
                    "quota.tryAcquire(2)",
                    "seats.holdAll");
        }

        /** ★ 클라이언트가 회차를 보내지 않는다 — 좌석에서 해석한 값만 쓴다. */
        @Test
        void 해석된_회차를_quota_범위로_쓴다() {
            scope.resolved = new SaleEventId(4242L);

            HoldSeatsResult result = service.hold(new HoldSeatsCommand(USER, seatIds(1)));

            assertThat(quota.lastSaleEventId).isEqualTo(new SaleEventId(4242L));
            assertThat(result.saleEventId()).isEqualTo(new SaleEventId(4242L));
        }

        @Test
        void 같은_홀드_식별자를_좌석에_전달하고_결과로_돌려준다() {
            HoldSeatsResult result = service.hold(new HoldSeatsCommand(USER, seatIds(1, 2)));

            assertThat(seats.lastHoldId).isEqualTo(result.holdId());
            assertThat(seats.lastUserId).isEqualTo(USER);
            assertThat(result.seatCount()).isEqualTo(2);
        }

        /** ★ 요청 좌석 수만큼 quota 를 확보한다. 1씩 반복하지 않는다. */
        @Test
        void 요청_좌석_수만큼_한_번에_확보한다() {
            service.hold(new HoldSeatsCommand(USER, seatIds(1, 2, 3, 4)));

            assertThat(calls).filteredOn(c -> c.startsWith("quota.tryAcquire"))
                    .containsExactly("quota.tryAcquire(4)");
        }
    }

    @Nested
    @DisplayName("§3 ★ 실패 계약")
    class 실패_계약 {

        /** ★ 상한 초과는 좌석 경합과 <b>구별되는</b> 예외다. */
        @Test
        void 상한_초과는_QuotaExceededException_이다() {
            quota.outcome = QuotaAcquireOutcome.LIMIT_EXCEEDED;

            assertThatThrownBy(() -> service.hold(new HoldSeatsCommand(USER, seatIds(1, 2))))
                    .isInstanceOf(QuotaExceededException.class)
                    .isNotInstanceOf(SeatUnavailableException.class);
        }

        /** ★ 상한 초과면 좌석을 건드리지 않는다. */
        @Test
        void 상한_초과면_좌석_선점을_시도하지_않는다() {
            quota.outcome = QuotaAcquireOutcome.LIMIT_EXCEEDED;

            assertThatThrownBy(() -> service.hold(new HoldSeatsCommand(USER, seatIds(1, 2))))
                    .isInstanceOf(QuotaExceededException.class);

            assertThat(calls).doesNotContain("seats.holdAll");
        }

        /**
         * ★★ 좌석 경합을 <b>삼키지 않고</b> 그대로 전파한다.
         *
         * <p>여기서 잡아 성공으로 처리하면 좌석은 savepoint 로 되돌아가는데
         * quota 증가만 커밋된다. 좌석 없이 카운터만 오르는 상태다.
         */
        @Test
        void 좌석_경합을_그대로_전파한다() {
            seats.failure = new SeatUnavailableException("경합");

            assertThatThrownBy(() -> service.hold(new HoldSeatsCommand(USER, seatIds(1, 2))))
                    .isInstanceOf(SeatUnavailableException.class)
                    .isNotInstanceOf(QuotaExceededException.class)
                    .hasMessage("경합");
        }

        /** ★ 회차 해석 실패도 삼키지 않는다. quota 는 건드리지 않는다. */
        @Test
        void 회차_해석_실패를_그대로_전파한다() {
            scope.failure = new SaleEventScopeException("서로 다른 회차");

            assertThatThrownBy(() -> service.hold(new HoldSeatsCommand(USER, seatIds(1, 2))))
                    .isInstanceOf(SaleEventScopeException.class);

            assertThat(calls).containsExactly("scope.resolve");
        }

        /** 배선 실수(트랜잭션 없음)는 경합이 아니다 — 구별되어야 한다. */
        @Test
        void 트랜잭션_배선_실수는_경합과_구별된다() {
            quota.failure = new IllegalStateException("트랜잭션 없음");

            assertThatThrownBy(() -> service.hold(new HoldSeatsCommand(USER, seatIds(1))))
                    .isInstanceOf(IllegalStateException.class)
                    .isNotInstanceOf(QuotaExceededException.class)
                    .isNotInstanceOf(SeatUnavailableException.class);
        }
    }

    // ------------------------------------------------------------------ 스텁

    private static final class RecordingScope implements SaleEventScopePort {
        private final List<String> calls;
        private SaleEventId resolved = CHUSEOK;
        private RuntimeException failure;

        private RecordingScope(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public SaleEventId resolve(List<SeatId> seatIds) {
            calls.add("scope.resolve");
            if (failure != null) {
                throw failure;
            }
            return resolved;
        }

        @Override
        public Optional<SaleEventId> resolveActiveHold(HoldId holdId, UserId userId) {
            throw new UnsupportedOperationException("선점 경로는 홀드로 해석하지 않는다");
        }
    }

    private static final class RecordingQuota implements UserHoldQuotaPort {
        private final List<String> calls;
        private QuotaAcquireOutcome outcome = QuotaAcquireOutcome.ACQUIRED;
        private RuntimeException failure;
        private SaleEventId lastSaleEventId;

        private RecordingQuota(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public void ensureRow(SaleEventId saleEventId, UserId userId) {
            calls.add("quota.ensureRow");
            if (failure != null) {
                throw failure;
            }
        }

        @Override
        public QuotaAcquireOutcome tryAcquire(SaleEventId saleEventId, UserId userId, int seats) {
            calls.add("quota.tryAcquire(%d)".formatted(seats));
            lastSaleEventId = saleEventId;
            if (failure != null) {
                throw failure;
            }
            return outcome;
        }

        @Override
        public Optional<Integer> lockRow(SaleEventId saleEventId, UserId userId) {
            throw new UnsupportedOperationException("선점 경로는 잠금만 따로 얻지 않는다");
        }

        @Override
        public QuotaReleaseOutcome release(SaleEventId saleEventId, UserId userId, int seats) {
            throw new UnsupportedOperationException("선점 경로는 줄이지 않는다");
        }
    }

    private static final class RecordingSeats implements SeatHoldPort {
        private final List<String> calls;
        private RuntimeException failure;
        private HoldId lastHoldId;
        private UserId lastUserId;

        private RecordingSeats(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public void holdAll(List<SeatId> seatIds, HoldId holdId, UserId userId) {
            calls.add("seats.holdAll");
            lastHoldId = holdId;
            lastUserId = userId;
            if (failure != null) {
                throw failure;
            }
        }
    }
}
