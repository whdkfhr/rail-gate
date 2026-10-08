package com.railgate.reservation.application.hold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.application.expiry.ExpireHoldsService;
import com.railgate.reservation.application.expiry.ExpirySweepResult;
import com.railgate.reservation.expiry.ExpiredSeat;
import com.railgate.reservation.expiry.ExpiryCandidate;
import com.railgate.reservation.expiry.ExpiryCursor;
import com.railgate.reservation.expiry.SeatExpiryPort;
import com.railgate.reservation.hold.SeatConfirmationPort;
import com.railgate.reservation.hold.SeatPaymentOutcome;
import com.railgate.reservation.hold.SeatPaymentPort;
import com.railgate.reservation.quota.UserHoldQuotaPort;
import com.railgate.reservation.seat.SeatId;
import com.railgate.reservation.support.MySqlSpringTestSupport;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ★ <b>Spring 이 배선한</b> 결제 시작 서비스를 실제 MySQL 에서 호출한다.
 *
 * <p>선점은 {@link HoldSeatsService} 로 준비한다. 시각 비교는 모두 <b>DB 시각</b>({@code NOW(3)})
 * 기준이며 애플리케이션 시계를 쓰지 않는다. 만료는 직접 SQL 로 {@code expires_at} 을 과거로
 * 돌려 만든다 — 기다리지 않는다.
 *
 * <p>저장소 수준 계약(조건 하나하나, P-4 의 세 경우, 동시 시작 CAS)은
 * {@code JdbcSeatPaymentRepositoryTest}·{@code SeatPaymentConcurrencyTest} 가 이미 고정했다.
 * 여기서는 <b>서비스 경로</b>에서 그 계약이 유지되는지와 quota 불변·트랜잭션 참여만 본다.
 */
@DisplayName("StartPaymentService — 실제 MySQL 통합")
class StartPaymentServiceIntegrationTest extends MySqlSpringTestSupport {

    private static final long SCHEDULE = 7_600L;
    private static final UserId USER_A = new UserId(61L);
    private static final UserId USER_B = new UserId(62L);

    @Autowired
    private HoldSeatsService holdSeats;

    @Autowired
    private ReleaseHoldService releaseHold;

    @Autowired
    private StartPaymentService service;

    private HoldId holdViaService(UserId user, long... seatIds) {
        List<SeatId> seats = new ArrayList<>();
        for (long id : seatIds) {
            seats.add(new SeatId(id));
        }
        return holdSeats.hold(new HoldSeatsCommand(user, seats)).holdId();
    }

    private SeatPaymentOutcome start(long seatId, HoldId hold) {
        return service.start(new StartPaymentCommand(new SeatId(seatId), hold));
    }

    private Timestamp dbNow() {
        return jdbc().queryForObject("SELECT NOW(3)", Timestamp.class);
    }

    private void setExpiresAt(long seatId, String sqlExpression) {
        jdbc().update("UPDATE seat_inventory SET expires_at = " + sqlExpression + " WHERE id = ?", seatId);
    }

    /** 좌석 행의 관련 필드 전부. 롤백·NOT_STARTED 가 아무것도 바꾸지 않았는지 동등 비교한다. */
    private record SeatSnapshot(
            String status, Long reservationId, String holdId, Long heldBy,
            Timestamp heldAt, Timestamp expiresAt, long version) {
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

    @Nested
    @DisplayName("§1 ★ 정상 시작")
    class 정상_시작 {

        @Test
        void PAYING_으로_바뀌고_소유권은_보존되고_version_이_1_늘고_quota_는_그대로다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            HoldId hold = holdViaService(USER_A, a);
            SeatSnapshot before = snapshotOf(a);
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).isEqualTo(1);

            assertThat(start(a, hold)).isEqualTo(SeatPaymentOutcome.STARTED);

            SeatSnapshot after = snapshotOf(a);
            assertThat(after.status()).isEqualTo("PAYING");
            assertThat(after.holdId()).as("소유권 필드 보존").isEqualTo(before.holdId());
            assertThat(after.heldBy()).isEqualTo(before.heldBy());
            assertThat(after.heldAt()).isEqualTo(before.heldAt());
            assertThat(after.reservationId()).isNull();
            assertThat(after.version()).isEqualTo(before.version() + 1);
            assertThat(after.expiresAt()).as("★ 만료 시각은 줄지 않는다").isAfterOrEqualTo(before.expiresAt());
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value()))
                    .as("★ HELD·PAYING 모두 활성 점유 — 결제 시작은 카운터를 바꾸지 않는다").isEqualTo(1);
            assertThat(quotaRowCount()).isEqualTo(1);
        }

        /** P-4 — 남은 시간이 결제 창보다 짧으면 DB 시각 + 5분까지 늘어난다. */
        @Test
        void 잔여_시간이_짧으면_DB_시각_기준_결제_창까지_늘어난다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            HoldId hold = holdViaService(USER_A, a);
            setExpiresAt(a, "DATE_ADD(NOW(3), INTERVAL 30 SECOND)");
            Timestamp before = dbNow();

            assertThat(start(a, hold)).isEqualTo(SeatPaymentOutcome.STARTED);

            Timestamp floor = new Timestamp(before.getTime() + 5 * 60 * 1000);
            assertThat(expiresAtOf(a)).isAfterOrEqualTo(floor);
        }

        /** P-4 — 남은 시간이 결제 창보다 길면 그대로다. 앞당기지 않는다. */
        @Test
        void 잔여_시간이_길면_만료_시각을_앞당기지_않는다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            HoldId hold = holdViaService(USER_A, a);
            setExpiresAt(a, "DATE_ADD(NOW(3), INTERVAL 30 MINUTE)");
            Timestamp before = expiresAtOf(a);

            assertThat(start(a, hold)).isEqualTo(SeatPaymentOutcome.STARTED);

            assertThat(expiresAtOf(a)).isEqualTo(before);
        }

        /** 같은 홀드의 다른 좌석과 다른 사용자의 좌석은 그대로다. */
        @Test
        void 지정한_좌석만_바뀐다() {
            long a1 = insertAvailableSeat(SCHEDULE, "1A");
            long a2 = insertAvailableSeat(SCHEDULE, "1B");
            long b1 = insertAvailableSeat(SCHEDULE, "2A");
            HoldId holdA = holdViaService(USER_A, a1, a2);
            holdViaService(USER_B, b1);
            SeatSnapshot a2Before = snapshotOf(a2);
            SeatSnapshot b1Before = snapshotOf(b1);

            assertThat(start(a1, holdA)).isEqualTo(SeatPaymentOutcome.STARTED);

            assertThat(snapshotOf(a2)).isEqualTo(a2Before);
            assertThat(snapshotOf(b1)).isEqualTo(b1Before);
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).isEqualTo(2);
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_B.value())).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("§2 ★ NOT_STARTED — 정상 경합 결과, 아무것도 바뀌지 않는다")
    class NOT_STARTED {

        @Test
        void 다른_holdId_는_NOT_STARTED_이고_좌석과_quota_가_그대로다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            holdViaService(USER_A, a);
            SeatSnapshot before = snapshotOf(a);

            assertThat(start(a, HoldId.newId())).isEqualTo(SeatPaymentOutcome.NOT_STARTED);

            assertThat(snapshotOf(a)).isEqualTo(before);
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).isEqualTo(1);
        }

        /** ★ 만료 판단은 DB 시각이다. 만료된 홀드는 결제로 넘어가지 않는다. */
        @Test
        void 만료된_홀드는_NOT_STARTED_이고_좌석과_quota_가_그대로다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            HoldId hold = holdViaService(USER_A, a);
            setExpiresAt(a, "DATE_SUB(NOW(3), INTERVAL 1 SECOND)");
            SeatSnapshot before = snapshotOf(a);

            assertThat(start(a, hold)).isEqualTo(SeatPaymentOutcome.NOT_STARTED);

            assertThat(snapshotOf(a)).as("HELD·만료 시각·version 그대로").isEqualTo(before);
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).isEqualTo(1);
        }

        /** ★ 재요청 — 이미 PAYING 이면 NOT_STARTED 이고 만료를 다시 늘리지 않는다. 멱등 재생이 아니다. */
        @Test
        void 재요청은_NOT_STARTED_이고_만료를_재연장하지_않는다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            HoldId hold = holdViaService(USER_A, a);
            assertThat(start(a, hold)).isEqualTo(SeatPaymentOutcome.STARTED);
            SeatSnapshot afterFirst = snapshotOf(a);

            assertThat(start(a, hold)).isEqualTo(SeatPaymentOutcome.NOT_STARTED);

            assertThat(snapshotOf(a)).as("★ expires_at·version 모두 첫 시작 직후 그대로").isEqualTo(afterFirst);
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).isEqualTo(1);
        }

        @Test
        void 없는_좌석은_NOT_STARTED_이고_quota_행을_만들지_않는다() {
            assertThat(start(999_999L, HoldId.newId())).isEqualTo(SeatPaymentOutcome.NOT_STARTED);
            assertThat(quotaRowCount()).isZero();
        }

        @Test
        void 해제된_홀드는_NOT_STARTED_다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            HoldId hold = holdViaService(USER_A, a);
            assertThat(releaseHold.release(new ReleaseHoldCommand(hold, USER_A)).releasedSeats()).isEqualTo(1);

            assertThat(start(a, hold)).isEqualTo(SeatPaymentOutcome.NOT_STARTED);

            assertThat(statusOf(a)).isEqualTo("AVAILABLE");
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).isZero();
        }
    }

    @Nested
    @DisplayName("§3 ★ 트랜잭션 참여 — 외부 롤백")
    class 외부_롤백 {

        @Test
        void 바깥_트랜잭션이_롤백하면_상태_만료_시각_version_이_모두_돌아온다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            HoldId hold = holdViaService(USER_A, a);
            setExpiresAt(a, "DATE_ADD(NOW(3), INTERVAL 30 SECOND)");   // 연장이 실제로 일어나게
            SeatSnapshot before = snapshotOf(a);
            TransactionTemplate outer =
                    new TransactionTemplate(new DataSourceTransactionManager(dataSource()));

            assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
                assertThat(start(a, hold)).as("트랜잭션 안에서는 STARTED — 아직 최종 커밋이 아니다")
                        .isEqualTo(SeatPaymentOutcome.STARTED);
                SeatSnapshot inside = snapshotOf(a);
                assertThat(inside.status()).isEqualTo("PAYING");
                assertThat(inside.expiresAt()).isAfter(before.expiresAt());
                assertThat(inside.version()).isEqualTo(before.version() + 1);
                throw new IllegalStateException("호출자가 롤백시킨다");
            })).isInstanceOf(IllegalStateException.class);

            assertThat(snapshotOf(a)).as("★ 모든 필드가 시작 직전 값으로").isEqualTo(before);
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("§4 ★ 동시 실행 — 보조 검증")
    class 동시_실행 {

        /**
         * 같은 좌석·같은 홀드로 동시에 시작해도 한 요청만 STARTED 다.
         * 래치는 출발만 맞추며 교차 순서를 강제하지 않는다. 결과의 합만 본다.
         */
        @Test
        void 같은_좌석_동시_시작은_하나만_성공하고_quota_가_그대로다() throws Exception {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            HoldId hold = holdViaService(USER_A, a);
            long versionBefore = versionOf(a);
            int threads = 8;

            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<SeatPaymentOutcome> outcomes = new ArrayList<>();
            try {
                List<Future<SeatPaymentOutcome>> futures = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    futures.add(pool.submit(() -> {
                        ready.countDown();
                        go.await(30, TimeUnit.SECONDS);
                        return start(a, hold);
                    }));
                }
                assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
                go.countDown();
                for (Future<SeatPaymentOutcome> f : futures) {
                    outcomes.add(f.get(30, TimeUnit.SECONDS));
                }
            } finally {
                pool.shutdownNow();
            }

            assertThat(outcomes).filteredOn(o -> o == SeatPaymentOutcome.STARTED).hasSize(1);
            assertThat(outcomes).filteredOn(o -> o == SeatPaymentOutcome.NOT_STARTED).hasSize(threads - 1);
            assertThat(statusOf(a)).isEqualTo("PAYING");
            assertThat(versionOf(a)).as("갱신은 한 번").isEqualTo(versionBefore + 1);
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).isEqualTo(1);
        }

        /**
         * ★ 결제 시작과 자발적 해제가 경쟁해도 quota 가 어긋나지 않는다.
         *
         * <p>해제는 {@code HELD}·{@code PAYING} 을 모두 푼다. 어느 쪽이 먼저 좌석 행을 잡든
         * 좌석은 AVAILABLE, 카운터는 0 이다. 시작은 quota 행을 잠그지 않으므로 해제의
         * {@code quota → seat} 순서와 교차해도 서로 다른 순서로 두 자원을 쥐는 일이 없다.
         * 어느 분기가 나왔는지는 통계적 관측이고 불변식만 확인한다.
         */
        @Test
        void 결제_시작과_해제가_경쟁해도_좌석은_풀리고_quota_는_0_이다() throws Exception {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            HoldId hold = holdViaService(USER_A, a);

            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            SeatPaymentOutcome started;
            int released;
            try {
                Future<SeatPaymentOutcome> starting = pool.submit(() -> {
                    go.await(30, TimeUnit.SECONDS);
                    return start(a, hold);
                });
                Future<Integer> releasing = pool.submit(() -> {
                    go.await(30, TimeUnit.SECONDS);
                    return releaseHold.release(new ReleaseHoldCommand(hold, USER_A)).releasedSeats();
                });
                go.countDown();
                started = starting.get(30, TimeUnit.SECONDS);
                released = releasing.get(30, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }

            assertThat(started).isIn(SeatPaymentOutcome.STARTED, SeatPaymentOutcome.NOT_STARTED);
            assertThat(released).as("HELD 든 PAYING 이든 해제된다").isEqualTo(1);
            assertThat(statusOf(a)).isEqualTo("AVAILABLE");
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).isZero();
        }
    }

    @Nested
    @DisplayName("§5 ★ 만료 배치와의 교차 — 결정적")
    class 만료_배치_교차 {

        @Autowired
        private SeatExpiryPort expiryPort;

        @Autowired
        private UserHoldQuotaPort quotaPort;

        @Autowired
        private DataSourceTransactionManager transactionManager;

        /**
         * 만료 배치가 후보를 읽은 <b>직후</b> 결제 시작이 들어와도 만료된 홀드를 살리지 못한다.
         * 배치는 그 좌석을 회수하고 quota 를 1 줄인다.
         *
         * <p>후보 조회 직후 훅을 실행하는 데코레이터로 순서를 고정한다 ({@code ExpireHoldsServiceIntegrationTest}
         * 와 같은 방식). 배치가 아직 결제 창 안의 좌석을 회수하지 않는다는 반대 방향은 회수 UPDATE 의
         * {@code expires_at <= NOW(3)} 재검사가 맡으며 저장소 테스트({@code SeatExpiryStaleCandidateTest} 등)가 고정했다.
         */
        @Test
        void 후보_조회_뒤_들어온_결제_시작은_NOT_STARTED_이고_배치가_회수한다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            HoldId hold = holdViaService(USER_A, a);
            setExpiresAt(a, "DATE_SUB(NOW(3), INTERVAL 10 SECOND)");
            List<SeatPaymentOutcome> duringSweep = new ArrayList<>();
            SeatExpiryPort decorated = new SeatExpiryPort() {
                @Override
                public List<ExpiredSeat> findExpiredSeats(int pageSize, Optional<ExpiryCursor> after) {
                    List<ExpiredSeat> found = expiryPort.findExpiredSeats(pageSize, after);
                    if (duringSweep.isEmpty()) {   // 첫 페이지 직후 한 번만
                        duringSweep.add(start(a, hold));
                    }
                    return found;
                }

                @Override
                public int expire(List<ExpiryCandidate> candidates) {
                    return expiryPort.expire(candidates);
                }
            };
            ExpireHoldsService sweeper =
                    new ExpireHoldsService(decorated, quotaPort, transactionManager, attempt -> { });

            ExpirySweepResult result = sweeper.sweep(100);

            assertThat(duringSweep).containsExactly(SeatPaymentOutcome.NOT_STARTED);
            assertThat(result.reclaimed()).isEqualTo(1);
            assertThat(statusOf(a)).isEqualTo("AVAILABLE");
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).isZero();
        }
    }

    @Nested
    @DisplayName("§6 배선")
    class 배선 {

        @Autowired
        private ApplicationContext context;

        /** ★ 결제 시작·확정 포트가 각각 정확히 하나의 빈으로 풀리고, 그 빈은 같은 어댑터 인스턴스다. */
        @Test
        void 두_포트가_모호하지_않게_하나의_어댑터로_풀린다() {
            assertThat(context.getBeanNamesForType(SeatPaymentPort.class)).hasSize(1);
            assertThat(context.getBeanNamesForType(SeatConfirmationPort.class)).hasSize(1);
            assertThat(context.getBean(SeatPaymentPort.class))
                    .isSameAs(context.getBean(SeatConfirmationPort.class));
        }

        @Test
        void 서비스는_트랜잭션_프록시로_주입된다() {
            assertThat(AopUtils.isAopProxy(service)).isTrue();
        }
    }
}
