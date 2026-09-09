package com.railgate.reservation.infra.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.infra.MySqlTestSupport;
import com.railgate.reservation.infra.seat.JdbcMultiSeatHoldRepository;
import com.railgate.reservation.saleevent.SaleEventId;
import com.railgate.reservation.seat.SeatId;
import com.railgate.reservation.seat.SeatUnavailableException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * ★ 운영 quota 저장소 (Task 2G-G).
 *
 * <h2>2G-A~F 가 검증한 계약을 운영 코드로 옮긴다</h2>
 *
 * <ul>
 *   <li>집계가 아니라 <b>카운터 행의 조건부 UPDATE</b> — 미커밋 홀드가 보이지 않는 문제 (2G-A)</li>
 *   <li>잠금 순서 quota → seat, 키는 {@code (saleEventId, userId)} 오름차순 (2G-B)</li>
 *   <li>감소량은 후보 수가 아니라 <b>실제 좌석 변경 행 수</b> (2G-C)</li>
 *   <li>범위는 {@code sale_event_id} (2G-D)</li>
 *   <li>없는 행을 자동 생성하지 않는다 (2G-F 실험 C)</li>
 * </ul>
 *
 * <h2>이 저장소가 보장하지 않는 것</h2>
 *
 * <p><b>I-12 는 이 저장소만으로 요청 경로 전체에 강제되지 않는다.</b>
 * 선점 유스케이스가 quota 확보 → 좌석 선점을 <b>한 트랜잭션에서</b> 부르도록 배선해야 하고,
 * 그 배선은 Task 2H 다. 여기서 보장하는 것은 <b>불려졌을 때 원자적이라는 것</b>뿐이다.
 */
@Timeout(300)
@DisplayName("JdbcUserHoldQuotaRepository — 운영 quota 저장소 (Task 2G-G)")
class JdbcUserHoldQuotaRepositoryTest extends MySqlTestSupport {

    private static final Duration HOLD_DURATION = Duration.ofMinutes(5);
    private static final int TIMEOUT_SECONDS = 60;

    private static final SaleEventId CHUSEOK = new SaleEventId(9001L);
    private static final SaleEventId SEOLLAL = new SaleEventId(9002L);
    private static final long SCHEDULE_CHUSEOK = 101L;
    private static final long SCHEDULE_SEOLLAL = 201L;

    private static final UserId USER_A = new UserId(11L);
    private static final UserId USER_B = new UserId(22L);

    private JdbcUserHoldQuotaRepository quota;
    private JdbcMultiSeatHoldRepository holdRepository;

    @BeforeEach
    void setUp() {
        quota = new JdbcUserHoldQuotaRepository(dataSource());
        holdRepository = new JdbcMultiSeatHoldRepository(
                dataSource(), transactionManager(), HOLD_DURATION);

        insertSaleEvent(CHUSEOK, "추석");
        insertSaleEvent(SEOLLAL, "설");
        jdbc().update("INSERT INTO train_schedule (id, sale_event_id) VALUES (?, ?), (?, ?)",
                SCHEDULE_CHUSEOK, CHUSEOK.value(), SCHEDULE_SEOLLAL, SEOLLAL.value());
    }

    private static void insertSaleEvent(SaleEventId id, String name) {
        jdbc().update("""
                INSERT INTO sale_event (id, name, opens_at, status)
                VALUES (?, ?, DATE_SUB(NOW(3), INTERVAL 1 DAY), 'OPEN')
                """, id.value(), name);
    }

    private static Integer heldSeatsOf(SaleEventId saleEventId, UserId user) {
        List<Integer> rows = jdbc().queryForList("""
                SELECT held_seats FROM user_hold_quota
                 WHERE sale_event_id = ? AND user_id = ?
                """, Integer.class, saleEventId.value(), user.value());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static long rowCount() {
        return jdbc().queryForObject("SELECT COUNT(*) FROM user_hold_quota", Long.class);
    }

    /** 호출자가 트랜잭션을 소유한다 — 이 저장소는 스스로 열지 않는다. */
    private void inTx(Runnable work) {
        inTransaction(work);
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("§1 행 확보")
    class 행_확보 {

        @Test
        void 없으면_0_으로_만든다() {
            inTx(() -> quota.ensureRow(CHUSEOK, USER_A));

            assertThat(heldSeatsOf(CHUSEOK, USER_A)).isZero();
        }

        /** ★ 이미 있는 카운터를 0 으로 되돌리면 안 된다. */
        @Test
        void 이미_있으면_값을_보존한다() {
            inTx(() -> {
                quota.ensureRow(CHUSEOK, USER_A);
                assertThat(quota.tryAcquire(CHUSEOK, USER_A, 3))
                        .isEqualTo(QuotaAcquireOutcome.ACQUIRED);
            });

            inTx(() -> quota.ensureRow(CHUSEOK, USER_A));

            assertThat(heldSeatsOf(CHUSEOK, USER_A))
                    .as("★ 재확보가 값을 덮으면 이미 잡은 좌석이 사라진다")
                    .isEqualTo(3);
        }

        @Test
        void 반복_호출해도_행은_하나다() {
            inTx(() -> {
                quota.ensureRow(CHUSEOK, USER_A);
                quota.ensureRow(CHUSEOK, USER_A);
                quota.ensureRow(CHUSEOK, USER_A);
            });

            assertThat(rowCount()).isEqualTo(1);
        }

        @Test
        void 존재하지_않는_회차는_외래_키가_거부한다() {
            assertThatThrownBy(() -> inTx(() -> quota.ensureRow(new SaleEventId(8888L), USER_A)))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
    }

    @Nested
    @DisplayName("§2 확보 — 조건부 증가")
    class 확보 {

        @BeforeEach
        void givenRow() {
            inTx(() -> quota.ensureRow(CHUSEOK, USER_A));
        }

        @Test
        void 상한_안에서는_확보된다() {
            inTx(() -> assertThat(quota.tryAcquire(CHUSEOK, USER_A, 3))
                    .isEqualTo(QuotaAcquireOutcome.ACQUIRED));

            assertThat(heldSeatsOf(CHUSEOK, USER_A)).isEqualTo(3);
        }

        @Test
        void 상한을_넘으면_거부하고_값을_바꾸지_않는다() {
            inTx(() -> quota.tryAcquire(CHUSEOK, USER_A, 3));

            inTx(() -> assertThat(quota.tryAcquire(CHUSEOK, USER_A, 2))
                    .as("3 + 2 = 5 는 상한 4 를 넘는다")
                    .isEqualTo(QuotaAcquireOutcome.LIMIT_EXCEEDED));

            assertThat(heldSeatsOf(CHUSEOK, USER_A))
                    .as("거부된 확보는 값을 바꾸지 않는다")
                    .isEqualTo(3);
        }

        @Test
        void 정확히_상한까지는_확보된다() {
            inTx(() -> {
                quota.tryAcquire(CHUSEOK, USER_A, 2);
                assertThat(quota.tryAcquire(CHUSEOK, USER_A, 2))
                        .isEqualTo(QuotaAcquireOutcome.ACQUIRED);
            });

            assertThat(heldSeatsOf(CHUSEOK, USER_A)).isEqualTo(4);
        }

        /** 없는 행에 대한 확보는 거부된다. 자동 생성하지 않는다. */
        @Test
        void 없는_행에는_확보되지_않는다() {
            inTx(() -> assertThat(quota.tryAcquire(SEOLLAL, USER_B, 1))
                    .isEqualTo(QuotaAcquireOutcome.LIMIT_EXCEEDED));

            assertThat(heldSeatsOf(SEOLLAL, USER_B))
                    .as("행을 만들어내지 않는다")
                    .isNull();
        }
    }

    @Nested
    @DisplayName("§3 감소 — 실제 변경 행 수만큼")
    class 감소 {

        @BeforeEach
        void givenThreeSeats() {
            inTx(() -> {
                quota.ensureRow(CHUSEOK, USER_A);
                quota.tryAcquire(CHUSEOK, USER_A, 3);
            });
        }

        @Test
        void 보유량_안에서는_감소한다() {
            inTx(() -> assertThat(quota.release(CHUSEOK, USER_A, 2))
                    .isEqualTo(QuotaReleaseOutcome.RELEASED));

            assertThat(heldSeatsOf(CHUSEOK, USER_A)).isEqualTo(1);
        }

        /**
         * ★ 음수가 될 요청은 거부한다. 이중 감소나 과대 계산의 방어선이다.
         *
         * <p>4 는 인자 범위 안이지만 보유량 3 에서 빼면 음수가 된다 — 그래서 조건부 UPDATE 가
         * 걸러야 하는 값이다. 범위를 벗어난 5 는 인자 검증이 따로 막는다 (§7).
         */
        @Test
        void 음수가_될_감소는_거부하고_값을_바꾸지_않는다() {
            inTx(() -> assertThat(quota.release(CHUSEOK, USER_A, 4))
                    .isEqualTo(QuotaReleaseOutcome.REJECTED));

            assertThat(heldSeatsOf(CHUSEOK, USER_A)).isEqualTo(3);
        }

        /** ★ 없는 행을 자동 생성하지 않는다 (2G-F 실험 C). */
        @Test
        void 없는_행의_감소는_거부하고_행을_만들지_않는다() {
            inTx(() -> assertThat(quota.release(SEOLLAL, USER_B, 1))
                    .isEqualTo(QuotaReleaseOutcome.REJECTED));

            assertThat(heldSeatsOf(SEOLLAL, USER_B)).isNull();
            assertThat(rowCount()).isEqualTo(1);
        }

        @Test
        void 전량_감소하면_0_이_된다() {
            inTx(() -> assertThat(quota.release(CHUSEOK, USER_A, 3))
                    .isEqualTo(QuotaReleaseOutcome.RELEASED));

            assertThat(heldSeatsOf(CHUSEOK, USER_A))
                    .as("행은 남는다 — 0 은 정상 상태다")
                    .isZero();
        }
    }

    @Nested
    @DisplayName("§4 잠금")
    class 잠금 {

        @Test
        void 있는_행을_잠그고_현재_값을_돌려준다() {
            inTx(() -> {
                quota.ensureRow(CHUSEOK, USER_A);
                quota.tryAcquire(CHUSEOK, USER_A, 2);
                assertThat(quota.lockRow(CHUSEOK, USER_A)).contains(2);
            });
        }

        /**
         * ★ 없는 행은 빈 값이다 — 2G-F 가 drift 로 취급하는 신호다.
         *
         * <p><b>빈 값은 "일치하는 행이 없다" 는 뜻이지 "잠금이 전혀 없다" 는 뜻이 아니다.</b>
         * InnoDB 는 격리 수준과 조회 조건에 따라 없는 키에도 갭 잠금을 잡을 수 있다.
         * 여기서 검증하는 것은 <b>반환값과 행이 생기지 않는다는 것</b>뿐이다.
         */
        @Test
        void 없는_행은_비어_있다() {
            inTx(() -> assertThat(quota.lockRow(SEOLLAL, USER_B)).isEmpty());

            assertThat(rowCount()).as("행을 만들어내지 않는다").isZero();
        }
    }

    /**
     * ★ §5 트랜잭션 계약 — 변경과 잠금은 호출자의 트랜잭션에 참여한다.
     *
     * <p>독립 커밋을 만들면 quota 와 좌석이 서로 다른 시점에 확정되고, 좌석 선점이 실패해도
     * quota 만 남는다. 그래서 <b>SQL 실행 전에 거부</b>한다 — DB 를 건드리고 나서 알리면
     * 이미 늦다.
     *
     * <p>"다른 DataSource 의 트랜잭션" 과 "바인딩만 된 커넥션" 까지 구분하는 검증은
     * {@code QuotaTransactionEnlistmentTest} 가 따로 다룬다.
     */
    @Nested
    @DisplayName("§5 ★ 트랜잭션 계약")
    class 트랜잭션_계약 {

        @Test
        void 트랜잭션_없는_변경과_잠금은_거부된다() {
            assertThatThrownBy(() -> quota.ensureRow(CHUSEOK, USER_A))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("트랜잭션");
            assertThatThrownBy(() -> quota.tryAcquire(CHUSEOK, USER_A, 1))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> quota.release(CHUSEOK, USER_A, 1))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> quota.lockRow(CHUSEOK, USER_A))
                    .isInstanceOf(IllegalStateException.class);
        }

        /** ★ 거부는 SQL 실행 전이다 — 아무 흔적도 남지 않아야 한다. */
        @Test
        void 거부된_호출은_DB_를_건드리지_않는다() {
            assertThatThrownBy(() -> quota.ensureRow(CHUSEOK, USER_A))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(rowCount()).isZero();
        }

        /** ★ 외부 롤백이면 quota 변경도 함께 사라진다. */
        @Test
        void 외부_롤백이면_quota_변경도_복구된다() {
            assertThatThrownBy(() -> inTx(() -> {
                quota.ensureRow(CHUSEOK, USER_A);
                quota.tryAcquire(CHUSEOK, USER_A, 3);
                throw new IllegalStateException("호출자가 롤백시킨다");
            })).isInstanceOf(IllegalStateException.class);

            assertThat(rowCount()).as("행 확보까지 되돌아간다").isZero();
        }

        /**
         * ★ 좌석 선점 실패가 전파되면 quota 증가도 롤백된다.
         *
         * <p>이것이 "quota 와 좌석이 같은 트랜잭션이어야 한다" 는 계약의 실제 의미다.
         * 좌석은 못 잡았는데 카운터만 늘면 그 사용자는 영원히 손해를 본다.
         */
        @Test
        void 좌석_선점_실패가_전파되면_quota_증가도_롤백된다() {
            SeatId taken = new SeatId(insertAvailableSeat(SCHEDULE_CHUSEOK, "1A"));
            // 다른 사용자가 먼저 잡아 둔다.
            inTransaction(() -> holdRepository.holdAll(List.of(taken), HoldId.newId(), USER_B));
            inTx(() -> quota.ensureRow(CHUSEOK, USER_A));

            assertThatThrownBy(() -> inTx(() -> {
                assertThat(quota.tryAcquire(CHUSEOK, USER_A, 1))
                        .isEqualTo(QuotaAcquireOutcome.ACQUIRED);
                holdRepository.holdAll(List.of(taken), HoldId.newId(), USER_A);
            })).isInstanceOf(SeatUnavailableException.class);

            assertThat(heldSeatsOf(CHUSEOK, USER_A))
                    .as("★ 좌석을 못 잡았으므로 카운터도 0 이어야 한다")
                    .isZero();
        }

        /** 정상 경로에서는 둘이 함께 커밋된다. */
        @Test
        void 성공하면_quota_와_좌석이_함께_커밋된다() {
            List<SeatId> seats = List.of(
                    new SeatId(insertAvailableSeat(SCHEDULE_CHUSEOK, "1A")),
                    new SeatId(insertAvailableSeat(SCHEDULE_CHUSEOK, "2A")));
            inTx(() -> quota.ensureRow(CHUSEOK, USER_A));

            inTx(() -> {
                assertThat(quota.tryAcquire(CHUSEOK, USER_A, 2))
                        .isEqualTo(QuotaAcquireOutcome.ACQUIRED);
                holdRepository.holdAll(seats, HoldId.newId(), USER_A);
            });

            assertThat(heldSeatsOf(CHUSEOK, USER_A)).isEqualTo(2);
            assertThat(jdbc().queryForObject("""
                    SELECT COUNT(*) FROM seat_inventory
                     WHERE held_by = ? AND status = 'HELD'
                    """, Long.class, USER_A.value())).isEqualTo(2);
        }
    }

    /**
     * ★ §6 동시성 — 2G-A 가 재현한 문제를 운영 저장소가 막는지.
     */
    @Nested
    @DisplayName("§6 ★ 동시성")
    class 동시성 {

        @Test
        void 동시_최초_요청에서도_행은_하나만_생성된다() throws Exception {
            List<Boolean> results = race(16, i -> {
                inTx(() -> quota.ensureRow(CHUSEOK, USER_A));
                return true;
            });

            assertThat(results).hasSize(16).allMatch(ok -> ok);
            assertThat(rowCount()).as("★ 중복 키 예외 없이 행이 하나뿐이어야 한다").isEqualTo(1);
            assertThat(heldSeatsOf(CHUSEOK, USER_A)).isZero();
        }

        /**
         * ★ 2G-A 의 핵심 재현 — 같은 사용자의 동시 3석 요청 두 개.
         *
         * <p>집계로 검사하면 둘 다 "내 것 0석" 을 보고 통과해 6석이 된다.
         * 조건부 UPDATE 는 같은 행에서 직렬화되므로 하나만 통과한다.
         */
        @Test
        void 동시_3석_요청_두_개_중_하나만_성공한다() throws Exception {
            inTx(() -> quota.ensureRow(CHUSEOK, USER_A));

            List<QuotaAcquireOutcome> outcomes = race(2, i ->
                    inTxReturning(() -> quota.tryAcquire(CHUSEOK, USER_A, 3)));

            assertThat(outcomes.stream().filter(o -> o == QuotaAcquireOutcome.ACQUIRED).count())
                    .as("★ 3 + 3 = 6 은 상한 4 를 넘는다. 하나만 성공해야 한다")
                    .isEqualTo(1);
            assertThat(heldSeatsOf(CHUSEOK, USER_A))
                    .as("최종 값은 3 이다")
                    .isEqualTo(3);
        }

        @Test
        void 동시_요청이_많아도_상한을_넘지_않는다() throws Exception {
            inTx(() -> quota.ensureRow(CHUSEOK, USER_A));

            List<QuotaAcquireOutcome> outcomes = race(16, i ->
                    inTxReturning(() -> quota.tryAcquire(CHUSEOK, USER_A, 1)));

            assertThat(outcomes.stream().filter(o -> o == QuotaAcquireOutcome.ACQUIRED).count())
                    .isEqualTo(4);
            assertThat(heldSeatsOf(CHUSEOK, USER_A)).isEqualTo(4);
        }

        /** 다른 사용자와 다른 회차의 카운터는 서로 독립이다 (2G-D). */
        @Test
        void 서로_다른_사용자와_회차의_quota_는_독립이다() throws Exception {
            inTx(() -> {
                quota.ensureRow(CHUSEOK, USER_A);
                quota.ensureRow(CHUSEOK, USER_B);
                quota.ensureRow(SEOLLAL, USER_A);
            });

            race(12, i -> {
                SaleEventId event = i % 3 == 2 ? SEOLLAL : CHUSEOK;
                UserId user = i % 3 == 1 ? USER_B : USER_A;
                return inTxReturning(() -> quota.tryAcquire(event, user, 1));
            });

            assertThat(heldSeatsOf(CHUSEOK, USER_A)).isEqualTo(4);
            assertThat(heldSeatsOf(CHUSEOK, USER_B)).isEqualTo(4);
            assertThat(heldSeatsOf(SEOLLAL, USER_A))
                    .as("★ 같은 사용자라도 회차가 다르면 별개다")
                    .isEqualTo(4);
        }

        private <T> T inTxReturning(java.util.function.Supplier<T> work) {
            return newTransactionTemplate().execute(status -> work.get());
        }

        /** 배리어로 동시 출발시키고 <b>모든 Future 의 결과·예외를 회수한다.</b> */
        private <T> List<T> race(int workers, IntFunction<T> action) throws Exception {
            CountDownLatch ready = new CountDownLatch(workers);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();

            ExecutorService pool = Executors.newFixedThreadPool(workers);
            try {
                for (int i = 0; i < workers; i++) {
                    int index = i;
                    futures.add(pool.submit(() -> {
                        ready.countDown();
                        start.await();
                        return action.apply(index);
                    }));
                }
                assertThat(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        .as("작업자 %d개가 출발선에 서야 한다", workers).isTrue();
                start.countDown();

                pool.shutdown();
                assertThat(pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        .as("%d초 안에 끝나야 한다", TIMEOUT_SECONDS).isTrue();
            } finally {
                pool.shutdownNow();
            }

            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    @Nested
    @DisplayName("§7 인자 검증")
    class 인자_검증 {

        @Test
        void null_을_거부한다() {
            assertThatThrownBy(() -> new JdbcUserHoldQuotaRepository(null))
                    .isInstanceOf(NullPointerException.class);
            inTx(() -> {
                assertThatThrownBy(() -> quota.ensureRow(null, USER_A))
                        .isInstanceOf(NullPointerException.class);
                assertThatThrownBy(() -> quota.tryAcquire(CHUSEOK, null, 1))
                        .isInstanceOf(NullPointerException.class);
                assertThatThrownBy(() -> quota.release(null, USER_A, 1))
                        .isInstanceOf(NullPointerException.class);
                assertThatThrownBy(() -> quota.lockRow(CHUSEOK, null))
                        .isInstanceOf(NullPointerException.class);
            });
        }

        /** 확보 수량은 P-2 범위(1~4)여야 한다. 0 과 상한 초과는 요청 자체가 잘못됐다. */
        @Test
        void 확보_수량은_1_에서_4_사이여야_한다() {
            inTx(() -> {
                assertThatThrownBy(() -> quota.tryAcquire(CHUSEOK, USER_A, 0))
                        .isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> quota.tryAcquire(CHUSEOK, USER_A, -1))
                        .isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> quota.tryAcquire(CHUSEOK, USER_A, 5))
                        .as("한 요청이 상한을 넘을 수는 없다 (SeatCount 와 같은 범위)")
                        .isInstanceOf(IllegalArgumentException.class);
            });
        }

        /**
         * ★ 감소량 0 은 거부한다.
         *
         * <p>2G-F 의 스위퍼는 {@code affected_rows == 0} 이면 감소 SQL 자체를 보내지 않는다.
         * 그 계약을 저장소가 강제한다 — 0 을 허용하면 "행이 없어서 0" 과 "줄일 것이 없어서 0" 이
         * 같은 결과가 되어 drift 를 숨긴다.
         */
        @Test
        void 감소량_0_은_거부한다() {
            inTx(() -> {
                assertThatThrownBy(() -> quota.release(CHUSEOK, USER_A, 0))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("0");
                assertThatThrownBy(() -> quota.release(CHUSEOK, USER_A, -1))
                        .isInstanceOf(IllegalArgumentException.class);
            });
        }

        /** 감소량 상한은 확보와 같다. 그보다 크면 애초에 그만큼 잡을 수 없었다. */
        @Test
        void 감소량도_상한을_넘을_수_없다() {
            inTx(() -> assertThatThrownBy(() -> quota.release(CHUSEOK, USER_A, 5))
                    .isInstanceOf(IllegalArgumentException.class));
        }

        @Test
        void 인자_검증은_트랜잭션_검사_뒤에_온다() {
            assertThatThrownBy(() -> quota.tryAcquire(CHUSEOK, USER_A, 0))
                    .as("트랜잭션이 없으면 그것부터 알린다")
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void 유효한_호출은_예외를_던지지_않는다() {
            assertThatCode(() -> inTx(() -> {
                quota.ensureRow(CHUSEOK, USER_A);
                quota.tryAcquire(CHUSEOK, USER_A, 4);
                quota.lockRow(CHUSEOK, USER_A);
                quota.release(CHUSEOK, USER_A, 4);
            })).doesNotThrowAnyException();
        }
    }

    /** 잠금 순서를 정하려면 키가 정렬 가능해야 한다 (2G-B). */
    @Test
    void quota_키는_정렬_가능하다() {
        List<QuotaKey> keys = new ArrayList<>(List.of(
                new QuotaKey(SEOLLAL, USER_A),
                new QuotaKey(CHUSEOK, USER_B),
                new QuotaKey(CHUSEOK, USER_A)));

        java.util.Collections.sort(keys);

        assertThat(keys).containsExactly(
                new QuotaKey(CHUSEOK, USER_A),
                new QuotaKey(CHUSEOK, USER_B),
                new QuotaKey(SEOLLAL, USER_A));
    }

    @Test
    void 잠금은_키_오름차순으로_수행한다() {
        inTx(() -> {
            quota.ensureRow(CHUSEOK, USER_A);
            quota.ensureRow(CHUSEOK, USER_B);
            quota.ensureRow(SEOLLAL, USER_A);
        });

        List<QuotaKey> unordered = List.of(
                new QuotaKey(SEOLLAL, USER_A),
                new QuotaKey(CHUSEOK, USER_B),
                new QuotaKey(CHUSEOK, USER_A));

        inTx(() -> {
            List<QuotaKey> lockedInOrder = quota.lockAll(unordered);
            assertThat(lockedInOrder)
                    .as("★ 호출자가 어떤 순서로 넘겨도 잠금은 오름차순이다")
                    .containsExactly(
                            new QuotaKey(CHUSEOK, USER_A),
                            new QuotaKey(CHUSEOK, USER_B),
                            new QuotaKey(SEOLLAL, USER_A));
        });
    }

    /** 없는 행이 섞이면 그 키를 알려준다 — 조용히 건너뛰지 않는다. */
    @Test
    void lockAll_은_없는_행을_알린다() {
        inTx(() -> quota.ensureRow(CHUSEOK, USER_A));

        inTx(() -> assertThatThrownBy(() -> quota.lockAll(List.of(
                new QuotaKey(CHUSEOK, USER_A),
                new QuotaKey(CHUSEOK, USER_B))))
                .isInstanceOf(QuotaRowMissingException.class)
                .hasMessageContaining(String.valueOf(USER_B.value())));
    }

    private static Optional<Integer> unusedImportGuard() {
        return Optional.empty();
    }
}
