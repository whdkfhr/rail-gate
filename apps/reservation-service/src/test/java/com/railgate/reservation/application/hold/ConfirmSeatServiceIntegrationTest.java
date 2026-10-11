package com.railgate.reservation.application.hold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.ReservationId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.hold.SeatConfirmationOutcome;
import com.railgate.reservation.infra.seat.JdbcSeatPaymentRepository;
import com.railgate.reservation.hold.SeatPaymentOutcome;
import com.railgate.reservation.quota.HoldAttributionException;
import com.railgate.reservation.seat.SeatId;
import com.railgate.reservation.support.MySqlSpringTestSupport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ★ <b>Spring 이 배선한</b> 확정 서비스를 실제 MySQL 에서 호출한다.
 *
 * <p>선점은 {@link HoldSeatsService}, 결제 시작은 <b>기존 {@code startPayment} 저장소</b>로
 * 준비한다 — 결제 시작 서비스는 이 Task 의 범위가 아니라 저장소를 직접 쓴다.
 * 결제 승인 확인은 호출자의 전제이며 이 테스트는 그것을 검증하지 않는다 (I-15 미완성).
 */
@DisplayName("ConfirmSeatService — 실제 MySQL 통합")
class ConfirmSeatServiceIntegrationTest extends MySqlSpringTestSupport {

    private static final long SCHEDULE = 7_500L;
    private static final long OTHER_EVENT = 9_300_000L;
    private static final UserId USER_A = new UserId(41L);
    private static final UserId USER_B = new UserId(42L);
    private static final ReservationId RESERVATION = new ReservationId(8_001L);

    @Autowired
    private HoldSeatsService holdSeats;

    @Autowired
    private ReleaseHoldService releaseHold;

    @Autowired
    private ConfirmSeatService service;

    /** 결제 시작용. 서비스가 없으므로 저장소를 직접 쓴다 (autocommit). */
    private JdbcSeatPaymentRepository payments;

    @BeforeEach
    void wirePayments() {
        payments = new JdbcSeatPaymentRepository(dataSource(), Duration.ofMinutes(5));
    }

    private static List<SeatId> ids(long... values) {
        List<SeatId> list = new ArrayList<>();
        for (long v : values) {
            list.add(new SeatId(v));
        }
        return list;
    }

    private HoldId holdViaService(UserId user, List<SeatId> seats) {
        return holdSeats.hold(new HoldSeatsCommand(user, seats)).holdId();
    }

    /** 선점 → 결제 시작까지 운영 경로로 준비한다. */
    private HoldId payingViaService(UserId user, long seatId) {
        HoldId hold = holdViaService(user, ids(seatId));
        assertThat(payments.startPayment(new SeatId(seatId), hold)).isEqualTo(SeatPaymentOutcome.STARTED);
        return hold;
    }

    private SeatConfirmationOutcome confirm(long seatId, HoldId hold) {
        return service.confirm(new ConfirmSeatCommand(new SeatId(seatId), hold, RESERVATION));
    }

    private void assertStillPaying(long seatId, HoldId hold, UserId user) {
        assertThat(statusOf(seatId)).isEqualTo("PAYING");
        assertThat(holdIdOf(seatId)).isEqualTo(hold.asString());
        assertThat(heldByOf(seatId)).isEqualTo(user.value());
        assertThat(expiresAtOf(seatId)).isNotNull();
        assertThat(reservationIdOf(seatId)).isNull();
    }

    /**
     * 롤백 검증용 좌석 스냅숏. <b>확정 호출 직전</b>에 찍고 트랜잭션 종료 후 같은 값인지 비교한다.
     *
     * <p>{@link #assertStillPaying} 은 "PAYING 이고 홀드가 남아 있다" 까지만 본다. 롤백이
     * <b>모든 필드</b>를 원래 값으로 되돌렸는지는 {@code held_at}·{@code expires_at}·{@code version}
     * 까지 동등 비교해야 안다 — {@code version} 은 UPDATE 가 성공했다 되돌아왔다면 원래 값이어야 한다.
     */
    private record SeatSnapshot(
            String status, Long reservationId, String holdId, Long heldBy,
            java.sql.Timestamp heldAt, java.sql.Timestamp expiresAt, long version) {
    }

    private SeatSnapshot snapshotOf(long seatId) {
        return jdbc().queryForObject("""
                SELECT status, reservation_id, hold_id, held_by, held_at, expires_at, version
                  FROM seat_inventory WHERE id = ?
                """, (rs, i) -> new SeatSnapshot(
                        rs.getString("status"),
                        rs.getObject("reservation_id", Long.class),
                        rs.getString("hold_id"),
                        rs.getObject("held_by", Long.class),
                        rs.getTimestamp("held_at"),
                        rs.getTimestamp("expires_at"),
                        rs.getLong("version")),
                seatId);
    }

    private void assertRestoredTo(long seatId, SeatSnapshot before) {
        SeatSnapshot after = snapshotOf(seatId);
        assertThat(after).as("★ 확정 직전 값으로 모든 필드가 되돌아왔다").isEqualTo(before);
        assertThat(after.status()).isEqualTo("PAYING");
        assertThat(after.reservationId()).isNull();
    }

    @Nested
    @DisplayName("§1 ★ 정상 확정")
    class 정상_확정 {

        @Test
        void SOLD_와_reservation_id_와_홀드_정리와_quota_감소가_함께_반영된다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            HoldId hold = payingViaService(USER_A, a);
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).isEqualTo(1);

            assertThat(confirm(a, hold)).isEqualTo(SeatConfirmationOutcome.CONFIRMED);

            assertThat(statusOf(a)).isEqualTo("SOLD");
            assertThat(reservationIdOf(a)).isEqualTo(RESERVATION.value());
            assertThat(holdIdOf(a)).isNull();
            assertThat(heldByOf(a)).isNull();
            assertThat(expiresAtOf(a)).isNull();
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value()))
                    .as("★ 확정된 1석만큼 준다").isZero();
        }

        /** ★ 여러 홀드·사용자·회차 중 해당 좌석의 quota 만 1 준다. */
        @Test
        void 해당_좌석의_사용자_회차_quota_만_1_준다() {
            long a1 = insertAvailableSeat(SCHEDULE, "1A");
            long a2 = insertAvailableSeat(SCHEDULE, "1B");
            long ay = insertAvailableSeat(8_500L, "1A", OTHER_EVENT);
            long b1 = insertAvailableSeat(SCHEDULE, "2A");
            HoldId holdA = holdViaService(USER_A, ids(a1, a2));         // A / 추석 2석
            HoldId holdAy = holdViaService(USER_A, ids(ay));            // A / 다른 회차 1석
            holdViaService(USER_B, ids(b1));                             // B / 추석 1석
            assertThat(payments.startPayment(new SeatId(a1), holdA)).isEqualTo(SeatPaymentOutcome.STARTED);

            assertThat(confirm(a1, holdA)).isEqualTo(SeatConfirmationOutcome.CONFIRMED);

            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).as("2 → 1").isEqualTo(1);
            assertThat(quotaOf(OTHER_EVENT, USER_A.value())).as("다른 회차 불변").isEqualTo(1);
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_B.value())).as("다른 사용자 불변").isEqualTo(1);
            assertThat(statusOf(a2)).as("같은 홀드의 다른 좌석은 그대로").isEqualTo("HELD");
            assertThat(statusOf(ay)).isEqualTo("HELD");
            assertThat(holdAy).isNotEqualTo(holdA);
        }

        /**
         * ★ 만료 시각이 지난 PAYING 도 확정된다 — 기존 계약(TASK-002B)을 유지한다.
         *
         * <p><b>픽스처 주의.</b> 만료는 직접 SQL 로 {@code expires_at} 을 과거로 돌린 것이며
         * 스위퍼를 거치지 않았다. 관측 범위는 "확정 조건에 만료 시각이 없다" 뿐이다.
         */
        @Test
        void 만료_시각이_지난_PAYING_도_확정되고_quota_가_준다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            HoldId hold = payingViaService(USER_A, a);
            jdbc().update("UPDATE seat_inventory SET expires_at = DATE_SUB(NOW(3), INTERVAL 1 MINUTE) WHERE id = ?", a);

            assertThat(confirm(a, hold)).isEqualTo(SeatConfirmationOutcome.CONFIRMED);

            assertThat(statusOf(a)).isEqualTo("SOLD");
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).isZero();
        }
    }

    @Nested
    @DisplayName("§2 ★ NOT_CONFIRMED — 감소 없음")
    class NOT_CONFIRMED {

        /** ★ 이미 SOLD 인 재요청은 quota 를 다시 줄이지 않는다. 최초 응답 재생도 아니다. */
        @Test
        void 재확정은_NOT_CONFIRMED_이고_quota_를_다시_줄이지_않는다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            HoldId hold = payingViaService(USER_A, a);
            confirm(a, hold);

            assertThat(confirm(a, hold)).isEqualTo(SeatConfirmationOutcome.NOT_CONFIRMED);

            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).as("0 에서 더 줄지 않는다").isZero();
            assertThat(reservationIdOf(a)).as("주인이 바뀌지 않는다").isEqualTo(RESERVATION.value());
        }

        @Test
        void 다른_holdId_는_NOT_CONFIRMED_이고_아무것도_바뀌지_않는다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            HoldId hold = payingViaService(USER_A, a);

            assertThat(confirm(a, HoldId.newId())).isEqualTo(SeatConfirmationOutcome.NOT_CONFIRMED);

            assertStillPaying(a, hold, USER_A);
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).isEqualTo(1);
        }

        @Test
        void 없는_좌석은_NOT_CONFIRMED_이고_quota_행을_만들지_않는다() {
            assertThat(confirm(999_999L, HoldId.newId())).isEqualTo(SeatConfirmationOutcome.NOT_CONFIRMED);
            assertThat(quotaRowCount()).isZero();
        }

        /**
         * ★ <b>stale 스냅숏</b> — 귀속을 읽은 뒤 남이 먼저 확정하면 0 건이고 감소도 없다.
         *
         * <p>결정적으로 만드는 방법: 바깥 트랜잭션에서 먼저 잠금 없는 읽기로 REPEATABLE READ
         * 스냅숏을 고정하고, <b>다른 커넥션(autocommit)</b>으로 좌석을 SOLD 로 커밋한 뒤 서비스를
         * 부른다. 귀속 조회는 옛 스냅숏(PAYING)을 보지만 확정 UPDATE 는 잠금 읽기라 최신(SOLD)을
         * 본다. 스냅숏이 있어도 <b>감소는 UPDATE 결과를 따른다</b>는 것을 고정한다.
         *
         * <p>픽스처 주의: 남의 확정은 직접 SQL 이다. 그래서 남는 quota 1 은 픽스처가 만든 drift 다.
         */
        @Test
        void 귀속_스냅숏_이후_남이_먼저_확정하면_NOT_CONFIRMED_이고_감소가_없다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            HoldId hold = payingViaService(USER_A, a);
            TransactionTemplate outer =
                    new TransactionTemplate(new DataSourceTransactionManager(dataSource()));

            SeatConfirmationOutcome outcome = outer.execute(status -> {
                jdbc().queryForObject("SELECT status FROM seat_inventory WHERE id = ?", String.class, a);
                try (var other = dataSource().getConnection();
                     var ps = other.prepareStatement(
                             "UPDATE seat_inventory SET status='SOLD', reservation_id=777, hold_id=NULL, "
                                     + "held_by=NULL, held_at=NULL, expires_at=NULL WHERE id = ?")) {
                    ps.setLong(1, a);
                    ps.executeUpdate();
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException(e);
                }
                return confirm(a, hold);
            });

            assertThat(outcome).as("★ 스냅숏은 PAYING 이었지만 UPDATE 는 0 건").isEqualTo(SeatConfirmationOutcome.NOT_CONFIRMED);
            assertThat(reservationIdOf(a)).as("남의 확정이 유지된다").isEqualTo(777L);
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value()))
                    .as("★ 감소가 없다 — 1 그대로 (픽스처 drift)").isEqualTo(1);
        }

        /** HELD 상태(결제 시작 전)에서의 확정은 0 건이다. */
        @Test
        void HELD_상태의_확정은_NOT_CONFIRMED_이고_quota_가_그대로다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            HoldId hold = holdViaService(USER_A, ids(a));   // startPayment 없음

            assertThat(confirm(a, hold)).isEqualTo(SeatConfirmationOutcome.NOT_CONFIRMED);

            assertThat(statusOf(a)).isEqualTo("HELD");
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("§3 ★★ 정합성 오류 — SOLD 전환까지 롤백")
    class 정합성_오류 {

        /** 선점 경로를 거치지 않은 PAYING (quota 없음). 활성화 전 backfill 을 거치지 않은 홀드를 흉내 낸다. */
        private HoldId payingDirectlyWithoutQuota(UserId user, long seatId) {
            HoldId hold = HoldId.newId();
            jdbc().update("""
                    UPDATE seat_inventory
                       SET status = 'PAYING', hold_id = ?, held_by = ?,
                           held_at = NOW(3), expires_at = DATE_ADD(NOW(3), INTERVAL 5 MINUTE)
                     WHERE id = ?
                    """, hold.asString(), user.value(), seatId);
            return hold;
        }

        @Test
        void quota_행이_없으면_거절하고_좌석의_모든_필드가_PAYING_으로_남는다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            HoldId hold = payingDirectlyWithoutQuota(USER_A, a);
            SeatSnapshot before = snapshotOf(a);   // 준비가 끝난 뒤, 확정 직전

            assertThatThrownBy(() -> confirm(a, hold))
                    .isInstanceOf(QuotaInconsistencyException.class)
                    .hasMessageContaining("행이 없");

            assertRestoredTo(a, before);
            assertThat(before.holdId()).isEqualTo(hold.asString());
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).as("행을 만들지 않았다").isEqualTo(-1);
        }

        /** quota 가 0 인데 PAYING 이 있다 — 직접 SQL 로 만든 drift. */
        @Test
        void quota_가_부족하면_거절하고_좌석이_PAYING_으로_남는다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            HoldId hold = payingViaService(USER_A, a);
            jdbc().update("UPDATE user_hold_quota SET held_seats = 0 WHERE user_id = ?", USER_A.value());
            SeatSnapshot before = snapshotOf(a);   // 의도적 데이터 변경까지 끝난 뒤, 확정 직전

            assertThatThrownBy(() -> confirm(a, hold))
                    .isInstanceOf(QuotaInconsistencyException.class)
                    .hasMessageContaining("거절");

            assertRestoredTo(a, before);
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).as("값을 보정하지 않았다").isZero();
        }

        /** ★ 손상 데이터 — PAYING 인데 held_by 가 없다. 대상 없음과 구분해 거절한다. */
        @Test
        void held_by_가_없는_PAYING_은_정합성_오류로_거절한다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            HoldId hold = payingViaService(USER_A, a);
            jdbc().update("UPDATE seat_inventory SET held_by = NULL WHERE id = ?", a);

            assertThatThrownBy(() -> confirm(a, hold))
                    .isInstanceOf(HoldAttributionException.class);

            assertThat(statusOf(a)).as("쓰기 전에 거절된다").isEqualTo("PAYING");
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).isEqualTo(1);
        }

        /** ★ 서비스가 성공해도 바깥 트랜잭션이 롤백하면 확정과 감소가 모두 되돌아간다. */
        @Test
        void 외부_롤백이_확정과_감소를_모두_되돌린다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            HoldId hold = payingViaService(USER_A, a);
            TransactionTemplate outer =
                    new TransactionTemplate(new DataSourceTransactionManager(dataSource()));
            SeatSnapshot before = snapshotOf(a);   // 확정 직전

            assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
                assertThat(confirm(a, hold))
                        .as("트랜잭션 안에서는 CONFIRMED — 아직 최종 커밋이 아니다")
                        .isEqualTo(SeatConfirmationOutcome.CONFIRMED);
                assertThat(snapshotOf(a).status()).as("트랜잭션 안에서는 SOLD 로 보인다").isEqualTo("SOLD");
                throw new IllegalStateException("호출자가 롤백시킨다");
            })).isInstanceOf(IllegalStateException.class);

            assertRestoredTo(a, before);
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("§4 ★ 동시 실행 — 보조 검증")
    class 동시_실행 {

        /**
         * ★ 같은 좌석을 동시에 두 번 확정해도 <b>한 번만</b> 확정되고 quota 는 한 번만 준다.
         *
         * <p>둘 다 귀속을 읽고 같은 quota 행을 잠그려 한다. 늦은 쪽의 조건부 UPDATE 는
         * 이미 SOLD 인 행을 보고 0 건이 되어 감소를 호출하지 않는다.
         * 래치는 출발만 맞추므로 교차 순서를 강제하지 않는다 — 결과의 합만 본다.
         */
        @Test
        void 같은_좌석_동시_확정은_한_번만_확정되고_한_번만_준다() throws Exception {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            HoldId hold = payingViaService(USER_A, a);

            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<SeatConfirmationOutcome> outcomes = new ArrayList<>();
            try {
                List<Future<SeatConfirmationOutcome>> futures = List.of(
                        pool.submit(() -> confirmAfter(start, a, hold, new ReservationId(1L))),
                        pool.submit(() -> confirmAfter(start, a, hold, new ReservationId(2L))));
                start.countDown();
                for (Future<SeatConfirmationOutcome> f : futures) {
                    outcomes.add(f.get(30, TimeUnit.SECONDS));
                }
            } finally {
                pool.shutdownNow();
            }

            assertThat(outcomes).containsExactlyInAnyOrder(
                    SeatConfirmationOutcome.CONFIRMED, SeatConfirmationOutcome.NOT_CONFIRMED);
            assertThat(statusOf(a)).isEqualTo("SOLD");
            assertThat(reservationIdOf(a)).as("이긴 쪽의 예약 하나만").isIn(1L, 2L);
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value()))
                    .as("★ 한 번만 줄어 0. 두 번 줄었다면 음수 방어에 걸려 예외가 났을 것이다").isZero();
        }

        /**
         * ★ 확정과 {@link ReleaseHoldService} 가 경쟁해도 이중 감소가 없다.
         *
         * <p>2석 홀드(a, b)에서 a 는 결제 중. 한쪽은 a 를 확정하고 다른 쪽은 홀드를 푼다.
         * 어느 쪽이 먼저 quota 행을 잠그느냐에 따라 확정 성공(a SOLD, b 해제) 또는
         * 확정 실패(둘 다 해제 — PAYING 도 해제 대상이다)로 갈린다. 어느 경우든
         * 카운터는 0 이고 활성 좌석도 0 이다. 분기는 통계적 관측이고 불변식만 확인한다.
         */
        @Test
        void 확정과_해제_경쟁에서_이중_감소가_없다() throws Exception {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long b = insertAvailableSeat(SCHEDULE, "1B");
            HoldId hold = holdViaService(USER_A, ids(a, b));
            assertThat(payments.startPayment(new SeatId(a), hold)).isEqualTo(SeatPaymentOutcome.STARTED);

            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            SeatConfirmationOutcome confirmed;
            int released;
            try {
                Future<SeatConfirmationOutcome> confirming =
                        pool.submit(() -> confirmAfter(start, a, hold, RESERVATION));
                Future<Integer> releasing = pool.submit(() -> {
                    start.await(30, TimeUnit.SECONDS);
                    return releaseHold.release(new ReleaseHoldCommand(hold, USER_A)).releasedSeats();
                });
                start.countDown();
                confirmed = confirming.get(30, TimeUnit.SECONDS);
                released = releasing.get(30, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }

            if (confirmed == SeatConfirmationOutcome.CONFIRMED) {
                assertThat(released).as("확정이 먼저 — 해제는 b 만").isEqualTo(1);
                assertThat(statusOf(a)).isEqualTo("SOLD");
            } else {
                assertThat(released).as("해제가 먼저 — PAYING 인 a 도 풀린다").isEqualTo(2);
                assertThat(statusOf(a)).isEqualTo("AVAILABLE");
            }
            assertThat(statusOf(b)).isEqualTo("AVAILABLE");
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value()))
                    .as("★ 어느 순서든 카운터 = 활성 좌석 수 = 0").isZero();
            assertThat(heldSeatCount()).isZero();
        }

        private SeatConfirmationOutcome confirmAfter(
                CountDownLatch start, long seatId, HoldId hold, ReservationId reservation) throws Exception {
            start.await(30, TimeUnit.SECONDS);
            return service.confirm(new ConfirmSeatCommand(new SeatId(seatId), hold, reservation));
        }
    }

    /**
     * ★ RED 대조군 — 정합성 오류를 삼키면 무슨 일이 일어나는가를 <b>커밋 후</b> 관측한다.
     *
     * <p>§3 는 올바른 구현을 검증한다. 삼키는 구현을 넣으면 {@code assertThatThrownBy} 가 먼저
     * 실패하므로 커밋 후 상태 어서션에 도달하지 못한다 — 그 RED 는 "예외 미발생" 까지만 본다.
     * 커밋 후 상태는 여기서 따로 본다. 운영 서비스에는 이 분기가 없다.
     */
    @Nested
    @DisplayName("§5 ★ RED 대조군 — 정합성 오류를 삼키면")
    class 정합성_오류를_삼킨_대조군 {

        @Autowired
        private com.railgate.reservation.saleevent.SaleEventScopePort scope;

        @Autowired
        private com.railgate.reservation.quota.UserHoldQuotaPort quota;

        @Autowired
        private com.railgate.reservation.hold.SeatConfirmationPort seats;

        /** {@code ConfirmSeatService.confirm} 의 1~4 단계. 차이는 예외 대신 반환뿐이다. */
        private SeatConfirmationOutcome 삼키는_확정(long seatId, HoldId hold) {
            TransactionTemplate boundary =
                    new TransactionTemplate(new DataSourceTransactionManager(dataSource()));
            return boundary.execute(status -> {
                var owner = scope.resolvePayingSeat(new SeatId(seatId), hold).orElseThrow();
                var counter = quota.lockRow(owner.saleEventId(), owner.userId());
                var outcome = seats.confirm(new SeatId(seatId), hold, RESERVATION);
                if (outcome != SeatConfirmationOutcome.CONFIRMED) {
                    return outcome;
                }
                if (counter.isEmpty()) {
                    return outcome;                                   // ★ 삼킨다
                }
                quota.release(owner.saleEventId(), owner.userId(), 1); // ★ 결과를 보지 않는다
                return outcome;
            });
        }

        @Test
        void 행_부재를_삼키면_좌석은_SOLD_로_커밋되고_카운터는_여전히_없다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            HoldId hold = HoldId.newId();
            jdbc().update("""
                    UPDATE seat_inventory SET status = 'PAYING', hold_id = ?, held_by = ?,
                           held_at = NOW(3), expires_at = DATE_ADD(NOW(3), INTERVAL 5 MINUTE)
                     WHERE id = ?
                    """, hold.asString(), USER_A.value(), a);

            assertThat(삼키는_확정(a, hold)).isEqualTo(SeatConfirmationOutcome.CONFIRMED);

            assertThat(statusOf(a)).as("★ SOLD 가 커밋됐다").isEqualTo("SOLD");
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value()))
                    .as("★ 카운터는 만들어지지도 남지도 않았다 — backfill 필요 증거의 소멸").isEqualTo(-1);
        }

        @Test
        void 부족을_삼키면_좌석은_SOLD_로_커밋되고_카운터는_0_으로_남는다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            HoldId hold = payingViaService(USER_A, a);
            jdbc().update("UPDATE user_hold_quota SET held_seats = 0 WHERE user_id = ?", USER_A.value());

            assertThat(삼키는_확정(a, hold)).isEqualTo(SeatConfirmationOutcome.CONFIRMED);

            assertThat(statusOf(a)).isEqualTo("SOLD");
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value()))
                    .as("★ 거절된 감소는 반영되지 않아 0 그대로 — 여기서는 우연히 정합하지만 증거는 사라졌다")
                    .isZero();
        }

        @Test
        void 올바른_구현은_같은_조건에서_PAYING_으로_되돌린다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            HoldId hold = payingViaService(USER_A, a);
            jdbc().update("UPDATE user_hold_quota SET held_seats = 0 WHERE user_id = ?", USER_A.value());

            assertThatThrownBy(() -> confirm(a, hold)).isInstanceOf(QuotaInconsistencyException.class);

            assertStillPaying(a, hold, USER_A);
        }
    }

    @Nested
    @DisplayName("§6 ★ 프록시 없이는 경계가 없다")
    class 프록시_검증 {

        @Autowired
        private com.railgate.reservation.saleevent.SaleEventScopePort scope;

        @Autowired
        private com.railgate.reservation.quota.UserHoldQuotaPort quota;

        @Autowired
        private com.railgate.reservation.hold.SeatConfirmationPort seats;

        @Test
        void 손으로_조립한_서비스는_트랜잭션_없음으로_거부된다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            HoldId hold = payingViaService(USER_A, a);
            ConfirmSeatService unproxied = new ConfirmSeatService(scope, quota, seats);

            assertThatThrownBy(() -> unproxied.confirm(new ConfirmSeatCommand(new SeatId(a), hold, RESERVATION)))
                    .isInstanceOf(IllegalStateException.class);

            assertStillPaying(a, hold, USER_A);
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).isEqualTo(1);
        }
    }
}
