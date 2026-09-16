package com.railgate.reservation.application.hold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.ReservationId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.hold.SeatConfirmationOutcome;
import com.railgate.reservation.hold.SeatConfirmationPort;
import com.railgate.reservation.quota.HoldAttribution;
import com.railgate.reservation.quota.HoldAttributionException;
import com.railgate.reservation.quota.QuotaAcquireOutcome;
import com.railgate.reservation.quota.QuotaReleaseOutcome;
import com.railgate.reservation.quota.UserHoldQuotaPort;
import com.railgate.reservation.saleevent.SaleEventId;
import com.railgate.reservation.saleevent.SaleEventScopePort;
import com.railgate.reservation.seat.SeatId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 확정 유스케이스의 <b>순서와 실패 계약</b>을 DB 없이 고정한다.
 * 실제 원자성은 {@code ConfirmSeatServiceIntegrationTest} 가 MySQL 로 검증한다.
 */
@DisplayName("ConfirmSeatService — 확정 유스케이스")
class ConfirmSeatServiceTest {

    private static final SeatId SEAT = new SeatId(101L);
    private static final HoldId HOLD = HoldId.newId();
    private static final ReservationId RESERVATION = new ReservationId(5001L);
    private static final HoldAttribution OWNER_IN_CHUSEOK =
            new HoldAttribution(new UserId(7L), new SaleEventId(9001L));

    private RecordingScope scope;
    private RecordingQuota quota;
    private RecordingConfirm seats;
    private ConfirmSeatService service;
    private List<String> calls;

    @BeforeEach
    void setUp() {
        calls = new ArrayList<>();
        scope = new RecordingScope(calls);
        quota = new RecordingQuota(calls);
        seats = new RecordingConfirm(calls);
        service = new ConfirmSeatService(scope, quota, seats);
    }

    private ConfirmSeatCommand command() {
        return new ConfirmSeatCommand(SEAT, HOLD, RESERVATION);
    }

    @Nested
    @DisplayName("§1 ★ 호출 순서")
    class 호출_순서 {

        /** ★ 귀속 조회(비잠금) → quota 잠금 → 조건부 확정 → 성공한 1석만 감소. */
        @Test
        void 귀속_조회_다음_quota_잠금_다음_확정_다음_1_감소() {
            SeatConfirmationOutcome outcome = service.confirm(command());

            assertThat(outcome).isEqualTo(SeatConfirmationOutcome.CONFIRMED);
            assertThat(calls).containsExactly(
                    "scope.resolvePayingSeat",
                    "quota.lockRow",
                    "seats.confirm",
                    "quota.release(1)");
        }

        /** ★ quota 의 사용자·회차는 클라이언트가 아니라 좌석에서 해석한 값이다. */
        @Test
        void DB_에서_해석한_귀속_정보로_quota_를_잠그고_줄인다() {
            scope.resolved = Optional.of(new HoldAttribution(new UserId(42L), new SaleEventId(4242L)));

            service.confirm(command());

            assertThat(quota.lockedUser).isEqualTo(new UserId(42L));
            assertThat(quota.lockedSaleEventId).isEqualTo(new SaleEventId(4242L));
            assertThat(quota.releasedUser).isEqualTo(new UserId(42L));
        }

        @Test
        void 확정에_같은_seatId_holdId_reservationId_를_전달한다() {
            service.confirm(command());

            assertThat(seats.lastSeatId).isEqualTo(SEAT);
            assertThat(seats.lastHoldId).isEqualTo(HOLD);
            assertThat(seats.lastReservationId).isEqualTo(RESERVATION);
        }
    }

    @Nested
    @DisplayName("§2 ★ NOT_CONFIRMED — 감소 없음")
    class NOT_CONFIRMED {

        /** ★ 귀속 대상이 없으면 quota 를 잠그지도 줄이지도 않는다. */
        @Test
        void 귀속_대상이_없으면_NOT_CONFIRMED_이고_quota_를_건드리지_않는다() {
            scope.resolved = Optional.empty();

            assertThat(service.confirm(command())).isEqualTo(SeatConfirmationOutcome.NOT_CONFIRMED);
            assertThat(calls).containsExactly("scope.resolvePayingSeat");
        }

        /** ★ 스냅숏에는 있었지만 조건부 UPDATE 가 0 건이면 감소를 호출하지 않는다. */
        @Test
        void 확정_0_건이면_감소를_호출하지_않는다() {
            seats.outcome = SeatConfirmationOutcome.NOT_CONFIRMED;

            assertThat(service.confirm(command())).isEqualTo(SeatConfirmationOutcome.NOT_CONFIRMED);
            assertThat(calls)
                    .containsExactly("scope.resolvePayingSeat", "quota.lockRow", "seats.confirm")
                    .doesNotContain("quota.release(1)");
        }

        @Test
        void 확정_0_건이면_quota_행이_없어도_오류가_아니다() {
            quota.locked = Optional.empty();
            seats.outcome = SeatConfirmationOutcome.NOT_CONFIRMED;

            assertThat(service.confirm(command())).isEqualTo(SeatConfirmationOutcome.NOT_CONFIRMED);
        }
    }

    @Nested
    @DisplayName("§3 ★ 실패 전파")
    class 실패_전파 {

        @Test
        void 확정됐는데_quota_행이_없으면_정합성_오류다() {
            quota.locked = Optional.empty();

            assertThatThrownBy(() -> service.confirm(command()))
                    .isInstanceOf(QuotaInconsistencyException.class)
                    .hasMessageContaining("행이 없");
            assertThat(calls).doesNotContain("quota.release(1)", "quota.ensureRow");
        }

        @Test
        void 감소가_거절되면_정합성_오류다() {
            quota.releaseOutcome = QuotaReleaseOutcome.REJECTED;

            assertThatThrownBy(() -> service.confirm(command()))
                    .isInstanceOf(QuotaInconsistencyException.class)
                    .hasMessageContaining("거절");
        }

        /** 손상 데이터(held_by 없음)는 쓰기 전에 거절된다. NOT_CONFIRMED 와 다르다. */
        @Test
        void 귀속_손상은_쓰기_전에_전파된다() {
            scope.failure = new HoldAttributionException("held_by 없음");

            assertThatThrownBy(() -> service.confirm(command()))
                    .isInstanceOf(HoldAttributionException.class);
            assertThat(calls).containsExactly("scope.resolvePayingSeat");
        }

        @Test
        void 트랜잭션_배선_실수는_정합성_오류와_구별된다() {
            quota.failure = new IllegalStateException("트랜잭션 없음");

            assertThatThrownBy(() -> service.confirm(command()))
                    .isInstanceOf(IllegalStateException.class)
                    .isNotInstanceOf(QuotaInconsistencyException.class);
        }

        @Test
        void null_을_거부한다() {
            assertThatThrownBy(() -> service.confirm(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new ConfirmSeatCommand(null, HOLD, RESERVATION))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new ConfirmSeatCommand(SEAT, null, RESERVATION))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new ConfirmSeatCommand(SEAT, HOLD, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // ------------------------------------------------------------------ 스텁

    private static final class RecordingScope implements SaleEventScopePort {
        private final List<String> calls;
        private Optional<HoldAttribution> resolved = Optional.of(OWNER_IN_CHUSEOK);
        private RuntimeException failure;

        private RecordingScope(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public SaleEventId resolve(List<SeatId> seatIds) {
            throw new UnsupportedOperationException("확정 경로는 좌석 목록으로 해석하지 않는다");
        }

        @Override
        public Optional<SaleEventId> resolveActiveHold(HoldId holdId, UserId userId) {
            throw new UnsupportedOperationException("확정 경로는 홀드 전체로 해석하지 않는다");
        }

        @Override
        public Optional<HoldAttribution> resolvePayingSeat(SeatId seatId, HoldId holdId) {
            calls.add("scope.resolvePayingSeat");
            if (failure != null) {
                throw failure;
            }
            return resolved;
        }
    }

    private static final class RecordingQuota implements UserHoldQuotaPort {
        private final List<String> calls;
        private Optional<Integer> locked = Optional.of(2);
        private QuotaReleaseOutcome releaseOutcome = QuotaReleaseOutcome.RELEASED;
        private RuntimeException failure;
        private UserId lockedUser;
        private SaleEventId lockedSaleEventId;
        private UserId releasedUser;

        private RecordingQuota(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public void ensureRow(SaleEventId saleEventId, UserId userId) {
            calls.add("quota.ensureRow");
            throw new AssertionError("★ 감소 경로는 행을 만들지 않는다");
        }

        @Override
        public QuotaAcquireOutcome tryAcquire(SaleEventId saleEventId, UserId userId, int seats) {
            throw new UnsupportedOperationException("확정 경로는 확보하지 않는다");
        }

        @Override
        public Optional<Integer> lockRow(SaleEventId saleEventId, UserId userId) {
            calls.add("quota.lockRow");
            lockedUser = userId;
            lockedSaleEventId = saleEventId;
            if (failure != null) {
                throw failure;
            }
            return locked;
        }

        @Override
        public QuotaReleaseOutcome release(SaleEventId saleEventId, UserId userId, int seats) {
            calls.add("quota.release(%d)".formatted(seats));
            releasedUser = userId;
            return releaseOutcome;
        }
    }

    private static final class RecordingConfirm implements SeatConfirmationPort {
        private final List<String> calls;
        private SeatConfirmationOutcome outcome = SeatConfirmationOutcome.CONFIRMED;
        private SeatId lastSeatId;
        private HoldId lastHoldId;
        private ReservationId lastReservationId;

        private RecordingConfirm(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public SeatConfirmationOutcome confirm(SeatId seatId, HoldId holdId, ReservationId reservationId) {
            calls.add("seats.confirm");
            lastSeatId = seatId;
            lastHoldId = holdId;
            lastReservationId = reservationId;
            return outcome;
        }
    }
}
