package com.railgate.reservation.application.hold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.hold.SeatReleasePort;
import com.railgate.reservation.quota.QuotaAcquireOutcome;
import com.railgate.reservation.quota.QuotaReleaseOutcome;
import com.railgate.reservation.quota.UserHoldQuotaPort;
import com.railgate.reservation.saleevent.SaleEventId;
import com.railgate.reservation.saleevent.SaleEventScopeException;
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
 * 해제 유스케이스의 <b>순서와 실패 계약</b>을 DB 없이 고정한다.
 *
 * <p>{@code HoldSeatsServiceTest} 와 같은 방식이다 — 손으로 만든 기록 스텁으로
 * "무엇을 어떤 순서로 부르는가" 와 "무엇을 전파하는가" 를 본다.
 * 실제 원자성은 {@code ReleaseHoldServiceIntegrationTest} 가 MySQL 로 검증한다.
 */
@DisplayName("ReleaseHoldService — 해제 유스케이스")
class ReleaseHoldServiceTest {

    private static final UserId USER = new UserId(7L);
    private static final HoldId HOLD = HoldId.newId();
    private static final SaleEventId CHUSEOK = new SaleEventId(9001L);

    private RecordingScope scope;
    private RecordingQuota quota;
    private RecordingRelease seats;
    private ReleaseHoldService service;
    private List<String> calls;

    @BeforeEach
    void setUp() {
        calls = new ArrayList<>();
        scope = new RecordingScope(calls);
        quota = new RecordingQuota(calls);
        seats = new RecordingRelease(calls);
        service = new ReleaseHoldService(scope, quota, seats);
    }

    private ReleaseHoldCommand command() {
        return new ReleaseHoldCommand(HOLD, USER);
    }

    @Nested
    @DisplayName("§1 ★ 호출 순서")
    class 호출_순서 {

        /** ★ 회차 해석(비잠금) → quota 잠금 → 좌석 해제 → 실제 수만큼 감소. */
        @Test
        void 회차_해석_다음_quota_잠금_다음_해제_다음_감소() {
            seats.released = 3;

            ReleaseHoldResult result = service.release(command());

            assertThat(calls).containsExactly(
                    "scope.resolveActiveHold",
                    "quota.lockRow",
                    "seats.releaseAll",
                    "quota.release(3)");
            assertThat(result.releasedSeats()).isEqualTo(3);
        }

        /** ★ 회차는 클라이언트가 아니라 활성 홀드에서 해석한 값이다. */
        @Test
        void 해석된_회차로_quota_를_잠그고_줄인다() {
            scope.resolved = Optional.of(new SaleEventId(4242L));
            seats.released = 1;

            service.release(command());

            assertThat(quota.lockedSaleEventId).isEqualTo(new SaleEventId(4242L));
            assertThat(quota.releasedSaleEventId).isEqualTo(new SaleEventId(4242L));
        }

        /** ★ 감소량은 요청·후보 수가 아니라 실제 해제 수다. */
        @Test
        void 실제_해제_수만큼만_줄인다() {
            seats.released = 2;   // 후보는 더 많았을 수 있다 — 스텁은 "실제 바뀐 수" 만 돌려준다

            service.release(command());

            assertThat(calls).contains("quota.release(2)");
            assertThat(calls).filteredOn(c -> c.startsWith("quota.release("))
                    .hasSize(1);
        }
    }

    @Nested
    @DisplayName("§2 ★ 성공 no-op")
    class 성공_no_op {

        /** ★ 활성 좌석이 없으면 quota 를 건드리지 않는다. 원인은 구분하지 않는다. */
        @Test
        void 활성_홀드가_없으면_quota_를_잠그지도_줄이지도_않는다() {
            scope.resolved = Optional.empty();

            ReleaseHoldResult result = service.release(command());

            assertThat(result.releasedSeats()).isZero();
            assertThat(result.released()).isFalse();
            assertThat(calls).containsExactly("scope.resolveActiveHold");
        }

        /** ★ 스냅숏에는 있었지만 실제 해제가 0 이면 감소를 호출하지 않는다. */
        @Test
        void 실제_해제가_0_이면_감소를_호출하지_않는다() {
            seats.released = 0;

            ReleaseHoldResult result = service.release(command());

            assertThat(result.releasedSeats()).isZero();
            assertThat(calls)
                    .containsExactly("scope.resolveActiveHold", "quota.lockRow", "seats.releaseAll")
                    .doesNotContain("quota.release(0)");
        }

        /** 0 건이면 quota 행이 없어도 오류가 아니다 — 줄일 것이 없다. */
        @Test
        void 실제_해제가_0_이면_quota_행이_없어도_오류가_아니다() {
            quota.locked = Optional.empty();
            seats.released = 0;

            ReleaseHoldResult result = service.release(command());

            assertThat(result.releasedSeats()).isZero();
        }
    }

    @Nested
    @DisplayName("§3 ★ 실패 전파")
    class 실패_전파 {

        /** ★ 활성 좌석을 실제로 해제했는데 quota 행이 없다 — 정합성 오류. */
        @Test
        void 해제했는데_quota_행이_없으면_정합성_오류다() {
            quota.locked = Optional.empty();
            seats.released = 2;

            assertThatThrownBy(() -> service.release(command()))
                    .isInstanceOf(QuotaInconsistencyException.class)
                    .hasMessageContaining("행이 없");

            assertThat(calls).doesNotContain("quota.release(2)");
            assertThat(calls).doesNotContain("quota.ensureRow");
        }

        /** ★ 감소가 거절되면 정합성 오류로 전파한다. 삼키지 않는다. */
        @Test
        void 감소가_거절되면_정합성_오류다() {
            quota.releaseOutcome = QuotaReleaseOutcome.REJECTED;
            seats.released = 2;

            assertThatThrownBy(() -> service.release(command()))
                    .isInstanceOf(QuotaInconsistencyException.class)
                    .hasMessageContaining("거절");
        }

        /** 회차 혼합은 쓰기 전에 거절된다. quota 도 좌석도 건드리지 않는다. */
        @Test
        void 회차_혼합은_쓰기_전에_거절된다() {
            scope.failure = new SaleEventScopeException("서로 다른 판매 회차");

            assertThatThrownBy(() -> service.release(command()))
                    .isInstanceOf(SaleEventScopeException.class);

            assertThat(calls).containsExactly("scope.resolveActiveHold");
        }

        /** 배선 실수(트랜잭션 없음)는 정합성 오류와 구별된다. */
        @Test
        void 트랜잭션_배선_실수는_정합성_오류와_구별된다() {
            quota.failure = new IllegalStateException("트랜잭션 없음");

            assertThatThrownBy(() -> service.release(command()))
                    .isInstanceOf(IllegalStateException.class)
                    .isNotInstanceOf(QuotaInconsistencyException.class);
        }

        @Test
        void null_명령을_거부한다() {
            assertThatThrownBy(() -> service.release(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new ReleaseHoldCommand(null, USER))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new ReleaseHoldCommand(HOLD, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // ------------------------------------------------------------------ 스텁

    private static final class RecordingScope implements SaleEventScopePort {
        private final List<String> calls;
        private Optional<SaleEventId> resolved = Optional.of(CHUSEOK);
        private RuntimeException failure;

        private RecordingScope(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public SaleEventId resolve(List<SeatId> seatIds) {
            throw new UnsupportedOperationException("해제 경로는 좌석 목록으로 해석하지 않는다");
        }

        @Override
        public Optional<SaleEventId> resolveActiveHold(HoldId holdId, UserId userId) {
            calls.add("scope.resolveActiveHold");
            if (failure != null) {
                throw failure;
            }
            return resolved;
        }
    }

    private static final class RecordingQuota implements UserHoldQuotaPort {
        private final List<String> calls;
        private Optional<Integer> locked = Optional.of(3);
        private QuotaReleaseOutcome releaseOutcome = QuotaReleaseOutcome.RELEASED;
        private RuntimeException failure;
        private SaleEventId lockedSaleEventId;
        private SaleEventId releasedSaleEventId;

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
            throw new UnsupportedOperationException("해제 경로는 확보하지 않는다");
        }

        @Override
        public Optional<Integer> lockRow(SaleEventId saleEventId, UserId userId) {
            calls.add("quota.lockRow");
            lockedSaleEventId = saleEventId;
            if (failure != null) {
                throw failure;
            }
            return locked;
        }

        @Override
        public QuotaReleaseOutcome release(SaleEventId saleEventId, UserId userId, int seats) {
            calls.add("quota.release(%d)".formatted(seats));
            releasedSaleEventId = saleEventId;
            return releaseOutcome;
        }
    }

    private static final class RecordingRelease implements SeatReleasePort {
        private final List<String> calls;
        private int released = 1;

        private RecordingRelease(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public int releaseAll(HoldId holdId, UserId userId) {
            calls.add("seats.releaseAll");
            return released;
        }
    }
}
