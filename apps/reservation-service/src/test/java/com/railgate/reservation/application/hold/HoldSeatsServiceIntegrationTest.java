package com.railgate.reservation.application.hold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.hold.SeatHoldPort;
import com.railgate.reservation.quota.UserHoldQuotaPort;
import com.railgate.reservation.saleevent.SaleEventScopeException;
import com.railgate.reservation.saleevent.SaleEventScopePort;
import com.railgate.reservation.seat.SeatId;
import com.railgate.reservation.seat.SeatUnavailableException;
import com.railgate.reservation.support.MySqlSpringTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
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
 * ★ <b>Spring 이 배선한</b> 선점 서비스를 실제 MySQL 에서 호출한다.
 *
 * <p>단위 테스트가 고정하는 것은 호출 순서이고, 여기서 고정하는 것은
 * <b>트랜잭션 경계가 실제로 존재하는가</b> 다. 손으로 조립한 객체에는
 * {@code @Transactional} 프록시가 없으므로 주입받은 빈만 쓴다.
 */
@DisplayName("HoldSeatsService — 실제 MySQL 통합")
class HoldSeatsServiceIntegrationTest extends MySqlSpringTestSupport {

    private static final long SCHEDULE = 7_100L;
    private static final long OTHER_SCHEDULE = 7_200L;
    private static final long OTHER_EVENT = 9_100_000L;
    private static final UserId USER_A = new UserId(11L);
    private static final UserId USER_B = new UserId(22L);

    @Autowired
    private HoldSeatsService service;

    @Autowired
    private SeatHoldPort seatHold;

    @Autowired
    private SaleEventScopePort saleEventScope;

    @Autowired
    private UserHoldQuotaPort quota;

    private static List<SeatId> ids(long... values) {
        List<SeatId> list = new ArrayList<>();
        for (long value : values) {
            list.add(new SeatId(value));
        }
        return list;
    }

    @Nested
    @DisplayName("§1 ★ 정상 선점")
    class 정상_선점 {

        @Test
        void quota_와_좌석이_함께_반영된다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long b = insertAvailableSeat(SCHEDULE, "1B");

            HoldSeatsResult result = service.hold(new HoldSeatsCommand(USER_A, ids(a, b)));

            assertThat(result.seatCount()).isEqualTo(2);
            assertThat(result.saleEventId().value()).isEqualTo(FIXTURE_SALE_EVENT_ID);
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).isEqualTo(2);
            assertThat(statusOf(a)).isEqualTo("HELD");
            assertThat(statusOf(b)).isEqualTo("HELD");
            assertThat(heldByOf(a)).isEqualTo(USER_A.value());
        }

        /** ★ 회차는 서버가 좌석에서 해석한다. 클라이언트가 보낼 수 없다. */
        @Test
        void 회차를_좌석에서_해석한다() {
            long seat = insertAvailableSeat(8_100L, "1A", OTHER_EVENT);

            HoldSeatsResult result = service.hold(new HoldSeatsCommand(USER_A, ids(seat)));

            assertThat(result.saleEventId().value()).isEqualTo(OTHER_EVENT);
            assertThat(quotaOf(OTHER_EVENT, USER_A.value())).isEqualTo(1);
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value()))
                    .as("다른 회차의 카운터는 만들지 않는다").isEqualTo(-1);
        }

        /**
         * ★ 같은 회차의 <b>여러 운행편</b>은 하나의 quota 로 합산된다.
         *
         * <p>범위가 운행편이면 사용자가 운행편을 나눠 상한을 우회한다.
         */
        @Test
        void 같은_회차의_여러_운행편이_하나의_quota_로_합산된다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long b = insertAvailableSeat(OTHER_SCHEDULE, "1A");

            service.hold(new HoldSeatsCommand(USER_A, ids(a)));
            service.hold(new HoldSeatsCommand(USER_A, ids(b)));

            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value()))
                    .as("★ 운행편이 달라도 회차가 같으면 합산된다").isEqualTo(2);
            assertThat(quotaRowCount()).as("행은 하나다").isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("§2 ★ 요청 거부")
    class 요청_거부 {

        @Test
        void 없는_좌석이_섞이면_거부하고_아무것도_남기지_않는다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");

            assertThatThrownBy(() -> service.hold(new HoldSeatsCommand(USER_A, ids(a, 999_999L))))
                    .isInstanceOf(SaleEventScopeException.class);

            assertThat(quotaRowCount()).isZero();
            assertThat(statusOf(a)).isEqualTo("AVAILABLE");
        }

        /** ★ 서로 다른 회차 혼합. 합산할 수 없으므로 거부한다. */
        @Test
        void 서로_다른_회차의_좌석_혼합을_거부한다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long b = insertAvailableSeat(8_100L, "1A", OTHER_EVENT);

            assertThatThrownBy(() -> service.hold(new HoldSeatsCommand(USER_A, ids(a, b))))
                    .isInstanceOf(SaleEventScopeException.class);

            assertThat(quotaRowCount()).isZero();
            assertThat(heldSeatCount()).isZero();
        }

        @Test
        void 빈_목록과_중복과_상한_초과는_명령_생성에서_거부된다() {
            assertThatThrownBy(() -> new HoldSeatsCommand(USER_A, List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new HoldSeatsCommand(USER_A, ids(1, 1)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new HoldSeatsCommand(USER_A, ids(1, 2, 3, 4, 5)))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(quotaRowCount()).as("DB 를 건드리지 않는다").isZero();
        }
    }

    @Nested
    @DisplayName("§3 ★ 상한 초과")
    class 상한_초과 {

        @Test
        void 누적_5석째_요청은_QuotaExceededException_이다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long b = insertAvailableSeat(SCHEDULE, "1B");
            long c = insertAvailableSeat(SCHEDULE, "1C");
            long d = insertAvailableSeat(SCHEDULE, "1D");
            long e = insertAvailableSeat(SCHEDULE, "1E");

            service.hold(new HoldSeatsCommand(USER_A, ids(a, b, c, d)));

            assertThatThrownBy(() -> service.hold(new HoldSeatsCommand(USER_A, ids(e))))
                    .isInstanceOf(QuotaExceededException.class);

            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value()))
                    .as("★ 상한을 넘지 않는다").isEqualTo(4);
            assertThat(statusOf(e)).as("좌석은 건드리지 않았다").isEqualTo("AVAILABLE");
            assertThat(heldSeatCount()).isEqualTo(4);
        }

        /** ★ 다른 사용자와 다른 회차는 서로 독립이다. */
        @Test
        void 사용자와_회차_사이는_독립이다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long b = insertAvailableSeat(SCHEDULE, "1B");
            long c = insertAvailableSeat(SCHEDULE, "1C");
            long d = insertAvailableSeat(SCHEDULE, "1D");
            long other = insertAvailableSeat(SCHEDULE, "1E");
            long otherEventSeat = insertAvailableSeat(8_100L, "1A", OTHER_EVENT);

            service.hold(new HoldSeatsCommand(USER_A, ids(a, b, c, d)));

            assertThatCode(() -> service.hold(new HoldSeatsCommand(USER_B, ids(other))))
                    .as("다른 사용자는 영향받지 않는다").doesNotThrowAnyException();
            assertThatCode(() -> service.hold(new HoldSeatsCommand(USER_A, ids(otherEventSeat))))
                    .as("★ 같은 사용자라도 다른 회차는 독립이다").doesNotThrowAnyException();

            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).isEqualTo(4);
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_B.value())).isEqualTo(1);
            assertThat(quotaOf(OTHER_EVENT, USER_A.value())).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("§4 ★★ 좌석 경합 시 quota 도 복구")
    class 좌석_경합_복구 {

        /**
         * ★★ 이것이 Task 2H-A 의 핵심이다.
         *
         * <p>3석 중 1석이 이미 남의 것이면 요청 전체가 실패하고
         * <b>quota 증가도 함께 사라져야 한다.</b> 남으면 그 사용자는
         * 잡지도 못한 좌석 때문에 상한을 소진한다.
         */
        @Test
        void 부분_경합이면_quota_와_좌석이_모두_복구된다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long b = insertAvailableSeat(SCHEDULE, "1B");
            long c = insertAvailableSeat(SCHEDULE, "1C");

            service.hold(new HoldSeatsCommand(USER_B, ids(b)));   // b 를 선점당한 상태로 만든다

            assertThatThrownBy(() -> service.hold(new HoldSeatsCommand(USER_A, ids(a, b, c))))
                    .isInstanceOf(SeatUnavailableException.class)
                    .isNotInstanceOf(QuotaExceededException.class);

            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value()))
                    .as("★★ quota 증가가 남으면 안 된다 — 행 자체가 없거나 0 이어야 한다")
                    .isIn(-1, 0);
            assertThat(statusOf(a)).as("★ I-9 — 전부 또는 전무").isEqualTo("AVAILABLE");
            assertThat(statusOf(c)).isEqualTo("AVAILABLE");
            assertThat(heldByOf(b)).as("남의 홀드는 그대로다").isEqualTo(USER_B.value());
            assertThat(heldSeatCount()).isEqualTo(1);
        }

        /** ★ 실패 뒤에도 상한이 소진되지 않았음을 재선점으로 확인한다. */
        @Test
        void 경합_실패_뒤에_같은_사용자가_다시_4석을_잡을_수_있다() {
            long taken = insertAvailableSeat(SCHEDULE, "9Z");
            service.hold(new HoldSeatsCommand(USER_B, ids(taken)));

            long a = insertAvailableSeat(SCHEDULE, "1A");
            long b = insertAvailableSeat(SCHEDULE, "1B");
            long c = insertAvailableSeat(SCHEDULE, "1C");
            long d = insertAvailableSeat(SCHEDULE, "1D");

            assertThatThrownBy(() -> service.hold(new HoldSeatsCommand(USER_A, ids(a, taken))))
                    .isInstanceOf(SeatUnavailableException.class);

            assertThatCode(() -> service.hold(new HoldSeatsCommand(USER_A, ids(a, b, c, d))))
                    .as("★ 상한이 소진되지 않았다").doesNotThrowAnyException();
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("§5 ★★ 동시 요청")
    class 동시_요청 {

        /**
         * ★★ 같은 사용자의 <b>동시</b> 3석 요청 두 개 중 하나만 성공한다.
         *
         * <p>집계로 검사했다면 둘 다 "내 것 0석" 을 보고 통과해 6석이 됐을 것이다
         * (CLAUDE.md 규칙 8). 카운터 행의 조건부 UPDATE 가 그것을 막는다.
         *
         * <p>{@code CountDownLatch} 로 동시 출발시킨다. {@code Thread.sleep} 으로
         * 타이밍을 맞추지 않는다 (규칙 25).
         */
        @Test
        void 같은_사용자의_동시_3석_요청_두_개_중_하나만_성공한다() throws Exception {
            List<SeatId> first = ids(
                    insertAvailableSeat(SCHEDULE, "1A"),
                    insertAvailableSeat(SCHEDULE, "1B"),
                    insertAvailableSeat(SCHEDULE, "1C"));
            List<SeatId> second = ids(
                    insertAvailableSeat(SCHEDULE, "2A"),
                    insertAvailableSeat(SCHEDULE, "2B"),
                    insertAvailableSeat(SCHEDULE, "2C"));

            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                List<Future<Boolean>> futures = List.of(
                        pool.submit(() -> attempt(start, first)),
                        pool.submit(() -> attempt(start, second)));

                start.countDown();

                int succeeded = 0;
                for (Future<Boolean> future : futures) {
                    if (future.get(30, TimeUnit.SECONDS)) {
                        succeeded++;
                    }
                }

                assertThat(succeeded).as("★★ 정확히 하나만 성공한다").isEqualTo(1);
            } finally {
                pool.shutdownNow();
            }

            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value()))
                    .as("★★ 최종 3석. 6석이면 I-12 가 깨진 것이다").isEqualTo(3);
            assertThat(heldSeatCount()).isEqualTo(3);
        }

        private boolean attempt(CountDownLatch start, List<SeatId> seats) throws Exception {
            start.await(30, TimeUnit.SECONDS);
            try {
                service.hold(new HoldSeatsCommand(USER_A, seats));
                return true;
            } catch (QuotaExceededException expected) {
                return false;
            }
        }
    }

    @Nested
    @DisplayName("§6 ★ 외부 트랜잭션이 롤백하면 함께 사라진다")
    class 외부_롤백 {

        /**
         * ★ 서비스가 성공해도 <b>바깥 트랜잭션</b>이 롤백하면 quota 와 좌석이 모두 사라진다.
         *
         * <p>{@code @Transactional} 이 {@code REQUIRED} 이므로 서비스는 바깥 경계에
         * <b>참여</b>한다. 새 트랜잭션을 열어 독립 커밋하지 않는다는 뜻이다.
         * {@code REQUIRES_NEW} 였다면 선점이 그대로 남아 이 테스트가 실패한다.
         */
        @Test
        void 외부_롤백이_quota_와_좌석을_모두_되돌린다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long b = insertAvailableSeat(SCHEDULE, "1B");

            TransactionTemplate outer =
                    new TransactionTemplate(new DataSourceTransactionManager(dataSource()));

            assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
                HoldSeatsResult result = service.hold(new HoldSeatsCommand(USER_A, ids(a, b)));

                assertThat(result.seatCount()).as("트랜잭션 안에서는 성공한다").isEqualTo(2);
                assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value())).isEqualTo(2);

                throw new IllegalStateException("호출자가 롤백시킨다");
            })).isInstanceOf(IllegalStateException.class);

            assertThat(quotaRowCount()).as("★ quota 행 확보까지 되돌아간다").isZero();
            assertThat(statusOf(a)).as("★ 좌석도 함께 되돌아간다").isEqualTo("AVAILABLE");
            assertThat(statusOf(b)).isEqualTo("AVAILABLE");
        }
    }

    /**
     * ★ RED 1 — <b>예외를 삼키면 무슨 일이 일어나는가</b>를 실제로 관측한다.
     *
     * <h2>왜 별도의 테스트가 필요한가</h2>
     *
     * <p>§4 는 올바른 구현을 검증한다. 거기서 잘못된 구현을 넣어 실패시키면
     * {@code assertThatThrownBy} 가 <b>먼저</b> 실패하므로 그 뒤의 DB 상태 어서션에
     * 도달하지 못한다. 즉 §4 의 RED 는 <b>"예외가 나오지 않았다" 까지만</b> 관측한다.
     * "좌석 없이 quota 만 남는다" 는 그것과 <b>다른 주장</b>이고, 따로 관측해야 한다.
     *
     * <p>그래서 이 절은 {@link HoldSeatsService#hold} 와 <b>같은 순서</b>를 그대로 실행하되
     * {@code catch} 하나만 다른 대조군을 트랜잭션 안에서 돌리고,
     * <b>트랜잭션이 커밋된 뒤</b> quota 값과 좌석 상태를 각각 확인한다.
     *
     * <p>운영 서비스에는 이 분기를 넣지 않는다. 대조군은 이 테스트 안에만 있다.
     */
    @Nested
    @DisplayName("§8 ★ RED 1 — 경합 예외를 삼키면 quota 만 남는다")
    class 예외를_삼킨_대조군 {

        /**
         * ★ 대조군: 서비스와 같은 순서를 실행하되 {@link SeatUnavailableException} 만 삼킨다.
         *
         * <p>{@code HoldSeatsService.hold} 의 2~5 단계를 그대로 옮긴 것이며
         * <b>다른 점은 {@code catch} 하나뿐</b>이다.
         */
        private void 삼키는_선점(List<SeatId> seatIds) {
            TransactionTemplate boundary =
                    new TransactionTemplate(new DataSourceTransactionManager(dataSource()));

            boundary.executeWithoutResult(status -> {
                var saleEventId = saleEventScope.resolve(seatIds);
                quota.ensureRow(saleEventId, USER_A);
                quota.tryAcquire(saleEventId, USER_A, seatIds.size());
                try {
                    seatHold.holdAll(seatIds, HoldId.newId(), USER_A);
                } catch (SeatUnavailableException swallowed) {
                    // ★ 여기가 유일한 차이다. 실패를 성공처럼 넘긴다.
                }
            });
        }

        /**
         * ★★ 삼키면 <b>커밋 후에</b> 좌석은 없는데 quota 만 3 이 남는다.
         *
         * <p>좌석의 부분 갱신은 저장소의 NESTED savepoint 가 되돌리지만, savepoint 롤백은
         * 바깥 트랜잭션을 rollback-only 로 만들지 않는다. 그래서 <b>quota 증가는 그대로
         * 커밋된다.</b> 사용자는 잡지도 못한 3석 때문에 상한을 소진한다.
         */
        @Test
        void 예외를_삼키면_좌석_없이_quota_만_커밋된다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long b = insertAvailableSeat(SCHEDULE, "1B");
            long c = insertAvailableSeat(SCHEDULE, "1C");

            service.hold(new HoldSeatsCommand(USER_B, ids(b)));   // b 를 선점당한 상태로 만든다

            assertThatCode(() -> 삼키는_선점(ids(a, b, c)))
                    .as("대조군은 예외를 밖으로 내보내지 않는다 — 여기까지가 §4 의 RED 가 본 전부다")
                    .doesNotThrowAnyException();

            // ★ 여기부터가 §4 의 RED 가 도달하지 못했던 관측이다. 트랜잭션은 이미 커밋됐다.
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value()))
                    .as("★★ quota 만 남았다 — 잡지도 못한 3석이 상한을 소진한다")
                    .isEqualTo(3);
            assertThat(statusOf(a))
                    .as("★★ 그런데 좌석은 없다 — savepoint 가 되돌렸다")
                    .isEqualTo("AVAILABLE");
            assertThat(statusOf(c)).isEqualTo("AVAILABLE");
            assertThat(heldByOf(b)).as("남의 홀드는 그대로다").isEqualTo(USER_B.value());
            assertThat(heldSeatCount())
                    .as("★★ USER_A 가 잡은 좌석은 0석인데 카운터는 3이다")
                    .isEqualTo(1);
        }

        /**
         * ★ 같은 상황에서 <b>올바른 구현</b>은 quota 를 남기지 않는다.
         *
         * <p>위 테스트와 이 테스트의 차이는 {@code catch} 하나다.
         * 순진한 구현의 실패 관측과 올바른 구현의 통과를 같은 조건에서 나란히 둔다.
         */
        @Test
        void 올바른_구현은_같은_조건에서_quota_를_남기지_않는다() {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long b = insertAvailableSeat(SCHEDULE, "1B");
            long c = insertAvailableSeat(SCHEDULE, "1C");

            service.hold(new HoldSeatsCommand(USER_B, ids(b)));

            assertThatThrownBy(() -> service.hold(new HoldSeatsCommand(USER_A, ids(a, b, c))))
                    .isInstanceOf(SeatUnavailableException.class);

            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER_A.value()))
                    .as("★ 대조군은 3 이었다. 올바른 구현은 행이 없거나 0 이다")
                    .isIn(-1, 0);
            assertThat(statusOf(a)).isEqualTo("AVAILABLE");
            assertThat(statusOf(c)).isEqualTo("AVAILABLE");
        }
    }

    @Nested
    @DisplayName("§7 ★ 프록시 없이는 경계가 없다")
    class 프록시_검증 {

        /**
         * ★ 직접 {@code new} 로 만든 서비스에는 트랜잭션 경계가 없다.
         *
         * <p>이 테스트가 존재하는 이유는 <b>다른 테스트들이 프록시를 검증했다는 것을
         * 보이기 위해서다.</b> 손으로 조립해도 통과한다면 위의 원자성 테스트들은
         * 트랜잭션에 대해 아무것도 증명하지 못한 셈이다.
         *
         * <p>경계가 없으면 저장소의 참여 검사가 {@link IllegalStateException} 으로 거부한다.
         * 조용히 autocommit 되지 않는다.
         */
        @Test
        void 손으로_조립한_서비스는_트랜잭션_없음으로_거부된다() {
            long seat = insertAvailableSeat(SCHEDULE, "1A");
            HoldSeatsService unproxied =
                    new HoldSeatsService(saleEventScope, quota, seatHold);

            assertThatThrownBy(() -> unproxied.hold(new HoldSeatsCommand(USER_A, ids(seat))))
                    .as("★ 프록시가 없으면 경계가 없다")
                    .isInstanceOf(IllegalStateException.class);

            assertThat(quotaRowCount()).as("SQL 이 나가기 전에 거부된다").isZero();
            assertThat(statusOf(seat)).isEqualTo("AVAILABLE");
        }
    }

}
