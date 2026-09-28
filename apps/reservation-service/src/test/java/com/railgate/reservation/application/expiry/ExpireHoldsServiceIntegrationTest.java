package com.railgate.reservation.application.expiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.ReservationId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.application.hold.ConfirmSeatCommand;
import com.railgate.reservation.application.hold.ConfirmSeatService;
import com.railgate.reservation.application.hold.HoldSeatsCommand;
import com.railgate.reservation.application.hold.HoldSeatsService;
import com.railgate.reservation.application.hold.ReleaseHoldCommand;
import com.railgate.reservation.application.hold.ReleaseHoldService;
import com.railgate.reservation.expiry.ExpiredSeat;
import com.railgate.reservation.expiry.ExpiryCandidate;
import com.railgate.reservation.expiry.ExpiryCursor;
import com.railgate.reservation.expiry.SeatExpiryPort;
import com.railgate.reservation.hold.SeatConfirmationOutcome;
import com.railgate.reservation.infra.seat.JdbcSeatPaymentRepository;
import com.railgate.reservation.quota.UserHoldQuotaPort;
import com.railgate.reservation.seat.SeatId;
import com.railgate.reservation.support.MySqlSpringTestSupport;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ★ 만료 배치를 실제 MySQL 에서 검증한다.
 *
 * <p>정상 흐름은 <b>Spring 이 배선한 빈</b>으로, 결정적 동기화가 필요한 시나리오는
 * <b>같은 서비스 클래스</b>를 실제 트랜잭션 관리자·실제 저장소 위에 손으로 조립하되
 * {@link SeatExpiryPort} 만 <b>테스트 전용 데코레이터</b>로 감싼다. 이 서비스는
 * {@code @Transactional} 프록시가 아니라 {@code TransactionTemplate} 으로 경계를 여므로
 * 손으로 조립해도 트랜잭션 동작이 같다 — 그래서 그룹 경계의 실재는 §3·§5 가 롤백으로 확인한다.
 *
 * <p>선점은 {@link HoldSeatsService}, 결제 시작은 기존 저장소, 만료는 <b>직접 SQL 로
 * {@code expires_at} 을 과거로</b> 돌린다 (만료 시각 조작은 운영 경로가 없다).
 */
@DisplayName("ExpireHoldsService — 실제 MySQL 통합")
class ExpireHoldsServiceIntegrationTest extends MySqlSpringTestSupport {

    private static final long SCHEDULE = 7_600L;
    private static final long OTHER_EVENT = 9_400_000L;
    private static final UserId USER_A = new UserId(51L);
    private static final UserId USER_B = new UserId(52L);
    private static final String EXPIRED = "DATE_SUB(NOW(3), INTERVAL 10 SECOND)";

    @Autowired
    private HoldSeatsService holdSeats;

    @Autowired
    private ReleaseHoldService releaseHold;

    @Autowired
    private ConfirmSeatService confirmSeat;

    @Autowired
    private ExpireHoldsService service;

    @Autowired
    private SeatExpiryPort expiryPort;

    @Autowired
    private UserHoldQuotaPort quotaPort;

    @Autowired
    private DataSourceTransactionManager transactionManager;

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

    /** 롤백 검증용 — 좌석의 관련 7개 필드 전부. {@code null} 도 값으로 비교된다. */
    private Map<String, Object> seatSnapshot(long seatId) {
        return jdbc().queryForMap("""
                SELECT status, reservation_id, hold_id, held_by, held_at, expires_at, version
                  FROM seat_inventory WHERE id = ?
                """, seatId);
    }

    private void expireDirectly(long... seatIds) {
        for (long id : seatIds) {
            jdbc().update("UPDATE seat_inventory SET expires_at = " + EXPIRED + " WHERE id = ?", id);
        }
    }

    /** 선점 경로를 거치지 않은 만료 HELD (quota 없음). backfill 안 된 홀드를 흉내 낸다. */
    private HoldId expiredHeldWithoutQuota(UserId user, long... seatIds) {
        HoldId hold = HoldId.newId();
        for (long id : seatIds) {
            jdbc().update("""
                    UPDATE seat_inventory SET status = 'HELD', hold_id = ?, held_by = ?,
                           held_at = NOW(3), expires_at = """ + EXPIRED + " WHERE id = ?",
                    hold.asString(), user.value(), id);
        }
        return hold;
    }

    /** 실제 서비스 클래스 + 실제 관리자 + 데코레이터 포트. 백오프는 기록만 하고 대기하지 않는다. */
    private ExpireHoldsService handWired(SeatExpiryPort decorated, List<Integer> backoffs) {
        return new ExpireHoldsService(decorated, quotaPort, transactionManager, attempt -> {
            backoffs.add(attempt);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .as("★ 백오프는 트랜잭션 밖에서").isFalse();
        });
    }

    /** 후보 조회 직후 훅을 실행하는 데코레이터. 결정적 stale·경쟁 시나리오를 만든다. */
    private static SeatExpiryPort afterCandidates(SeatExpiryPort delegate, Consumer<List<ExpiredSeat>> hook) {
        return new SeatExpiryPort() {
            @Override
            public List<ExpiredSeat> findExpiredSeats(int pageSize, Optional<ExpiryCursor> after) {
                List<ExpiredSeat> found = delegate.findExpiredSeats(pageSize, after);
                hook.accept(found);
                return found;
            }

            @Override
            public int expire(List<ExpiryCandidate> candidates) {
                return delegate.expire(candidates);
            }
        };
    }

    @Nested
    @DisplayName("§1 ★ 정상 회수 (Spring 빈)")
    class 정상_회수 {

        @Test
        void 만료_좌석이_회수되고_quota_가_실제_회수_수만큼_준다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long b = insertAvailableSeat(SCHEDULE, "1B");
            long c = insertAvailableSeat(SCHEDULE, "1C");
            holdViaService(USER_A, ids(a, b, c));
            expireDirectly(a, b);   // c 는 아직 유효

            ExpirySweepResult result = service.sweep(100);

            assertThat(result.candidatesFound()).isEqualTo(2);
            assertThat(result.reclaimed()).isEqualTo(2);
            assertThat(result.succeeded()).containsEntry(new QuotaGroupKey(new com.railgate.reservation.saleevent.SaleEventId(FIXTURE_SALE_EVENT_ID), USER_A), 2);
            assertThat(result.hasFailures()).isFalse();
            assertThat(statusOf(a)).isEqualTo("AVAILABLE");
            assertThat(statusOf(c)).as("미만료는 그대로").isEqualTo("HELD");
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).as("3 − 2").isEqualTo(1);
        }

        /** ★ 여러 사용자·회차 — 각 그룹의 quota 만 각자 준다. 배치 크기와 순서도 지킨다. */
        @Test
        void 다중_사용자_회차를_각자_정산한다() {
            long a1 = insertAvailableSeat(SCHEDULE, "1A");
            long a2 = insertAvailableSeat(SCHEDULE, "1B");
            long ay = insertAvailableSeat(8_600L, "1A", OTHER_EVENT);
            long b1 = insertAvailableSeat(SCHEDULE, "2A");
            holdViaService(USER_A, ids(a1, a2));
            holdViaService(USER_A, ids(ay));
            holdViaService(USER_B, ids(b1));
            expireDirectly(a1, a2, ay, b1);

            ExpirySweepResult result = service.sweep(100);

            assertThat(result.reclaimed()).isEqualTo(4);
            assertThat(result.succeeded()).hasSize(3);
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).isZero();
            assertThat(quotaOf(OTHER_EVENT, USER_A.value())).isZero();
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_B.value())).isZero();
            assertThat(heldSeatCount()).isZero();
        }

        @Test
        void 후보가_없으면_아무것도_하지_않는다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            holdViaService(USER_A, ids(a));

            ExpirySweepResult result = service.sweep(100);

            assertThat(result.candidatesFound()).isZero();
            assertThat(statusOf(a)).isEqualTo("HELD");
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).isEqualTo(1);
        }

        /** ★ 외부 트랜잭션 안에서는 쓰기 전에 거절한다. */
        @Test
        void 외부_트랜잭션_안에서_호출하면_읽기_전에_거절한다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            holdViaService(USER_A, ids(a));
            expireDirectly(a);
            TransactionTemplate outer = new TransactionTemplate(transactionManager);

            assertThatThrownBy(() -> outer.executeWithoutResult(status -> service.sweep(100)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("활성 트랜잭션");

            assertThat(statusOf(a)).as("아무것도 바뀌지 않았다").isEqualTo("HELD");
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("§2 ★ 부분 stale — 결정적")
    class 부분_stale {

        /** 후보 조회 직후 한 좌석이 확정된다. 회수 UPDATE 는 그 좌석을 건너뛰고 quota 도 그만큼만 준다. */
        @Test
        void 후보_조회_뒤_확정된_좌석은_회수되지_않고_실제_수만큼만_준다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long b = insertAvailableSeat(SCHEDULE, "1B");
            long c = insertAvailableSeat(SCHEDULE, "1C");
            HoldId hold = holdViaService(USER_A, ids(a, b, c));
            JdbcSeatPaymentRepository payments = new JdbcSeatPaymentRepository(dataSource(), Duration.ofMinutes(5));
            payments.startPayment(new SeatId(b), hold);
            expireDirectly(a, b, c);
            List<Integer> backoffs = new ArrayList<>();
            ExpireHoldsService sweeper = handWired(afterCandidates(expiryPort, found -> {
                assertThat(found).hasSize(3);
                // 만료 지난 PAYING 도 확정된다 (2H-C 계약). quota 3 → 2.
                assertThat(confirmSeat.confirm(new ConfirmSeatCommand(new SeatId(b), hold, new ReservationId(1L))))
                        .isEqualTo(SeatConfirmationOutcome.CONFIRMED);
            }), backoffs);

            ExpirySweepResult result = sweeper.sweep(100);

            assertThat(result.candidatesFound()).isEqualTo(3);
            assertThat(result.reclaimed()).as("★ 후보 3, 실제 2").isEqualTo(2);
            assertThat(result.staleCandidates()).isEqualTo(1);
            assertThat(result.failed()).isEmpty();
            assertThat(statusOf(b)).as("I-11 — 확정된 좌석을 되돌리지 않는다").isEqualTo("SOLD");
            assertThat(statusOf(a)).isEqualTo("AVAILABLE");
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value()))
                    .as("★ 확정 1 + 회수 2 = 3 감소 → 0").isZero();
            assertThat(backoffs).isEmpty();
        }

        /** 후보 조회 뒤 홀드가 자발적으로 해제되면 회수 0 건 — 감소를 호출하지 않는다. */
        @Test
        void 후보_조회_뒤_해제된_홀드는_회수_0건이고_감소가_없다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long b = insertAvailableSeat(SCHEDULE, "1B");
            HoldId hold = holdViaService(USER_A, ids(a, b));
            expireDirectly(a, b);
            ExpireHoldsService sweeper = handWired(afterCandidates(expiryPort, found ->
                    assertThat(releaseHold.release(new ReleaseHoldCommand(hold, USER_A)).releasedSeats()).isEqualTo(2)),
                    new ArrayList<>());

            ExpirySweepResult result = sweeper.sweep(100);

            assertThat(result.candidatesFound()).isEqualTo(2);
            assertThat(result.reclaimed()).isZero();
            assertThat(result.staleCandidates()).isEqualTo(2);
            assertThat(result.succeeded()).hasSize(1);
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value()))
                    .as("★ 해제가 0 으로 만들었고 스위퍼는 더 줄이지 않았다 (줄였다면 REJECTED → 실패 그룹)").isZero();
        }
    }

    @Nested
    @DisplayName("§3 ★★ 실패 격리 — 그룹 롤백, 정상 그룹 커밋 유지")
    class 실패_격리 {

        /** ★ B 의 카운터가 없어도 A 는 회수된다. B 의 좌석은 UPDATE 전에 실패해 HELD 그대로다. */
        @Test
        void quota_행_없는_그룹만_실패하고_정상_그룹은_커밋된다() {
            long a1 = insertAvailableSeat(SCHEDULE, "1A");
            long a2 = insertAvailableSeat(SCHEDULE, "1B");
            long b1 = insertAvailableSeat(SCHEDULE, "2A");
            holdViaService(USER_A, ids(a1, a2));
            expireDirectly(a1, a2);
            expiredHeldWithoutQuota(USER_B, b1);
            Long versionBefore = versionOf(b1);

            ExpirySweepResult result = service.sweep(100);

            assertThat(result.reclaimed()).isEqualTo(2);
            assertThat(statusOf(a1)).isEqualTo("AVAILABLE");
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).isZero();
            assertThat(result.failed()).singleElement().satisfies(f -> {
                assertThat(f.key().userId()).isEqualTo(USER_B);
                assertThat(f.kind()).isEqualTo(GroupFailureKind.QUOTA_ROW_MISSING);
                assertThat(f.attempts()).isEqualTo(1);
            });
            assertThat(statusOf(b1)).as("★ UPDATE 전에 실패 — HELD 그대로").isEqualTo("HELD");
            assertThat(versionOf(b1)).as("좌석 행을 건드리지 않았다").isEqualTo(versionBefore);
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_B.value())).as("행을 만들지 않았다").isEqualTo(-1);
        }

        /** ★ 감소 거절 — 회수 UPDATE 까지 롤백된다. 좌석의 모든 필드가 원래 값이다. */
        @Test
        void quota_부족_그룹은_회수까지_롤백된다() {
            long a1 = insertAvailableSeat(SCHEDULE, "1A");
            long a2 = insertAvailableSeat(SCHEDULE, "1B");
            HoldId hold = holdViaService(USER_A, ids(a1, a2));
            expireDirectly(a1, a2);
            jdbc().update("UPDATE user_hold_quota SET held_seats = 1 WHERE user_id = ?", USER_A.value());
            // ★ 대상 두 좌석 모두, 7개 필드 전부를 호출 전후로 비교한다.
            Map<String, Object> before1 = seatSnapshot(a1);
            Map<String, Object> before2 = seatSnapshot(a2);

            ExpirySweepResult result = service.sweep(100);

            assertThat(result.failed()).singleElement()
                    .satisfies(f -> assertThat(f.kind()).isEqualTo(GroupFailureKind.QUOTA_DECREASE_REJECTED));
            assertThat(result.reclaimed()).as("롤백된 회수는 세지 않는다").isZero();
            assertThat(seatSnapshot(a1))
                    .as("★★ 좌석 1 — status·reservation_id·hold_id·held_by·held_at·expires_at·version 전부 복구")
                    .isEqualTo(before1);
            assertThat(seatSnapshot(a2))
                    .as("★★ 좌석 2 — 같은 그룹의 나머지 좌석도 전부 복구")
                    .isEqualTo(before2);
            assertThat(holdIdOf(a1)).isEqualTo(hold.asString());
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).as("값을 보정하지 않았다").isEqualTo(1);
        }

        /** ★ 손상 후보는 회수하지 않고 보고한다. 같은 배치의 정상 후보는 처리된다. */
        @Test
        void 손상_후보는_격리하고_정상_후보는_처리한다() {
            long broken = insertAvailableSeat(SCHEDULE, "1A");
            long ok = insertAvailableSeat(SCHEDULE, "1B");
            holdViaService(USER_A, ids(broken, ok));
            expireDirectly(broken, ok);
            jdbc().update("UPDATE seat_inventory SET held_by = NULL WHERE id = ?", broken);

            ExpirySweepResult result = service.sweep(100);

            assertThat(result.corrupt()).singleElement().satisfies(c -> {
                assertThat(c.seatId().value()).isEqualTo(broken);
                assertThat(c.reason()).contains("held_by");
            });
            assertThat(statusOf(broken)).as("회수하지 않았다").isEqualTo("HELD");
            assertThat(statusOf(ok)).isEqualTo("AVAILABLE");
            assertThat(result.reclaimed()).isEqualTo(1);
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value()))
                    .as("정상 1석만 줄어 1 — 손상 좌석의 몫은 남는다 (drift 증거)").isEqualTo(1);
        }
    }

    /**
     * ★ RED C 대조군 — <b>감소 거절을 삼키면</b> 커밋 후 무엇이 남는가.
     *
     * <p>§3 는 올바른 구현을 검증한다. 거기에 삼키는 구현을 넣으면 {@code failed()} 어서션이
     * <b>먼저</b> 실패하므로 그 뒤의 좌석 스냅숏 비교에 도달하지 못한다 — 그 RED 가 관측하는
     * 것은 "실패 그룹이 보고되지 않았다" 까지다. <b>커밋 후 상태는 다른 주장</b>이라 여기서
     * 따로 본다. 운영 서비스에는 이 분기가 없다.
     */
    @Nested
    @DisplayName("§6 ★ RED C 대조군 — 감소 거절을 삼키면")
    class 감소_거절을_삼킨_대조군 {

        @Autowired
        private com.railgate.reservation.quota.UserHoldQuotaPort quota;

        /** {@code settleGroup} 과 같은 순서. 차이는 감소 결과를 보지 않는 것뿐이다. */
        private int 삼키는_정산(QuotaGroupKey key, List<ExpiryCandidate> group) {
            TransactionTemplate boundary = new TransactionTemplate(transactionManager);
            return boundary.execute(status -> {
                quota.lockRow(key.saleEventId(), key.userId()).orElseThrow();
                int affected = expiryPort.expire(group);
                if (affected == 0) {
                    return 0;
                }
                quota.release(key.saleEventId(), key.userId(), affected);   // ★ 결과를 보지 않는다
                return affected;
            });
        }

        @Test
        void 감소_거절을_삼키면_좌석은_AVAILABLE_로_커밋되고_카운터는_그대로_남는다() {
            long a1 = insertAvailableSeat(SCHEDULE, "1A");
            long a2 = insertAvailableSeat(SCHEDULE, "1B");
            holdViaService(USER_A, ids(a1, a2));
            expireDirectly(a1, a2);
            jdbc().update("UPDATE user_hold_quota SET held_seats = 1 WHERE user_id = ?", USER_A.value());
            List<ExpiredSeat> found = expiryPort.findExpiredSeats(10, java.util.Optional.empty());
            QuotaGroupKey key = new QuotaGroupKey(
                    new com.railgate.reservation.saleevent.SaleEventId(FIXTURE_SALE_EVENT_ID), USER_A);

            int affected = 삼키는_정산(key, found.stream().map(ExpiredSeat::toCandidate).toList());

            assertThat(affected).as("대조군은 성공처럼 2 를 돌려준다").isEqualTo(2);
            assertThat(statusOf(a1)).as("★★ 좌석이 AVAILABLE 로 커밋됐다").isEqualTo("AVAILABLE");
            assertThat(statusOf(a2)).isEqualTo("AVAILABLE");
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value()))
                    .as("★★ 거절된 감소는 반영되지 않아 1 이 그대로 — 좌석 0석인데 상한 1 소진")
                    .isEqualTo(1);
        }

        /** ★ 같은 조건에서 올바른 구현은 좌석을 HELD 로 되돌린다 (§3 이 7필드로 고정한다). */
        @Test
        void 올바른_구현은_같은_조건에서_좌석을_되돌린다() {
            long a1 = insertAvailableSeat(SCHEDULE, "1A");
            holdViaService(USER_A, ids(a1));
            expireDirectly(a1);
            jdbc().update("UPDATE user_hold_quota SET held_seats = 0 WHERE user_id = ?", USER_A.value());

            ExpirySweepResult result = service.sweep(100);

            assertThat(result.failed()).singleElement()
                    .satisfies(f -> assertThat(f.kind()).isEqualTo(GroupFailureKind.QUOTA_DECREASE_REJECTED));
            assertThat(statusOf(a1)).as("★ 대조군은 AVAILABLE 이었다").isEqualTo("HELD");
        }
    }

    @Nested
    @DisplayName("§4 ★★ 두 스위퍼 — 결정적 동기화")
    class 두_스위퍼 {

        /**
         * ★★ 두 스위퍼가 <b>같은 후보를 읽은 뒤</b> 정산한다. 회수 합은 후보 수, 감소는 한 번.
         *
         * <p>{@link CyclicBarrier} 가 두 후보 조회를 모두 마친 뒤에야 정산을 허용한다 — 출발이 아니라
         * <b>"둘 다 같은 스냅숏을 가졌다"</b> 를 강제하므로 결정적이다. 누가 먼저 quota 를 잠그는지는
         * 결정적이지 않지만, 늦은 쪽은 조건부 UPDATE 가 0 건이 되어 감소하지 않는다.
         */
        @Test
        void 같은_후보를_읽은_두_스위퍼는_한_번만_회수하고_한_번만_준다() throws Exception {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long b = insertAvailableSeat(SCHEDULE, "1B");
            holdViaService(USER_A, ids(a, b));
            expireDirectly(a, b);

            CyclicBarrier bothRead = new CyclicBarrier(2);
            CountDownLatch start = new CountDownLatch(1);
            SeatExpiryPort synced = afterCandidates(expiryPort, found -> {
                assertThat(found).as("둘 다 같은 2석을 본다").hasSize(2);
                try {
                    bothRead.await(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<ExpirySweepResult> results = new ArrayList<>();
            try {
                List<Future<ExpirySweepResult>> futures = List.of(
                        pool.submit(() -> { start.await(30, TimeUnit.SECONDS); return handWired(synced, new ArrayList<>()).sweep(100); }),
                        pool.submit(() -> { start.await(30, TimeUnit.SECONDS); return handWired(synced, new ArrayList<>()).sweep(100); }));
                start.countDown();
                for (Future<ExpirySweepResult> f : futures) {
                    results.add(f.get(30, TimeUnit.SECONDS));
                }
            } finally {
                pool.shutdownNow();
            }

            assertThat(results).extracting(ExpirySweepResult::reclaimed).containsExactlyInAnyOrder(2, 0);
            assertThat(results).allSatisfy(r -> assertThat(r.failed()).as("REJECTED 가 없었다 — 이중 감소 없음").isEmpty());
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).as("★★ 한 번만 줄어 0").isZero();
            assertThat(heldSeatCount()).isZero();
        }
    }

    @Nested
    @DisplayName("§5 ★ 재시도 — 실제 트랜잭션")
    class 재시도 {

        /** 첫 시도의 회수 UPDATE 가 데드락(1213 을 cause 로)으로 실패하면 새 트랜잭션으로 재시도해 성공한다. */
        @Test
        void 데드락_한_번_뒤_새_트랜잭션에서_성공하고_첫_시도는_롤백됐다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            holdViaService(USER_A, ids(a));
            expireDirectly(a);
            List<Integer> backoffs = new ArrayList<>();
            List<Boolean> txActiveAtExpire = new ArrayList<>();
            int[] expireCalls = {0};
            SeatExpiryPort failingOnce = new SeatExpiryPort() {
                @Override
                public List<ExpiredSeat> findExpiredSeats(int pageSize, Optional<ExpiryCursor> after) {
                    return expiryPort.findExpiredSeats(pageSize, after);
                }

                @Override
                public int expire(List<ExpiryCandidate> candidates) {
                    txActiveAtExpire.add(TransactionSynchronizationManager.isActualTransactionActive());
                    if (expireCalls[0]++ == 0) {
                        int affected = expiryPort.expire(candidates);   // 실제로 바꾼 뒤 실패 — 롤백돼야 한다
                        assertThat(affected).isEqualTo(1);
                        throw new PessimisticLockingFailureException("savepoint 가 덮은 데드락",
                                new SQLException("Deadlock found when trying to get lock", "40001", 1213));
                    }
                    return expiryPort.expire(candidates);
                }
            };

            ExpirySweepResult result = handWired(failingOnce, backoffs).sweep(100);

            assertThat(backoffs).containsExactly(1);
            assertThat(txActiveAtExpire).as("두 시도 모두 트랜잭션 안").containsExactly(true, true);
            assertThat(result.failed()).isEmpty();
            assertThat(result.reclaimed()).as("★ 첫 시도의 회수는 롤백됐고 두 번째가 실제 회수").isEqualTo(1);
            assertThat(result.totalAttempts()).isEqualTo(2);
            assertThat(statusOf(a)).isEqualTo("AVAILABLE");
            assertThat(versionOf(a)).as("롤백된 UPDATE 는 version 을 남기지 않는다: 선점 +1, 회수 +1").isEqualTo(2L);
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).isZero();
        }

        @Test
        void 세_번_모두_실패하면_그룹은_실패로_남고_좌석과_quota_는_불변이다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            holdViaService(USER_A, ids(a));
            expireDirectly(a);
            List<Integer> backoffs = new ArrayList<>();
            SeatExpiryPort alwaysFailing = new SeatExpiryPort() {
                @Override
                public List<ExpiredSeat> findExpiredSeats(int pageSize, Optional<ExpiryCursor> after) {
                    return expiryPort.findExpiredSeats(pageSize, after);
                }

                @Override
                public int expire(List<ExpiryCandidate> candidates) {
                    expiryPort.expire(candidates);
                    throw new PessimisticLockingFailureException("lock wait",
                            new SQLException("Lock wait timeout exceeded", "HY000", 1205));
                }
            };

            ExpirySweepResult result = handWired(alwaysFailing, backoffs).sweep(100);

            assertThat(backoffs).containsExactly(1, 2);
            assertThat(result.failed()).singleElement().satisfies(f -> {
                assertThat(f.kind()).isEqualTo(GroupFailureKind.LOCK_WAIT_TIMEOUT);
                assertThat(f.attempts()).isEqualTo(3);
            });
            assertThat(statusOf(a)).as("세 번 모두 롤백").isEqualTo("HELD");
            assertThat(versionOf(a)).isEqualTo(1L);
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).isEqualTo(1);
        }
    }
}
