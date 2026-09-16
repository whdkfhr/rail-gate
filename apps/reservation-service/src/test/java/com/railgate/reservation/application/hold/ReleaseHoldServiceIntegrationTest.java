package com.railgate.reservation.application.hold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.saleevent.SaleEventScopeException;
import com.railgate.reservation.seat.SeatId;
import com.railgate.reservation.support.MySqlSpringTestSupport;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ★ <b>Spring 이 배선한</b> 해제 서비스를 실제 MySQL 에서 호출한다.
 *
 * <p>선점은 같은 컨텍스트의 {@link HoldSeatsService} 로 만든다 — 그래야 quota 와 좌석이
 * 운영 경로 그대로 준비된다. <b>확정(SOLD)과 만료는 아직 quota 와 연동되지 않았으므로</b>
 * 그 상태가 필요한 테스트는 직접 SQL 로 만들며, 각 테스트가 그 사실과 관측 범위를 명시한다.
 */
@DisplayName("ReleaseHoldService — 실제 MySQL 통합")
class ReleaseHoldServiceIntegrationTest extends MySqlSpringTestSupport {

    private static final long SCHEDULE = 7_300L;
    private static final long OTHER_SCHEDULE = 7_400L;
    private static final long OTHER_EVENT = 9_200_000L;
    private static final UserId USER_A = new UserId(31L);
    private static final UserId USER_B = new UserId(32L);

    @Autowired
    private HoldSeatsService holdSeats;

    @Autowired
    private ReleaseHoldService service;

    private static List<SeatId> ids(long... values) {
        List<SeatId> list = new ArrayList<>();
        for (long v : values) {
            list.add(new SeatId(v));
        }
        return list;
    }

    /** 운영 선점 경로로 홀드를 만든다. quota 도 함께 오른다. */
    private HoldId holdViaService(UserId user, List<SeatId> seats) {
        return holdSeats.hold(new HoldSeatsCommand(user, seats)).holdId();
    }

    private ReleaseHoldResult release(HoldId hold, UserId user) {
        return service.release(new ReleaseHoldCommand(hold, user));
    }

    /**
     * 픽스처 — <b>확정 경로가 quota 와 연동되지 않았으므로</b> 직접 SQL 로 SOLD 를 만든다.
     * 운영 확정 저장소를 거치지 않는다. 관측 범위는 "SOLD 좌석은 해제 대상이 아니다" 뿐이다.
     */
    private void markSoldDirectly(long seatId) {
        jdbc().update("UPDATE seat_inventory SET status = 'SOLD' WHERE id = ?", seatId);
    }

    /**
     * 픽스처 — 선점 경로를 거치지 않고 HELD 좌석을 만든다. <b>quota 는 오르지 않는다.</b>
     * 활성화 전 backfill 을 거치지 않은 기존 홀드를 흉내 낸다.
     */
    private HoldId heldDirectlyWithoutQuota(UserId user, long... seatIds) {
        HoldId hold = HoldId.newId();
        for (long id : seatIds) {
            jdbc().update("""
                    UPDATE seat_inventory
                       SET status = 'HELD', hold_id = ?, held_by = ?,
                           held_at = NOW(3), expires_at = DATE_ADD(NOW(3), INTERVAL 5 MINUTE)
                     WHERE id = ?
                    """, hold.asString(), user.value(), id);
        }
        return hold;
    }

    @Nested
    @DisplayName("§1 ★ 정상 해제")
    class 정상_해제 {

        @Test
        void 좌석이_풀리고_quota_가_같은_수만큼_준다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long b = insertAvailableSeat(SCHEDULE, "1B");
            long c = insertAvailableSeat(SCHEDULE, "1C");
            HoldId hold = holdViaService(USER_A, ids(a, b, c));
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).isEqualTo(3);

            ReleaseHoldResult result = release(hold, USER_A);

            assertThat(result.releasedSeats()).isEqualTo(3);
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value()))
                    .as("★ 실제 해제 수만큼 준다").isZero();
            assertThat(statusOf(a)).isEqualTo("AVAILABLE");
            assertThat(statusOf(c)).isEqualTo("AVAILABLE");
            assertThat(heldByOf(a)).isNull();
        }

        /** ★ 선점 → 해제 → 재선점. 반환된 quota 를 다시 쓸 수 있다. */
        @Test
        void 해제한_quota_를_다시_선점에_쓸_수_있다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long b = insertAvailableSeat(SCHEDULE, "1B");
            long c = insertAvailableSeat(SCHEDULE, "1C");
            long d = insertAvailableSeat(SCHEDULE, "1D");
            long e = insertAvailableSeat(SCHEDULE, "1E");
            HoldId first = holdViaService(USER_A, ids(a, b, c, d));

            assertThatThrownBy(() -> holdViaService(USER_A, ids(e)))
                    .as("상한 4석 소진").isInstanceOf(QuotaExceededException.class);

            release(first, USER_A);

            assertThatCode(() -> holdViaService(USER_A, ids(a, b, c, e)))
                    .as("★ 해제로 돌아온 몫으로 다시 4석").doesNotThrowAnyException();
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).isEqualTo(4);
            assertThat(statusOf(d)).as("두 번째 홀드에 없는 좌석은 그대로 비어 있다").isEqualTo("AVAILABLE");
        }

        /** 같은 회차의 여러 운행편에 걸친 홀드도 하나의 quota 에서 준다. */
        @Test
        void 여러_운행편_홀드도_하나의_quota_에서_준다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long b = insertAvailableSeat(OTHER_SCHEDULE, "1A");
            HoldId hold = holdViaService(USER_A, ids(a, b));

            assertThat(release(hold, USER_A).releasedSeats()).isEqualTo(2);
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).isZero();
        }
    }

    @Nested
    @DisplayName("§2 ★ 성공 no-op — 0 건이며 타인 좌석과 quota 를 건드리지 않는다")
    class 성공_no_op {

        @Test
        void 재해제는_0_건이고_quota_를_더_줄이지_않는다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            HoldId hold = holdViaService(USER_A, ids(a));
            release(hold, USER_A);

            ReleaseHoldResult again = release(hold, USER_A);

            assertThat(again.releasedSeats()).isZero();
            assertThat(again.released()).isFalse();
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value()))
                    .as("★ 0 에서 더 줄지 않는다 (음수 방어 이전에 호출 자체가 없다)").isZero();
        }

        @Test
        void 없는_홀드는_0_건이다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            holdViaService(USER_A, ids(a));

            assertThat(release(HoldId.newId(), USER_A).releasedSeats()).isZero();
            assertThat(statusOf(a)).isEqualTo("HELD");
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).isEqualTo(1);
        }

        /** ★ 타 사용자가 내 홀드 ID 로 해제를 요청해도 0 건이고, 내 좌석·quota 는 그대로다. */
        @Test
        void 타_사용자의_홀드는_0_건이고_아무것도_바뀌지_않는다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long b = insertAvailableSeat(SCHEDULE, "1B");
            HoldId mine = holdViaService(USER_A, ids(a, b));

            ReleaseHoldResult result = release(mine, USER_B);

            assertThat(result.releasedSeats()).isZero();
            assertThat(statusOf(a)).isEqualTo("HELD");
            assertThat(heldByOf(a)).isEqualTo(USER_A.value());
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).isEqualTo(2);
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_B.value()))
                    .as("★ 요청자의 quota 행을 만들지도 않는다").isEqualTo(-1);
        }

        /**
         * SOLD 대상은 0 건이다.
         *
         * <p><b>픽스처 주의.</b> 확정 경로는 quota 와 미연동이라 직접 SQL 로 SOLD 를 만들었다.
         * 그래서 quota 가 1 로 남는 것은 <b>이 테스트가 만든 drift</b>이며 해제 경로의 문제가 아니다.
         * 관측 범위는 "SOLD 는 해제 대상이 아니고 quota 를 건드리지 않는다" 까지다.
         */
        @Test
        void SOLD_대상은_0_건이고_quota_를_건드리지_않는다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            HoldId hold = holdViaService(USER_A, ids(a));
            markSoldDirectly(a);

            assertThat(release(hold, USER_A).releasedSeats()).isZero();
            assertThat(statusOf(a)).isEqualTo("SOLD");
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value()))
                    .as("확정 미연동으로 남은 1 — 해제 경로가 건드리지 않는다").isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("§3 ★★ 감소량은 실제 해제 수다")
    class 실제_해제_수 {

        /**
         * ★★ <b>stale 후보</b> — 스냅숏에는 3석이 있었지만 그 사이 1석이 SOLD 가 됐다.
         *
         * <p>결정적으로 만드는 방법: 바깥 트랜잭션에서 먼저 <b>잠금 없는 읽기</b>로
         * {@code REPEATABLE READ} 스냅숏을 고정한 뒤, <b>다른 커넥션(autocommit)</b>으로
         * 좌석 하나를 SOLD 로 커밋하고, 그제야 서비스를 부른다. 서비스는 바깥 트랜잭션에
         * 참여하므로 회차 해석과 후보 조회는 옛 스냅숏(3석 HELD)을 보고, 해제 UPDATE 는
         * 잠금 읽기라 최신 상태(1석 SOLD)를 본다. 그래서 <b>후보 3, 실제 해제 2</b> 가 된다.
         *
         * <p><b>픽스처 주의.</b> SOLD 는 직접 SQL 이다 (확정 미연동). 그래서 남는 quota 1 은
         * 이 테스트가 만든 drift 이며, 여기서 보는 것은 "감소가 후보 수 3 이 아니라
         * 실제 해제 수 2 만큼이다" 뿐이다.
         */
        @Test
        void 스냅숏_이후_SOLD_된_후보는_빠지고_실제_해제_수만큼만_준다() throws Exception {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long b = insertAvailableSeat(SCHEDULE, "1B");
            long c = insertAvailableSeat(SCHEDULE, "1C");
            HoldId hold = holdViaService(USER_A, ids(a, b, c));

            TransactionTemplate outer =
                    new TransactionTemplate(new DataSourceTransactionManager(dataSource()));

            ReleaseHoldResult result = outer.execute(status -> {
                // (1) 이 트랜잭션의 일관된 읽기 스냅숏을 지금 고정한다 — 3석 모두 HELD.
                jdbc().queryForObject(
                        "SELECT COUNT(*) FROM seat_inventory WHERE hold_id = ?",
                        Long.class, hold.asString());

                // (2) 다른 커넥션이 b 를 SOLD 로 커밋한다. 좌석 잠금을 잡은 적이 없으므로 막히지 않는다.
                commitOnSeparateConnection(
                        "UPDATE seat_inventory SET status = 'SOLD' WHERE id = ?", b);

                // (3) 서비스는 이 트랜잭션에 참여한다 — 옛 스냅숏으로 후보를 읽는다.
                return service.release(new ReleaseHoldCommand(hold, USER_A));
            });

            assertThat(result.releasedSeats())
                    .as("★★ 후보는 3 이었지만 실제로 풀린 것은 2 다").isEqualTo(2);
            assertThat(statusOf(a)).isEqualTo("AVAILABLE");
            assertThat(statusOf(c)).isEqualTo("AVAILABLE");
            assertThat(statusOf(b)).as("SOLD 는 그대로다").isEqualTo("SOLD");
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value()))
                    .as("★★ 3 − 2 = 1. 후보 수 3 으로 줄였다면 0 이 됐을 것이다").isEqualTo(1);
        }

        /** 다른 풀 커넥션으로 즉시 커밋한다. 현재 스레드의 트랜잭션과 무관하다. */
        private void commitOnSeparateConnection(String sql, Object... params) {
            try (Connection other = dataSource().getConnection();
                 PreparedStatement ps = other.prepareStatement(sql)) {
                assertThat(other.getAutoCommit()).as("별도 커넥션은 autocommit").isTrue();
                for (int i = 0; i < params.length; i++) {
                    ps.setObject(i + 1, params[i]);
                }
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("별도 커넥션 커밋 실패", e);
            }
        }
    }

    @Nested
    @DisplayName("§4 ★★ 정합성 오류 — 좌석 해제까지 롤백")
    class 정합성_오류 {

        /**
         * ★★ quota 행이 없는데 활성 좌석이 있다 (활성화 전 backfill 을 거치지 않은 홀드).
         *
         * <p>행을 만들어 넘기지 않는다. 예외로 전파하고 <b>좌석 해제까지 롤백</b>한다.
         */
        @Test
        void quota_행이_없으면_거절하고_좌석도_HELD_로_남는다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long b = insertAvailableSeat(SCHEDULE, "1B");
            HoldId hold = heldDirectlyWithoutQuota(USER_A, a, b);
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).isEqualTo(-1);

            assertThatThrownBy(() -> release(hold, USER_A))
                    .isInstanceOf(QuotaInconsistencyException.class)
                    .hasMessageContaining("행이 없");

            assertThat(statusOf(a)).as("★★ 좌석 해제가 롤백됐다").isEqualTo("HELD");
            assertThat(statusOf(b)).isEqualTo("HELD");
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value()))
                    .as("★ 행을 만들지 않았다").isEqualTo(-1);
        }

        /**
         * ★★ quota 가 실제 해제 수보다 작다 — 감소가 거절되고 좌석 해제까지 롤백된다.
         *
         * <p>선점으로 2석(quota 2)을 만든 뒤 같은 홀드에 직접 SQL 로 2석을 더 붙인다.
         * 활성 4석 / quota 2 인 drift 상태다.
         */
        @Test
        void quota_가_부족하면_거절하고_좌석도_HELD_로_남는다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long b = insertAvailableSeat(SCHEDULE, "1B");
            long c = insertAvailableSeat(SCHEDULE, "1C");
            long d = insertAvailableSeat(SCHEDULE, "1D");
            HoldId hold = holdViaService(USER_A, ids(a, b));
            jdbc().update("""
                    UPDATE seat_inventory
                       SET status = 'HELD', hold_id = ?, held_by = ?,
                           held_at = NOW(3), expires_at = DATE_ADD(NOW(3), INTERVAL 5 MINUTE)
                     WHERE id IN (?, ?)
                    """, hold.asString(), USER_A.value(), c, d);
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).isEqualTo(2);

            assertThatThrownBy(() -> release(hold, USER_A))
                    .isInstanceOf(QuotaInconsistencyException.class)
                    .hasMessageContaining("거절");

            assertThat(heldSeatCount()).as("★★ 4석 전부 HELD 로 남는다").isEqualTo(4);
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value()))
                    .as("★ 값을 보정하지 않았다 — drift 의 증거가 남는다").isEqualTo(2);
        }

        /** ★ 서비스가 성공해도 바깥 트랜잭션이 롤백하면 좌석과 quota 모두 되돌아간다. */
        @Test
        void 외부_롤백이_해제와_감소를_모두_되돌린다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long b = insertAvailableSeat(SCHEDULE, "1B");
            HoldId hold = holdViaService(USER_A, ids(a, b));

            TransactionTemplate outer =
                    new TransactionTemplate(new DataSourceTransactionManager(dataSource()));

            assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
                ReleaseHoldResult result = release(hold, USER_A);
                assertThat(result.releasedSeats())
                        .as("트랜잭션 안에서는 2 — 아직 최종 커밋이 아니다").isEqualTo(2);
                throw new IllegalStateException("호출자가 롤백시킨다");
            })).isInstanceOf(IllegalStateException.class);

            assertThat(statusOf(a)).as("★ 해제가 되돌아갔다").isEqualTo("HELD");
            assertThat(statusOf(b)).isEqualTo("HELD");
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value()))
                    .as("★ 감소도 되돌아갔다").isEqualTo(2);
        }

        /** 비정상 데이터 — 한 홀드가 두 회차에 걸쳐 있으면 쓰기 전에 거절한다. */
        @Test
        void 회차가_섞인_홀드는_쓰기_전에_거절한다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long b = insertAvailableSeat(8_300L, "1A", OTHER_EVENT);
            HoldId hold = heldDirectlyWithoutQuota(USER_A, a, b);

            assertThatThrownBy(() -> release(hold, USER_A))
                    .isInstanceOf(SaleEventScopeException.class);

            assertThat(statusOf(a)).isEqualTo("HELD");
            assertThat(statusOf(b)).isEqualTo("HELD");
        }
    }

    @Nested
    @DisplayName("§5 ★★ 동시 실행")
    class 동시_실행 {

        /**
         * ★★ 같은 홀드를 동시에 두 번 해제해도 <b>한 번만</b> 준다.
         *
         * <p>둘 다 회차를 해석하고 같은 quota 행을 잠그려 한다. 잠금이 직렬화하므로
         * 늦은 쪽의 해제 UPDATE 는 이미 AVAILABLE 인 좌석을 보고 0 건이 되고, 0 건이면
         * 감소를 호출하지 않는다. 결과의 <b>합</b>이 결정적이다 — 어느 쪽이 이기는지는 아니다.
         */
        @Test
        void 같은_홀드_동시_해제는_한_번만_준다() throws Exception {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long b = insertAvailableSeat(SCHEDULE, "1B");
            long c = insertAvailableSeat(SCHEDULE, "1C");
            HoldId hold = holdViaService(USER_A, ids(a, b, c));

            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<Integer> released = new ArrayList<>();
            try {
                List<Future<Integer>> futures = List.of(
                        pool.submit(() -> releaseAfter(start, hold)),
                        pool.submit(() -> releaseAfter(start, hold)));
                start.countDown();
                for (Future<Integer> f : futures) {
                    released.add(f.get(30, TimeUnit.SECONDS));
                }
            } finally {
                pool.shutdownNow();
            }

            assertThat(released).as("★★ 3 + 0 — 한 번만 풀린다").containsExactlyInAnyOrder(3, 0);
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value()))
                    .as("★★ 한 번만 줄어 0. 두 번 줄었다면 음수 방어에 걸려 예외가 났을 것이다")
                    .isZero();
            assertThat(heldSeatCount()).isZero();
        }

        /**
         * ★ 같은 사용자의 <b>해제와 선점</b>이 경쟁해도 상한을 지킨다.
         *
         * <p>4석 홀드를 풀면서 동시에 1석을 새로 잡는다. 어느 쪽이 먼저 quota 행을 잠그느냐에
         * 따라 선점이 성공(해제 뒤)하거나 상한 거절(해제 전)된다. <b>어느 경우든</b>
         * 카운터는 실제 활성 좌석 수와 같고 4 를 넘지 않는다. 결과의 분기는 통계적 관측이고,
         * 불변식은 결정적이다.
         */
        @Test
        void 같은_사용자의_해제와_선점_경쟁에서도_상한을_지킨다() throws Exception {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long b = insertAvailableSeat(SCHEDULE, "1B");
            long c = insertAvailableSeat(SCHEDULE, "1C");
            long d = insertAvailableSeat(SCHEDULE, "1D");
            long e = insertAvailableSeat(SCHEDULE, "1E");
            HoldId full = holdViaService(USER_A, ids(a, b, c, d));

            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            int releasedSeats;
            boolean heldNew;
            try {
                Future<Integer> releasing = pool.submit(() -> releaseAfter(start, full));
                Future<Boolean> holding = pool.submit(() -> {
                    start.await(30, TimeUnit.SECONDS);
                    try {
                        holdViaService(USER_A, ids(e));
                        return true;
                    } catch (QuotaExceededException limit) {
                        return false;
                    }
                });
                start.countDown();
                releasedSeats = releasing.get(30, TimeUnit.SECONDS);
                heldNew = holding.get(30, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }

            assertThat(releasedSeats).isEqualTo(4);
            int expectedQuota = heldNew ? 1 : 0;
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value()))
                    .as("★ 어느 순서든 카운터 = 실제 활성 좌석 수 (선점 %s)", heldNew ? "성공" : "거절")
                    .isEqualTo(expectedQuota);
            assertThat(heldSeatCount()).isEqualTo(expectedQuota);
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).isLessThanOrEqualTo(4);
        }

        private int releaseAfter(CountDownLatch start, HoldId hold) throws Exception {
            start.await(30, TimeUnit.SECONDS);
            return service.release(new ReleaseHoldCommand(hold, USER_A)).releasedSeats();
        }
    }

    /**
     * ★ RED A — <b>정합성 오류를 삼키면 무슨 일이 일어나는가</b>를 실제로 관측한다.
     *
     * <p>§4 는 올바른 구현을 검증한다. 거기에 삼키는 구현을 넣으면 {@code assertThatThrownBy}
     * 가 <b>먼저</b> 실패하므로 커밋 후 좌석·quota 상태 어서션에 도달하지 못한다.
     * 즉 §4 의 RED 는 "예외가 나오지 않았다" 까지만 본다. 커밋 후 상태는 여기서 따로 본다.
     *
     * <p>서비스와 <b>같은 순서</b>를 실행하되 예외 대신 성공을 돌려주는 대조군을 트랜잭션
     * 안에서 돌리고, <b>커밋된 뒤</b> 좌석과 카운터를 각각 확인한다. 운영 서비스에는 이 분기가 없다.
     */
    @Nested
    @DisplayName("§7 ★ RED A 대조군 — 정합성 오류를 삼키면")
    class 정합성_오류를_삼킨_대조군 {

        @Autowired
        private com.railgate.reservation.saleevent.SaleEventScopePort scope;

        @Autowired
        private com.railgate.reservation.quota.UserHoldQuotaPort quota;

        @Autowired
        private com.railgate.reservation.hold.SeatReleasePort seats;

        /** 대조군: {@code ReleaseHoldService.release} 의 1~4 단계. 차이는 예외 대신 반환뿐이다. */
        private int 삼키는_해제(HoldId hold, UserId user) {
            TransactionTemplate boundary =
                    new TransactionTemplate(new DataSourceTransactionManager(dataSource()));
            return boundary.execute(status -> {
                var saleEventId = scope.resolveActiveHold(hold, user).orElseThrow();
                var counter = quota.lockRow(saleEventId, user);
                int released = seats.releaseAll(hold, user);
                if (released == 0) {
                    return 0;
                }
                if (counter.isEmpty()) {
                    return released;                       // ★ 삼킨다 — 운영 코드는 예외
                }
                quota.release(saleEventId, user, released); // ★ 결과를 보지 않는다 — 운영 코드는 검사
                return released;
            });
        }

        /**
         * ★★ 행 부재를 삼키면 — 좌석은 풀리고 <b>카운터는 여전히 없다.</b>
         *
         * <p>"상한이 남는다" 는 상태가 아니다. 카운터 자체가 없으므로 그 사용자의 다음 선점은
         * {@code ensureRow} 가 0 부터 새로 만든다. 남는 것은 <b>backfill 이 필요했다는 증거의
         * 소멸</b>이다 — 활성 좌석 없는 사용자가 되어 drift 검사(V-7b)에도 더 이상 걸리지 않는다.
         */
        @Test
        void 행_부재를_삼키면_좌석은_풀리고_카운터는_여전히_없다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long b = insertAvailableSeat(SCHEDULE, "1B");
            HoldId hold = heldDirectlyWithoutQuota(USER_A, a, b);

            int released = 삼키는_해제(hold, USER_A);

            assertThat(released).as("대조군은 성공처럼 2 를 돌려준다").isEqualTo(2);
            assertThat(statusOf(a)).as("★★ 좌석 해제가 커밋됐다").isEqualTo("AVAILABLE");
            assertThat(statusOf(b)).isEqualTo("AVAILABLE");
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value()))
                    .as("★★ 카운터는 만들어지지도 남지도 않았다 — 행 부재 그대로다")
                    .isEqualTo(-1);
        }

        /**
         * ★★ 부족을 삼키면 — 좌석은 전부 풀리고 <b>카운터는 2 로 남는다.</b>
         *
         * <p>활성 4석 / quota 2 에서 {@code release(4)} 는 {@code 2-4<0} 으로 거절되어 카운터가
         * 그대로다. 그런데 좌석은 4석 모두 커밋된다. 사용자는 <b>좌석 0석인데 상한 2 를
         * 소진한 채</b> 남는다 — 다음 선점은 2석까지만 가능하다.
         */
        @Test
        void 부족을_삼키면_좌석은_전부_풀리고_카운터는_2_로_남는다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long b = insertAvailableSeat(SCHEDULE, "1B");
            long c = insertAvailableSeat(SCHEDULE, "1C");
            long d = insertAvailableSeat(SCHEDULE, "1D");
            HoldId hold = holdViaService(USER_A, ids(a, b));
            jdbc().update("""
                    UPDATE seat_inventory
                       SET status = 'HELD', hold_id = ?, held_by = ?,
                           held_at = NOW(3), expires_at = DATE_ADD(NOW(3), INTERVAL 5 MINUTE)
                     WHERE id IN (?, ?)
                    """, hold.asString(), USER_A.value(), c, d);

            int released = 삼키는_해제(hold, USER_A);

            assertThat(released).isEqualTo(4);
            assertThat(heldSeatCount()).as("★★ 4석 전부 AVAILABLE 로 커밋됐다").isZero();
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value()))
                    .as("★★ 거절된 감소는 반영되지 않아 카운터가 2 로 남는다 — 좌석 없이 상한 2 소진")
                    .isEqualTo(2);
        }

        /** ★ 같은 두 조건에서 올바른 구현은 좌석을 HELD 로 되돌린다 (§4 가 이미 고정한다). */
        @Test
        void 올바른_구현은_같은_조건에서_좌석을_되돌린다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            HoldId hold = heldDirectlyWithoutQuota(USER_A, a);

            assertThatThrownBy(() -> release(hold, USER_A))
                    .isInstanceOf(QuotaInconsistencyException.class);

            assertThat(statusOf(a)).as("★ 대조군은 AVAILABLE 이었다").isEqualTo("HELD");
        }
    }

    @Nested
    @DisplayName("§8 ★ 프록시 없이는 경계가 없다")
    class 프록시_검증 {

        @Autowired
        private com.railgate.reservation.saleevent.SaleEventScopePort scope;

        @Autowired
        private com.railgate.reservation.quota.UserHoldQuotaPort quota;

        @Autowired
        private com.railgate.reservation.hold.SeatReleasePort seats;

        /** 손으로 조립한 서비스는 quota 잠금 단계에서 참여 검사로 거절된다. */
        @Test
        void 손으로_조립한_서비스는_트랜잭션_없음으로_거부된다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            HoldId hold = holdViaService(USER_A, ids(a));
            ReleaseHoldService unproxied = new ReleaseHoldService(scope, quota, seats);

            assertThatThrownBy(() -> unproxied.release(new ReleaseHoldCommand(hold, USER_A)))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(statusOf(a)).as("좌석은 건드리기 전에 거부된다").isEqualTo("HELD");
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).isEqualTo(1);
        }
    }
}
