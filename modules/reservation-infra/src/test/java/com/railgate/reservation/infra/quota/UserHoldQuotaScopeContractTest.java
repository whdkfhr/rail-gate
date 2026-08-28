package com.railgate.reservation.infra.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.ReservationId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.infra.MySqlTestSupport;
import com.railgate.reservation.infra.seat.JdbcMultiSeatHoldRepository;
import com.railgate.reservation.infra.seat.JdbcSeatPaymentRepository;
import com.railgate.reservation.infra.seat.SeatConfirmationOutcome;
import com.railgate.reservation.infra.seat.SeatPaymentOutcome;
import com.railgate.reservation.seat.SeatId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ★ quota 범위 계약 — 운행편 단위인가 판매 이벤트 단위인가 (Task 2G-D).
 *
 * <h2>문제</h2>
 *
 * <p>FR-2.6 은 "한 사용자는 동시에 4석을 초과해 선점할 수 없다" 고만 적혀 있고 <b>범위가 없다.</b>
 * INVARIANTS.md 의 I-12 예시는 {@code event_id} 를 쓰지만 {@code seat_inventory} 에는
 * {@code schedule_id} 만 있다. 그래서 그동안 실험은 중립적인 {@code quota_scope_id} 를 썼다.
 *
 * <h2>§1 은 "통과하면 좋은" 테스트가 아니다</h2>
 *
 * <p>§1 이 통과한다는 것은 <b>운행편 단위 범위가 상한을 우회시키는 것을 관측했다</b>는 뜻이지
 * 그 설계가 옳다는 뜻이 아니다 (CLAUDE.md 규칙 27). 채택한 계약은 §2 다.
 *
 * <h2>범위</h2>
 *
 * <p>정책 결정과 계약 검증 단계다. 운영 {@code SaleEvent} aggregate·{@code train_schedule} 테이블·
 * {@code user_hold_quota} 마이그레이션·운영 저장소·애플리케이션 서비스를 만들지 않았다.
 * <b>I-12 는 여전히 운영에 구현되지 않았다.</b>
 *
 * <p>원자적 상한 검사(조건부 UPDATE) 자체는 Task 2G-A 가 이미 검증했다. 이 Task 가 더하는 것은
 * <b>그 검사가 어느 범위에 적용되어야 하는가</b> 뿐이다.
 */
@Timeout(180)
@DisplayName("quota 범위 계약 — 판매 이벤트 단위 (Task 2G-D)")
class UserHoldQuotaScopeContractTest extends MySqlTestSupport {

    private static final Duration HOLD_DURATION = Duration.ofMinutes(5);

    /** 같은 판매 이벤트에 속하는 서로 다른 열차 운행편. */
    private static final long SCHEDULE_A = 101L;
    private static final long SCHEDULE_B = 102L;
    /** 다른 판매 이벤트에 속하는 운행편. */
    private static final long SCHEDULE_C = 201L;
    /** 어느 판매 이벤트에도 배정되지 않은 운행편. */
    private static final long SCHEDULE_UNMAPPED = 999L;

    private static final long SALE_EVENT_CHUSEOK = 9001L;
    private static final long SALE_EVENT_SEOLLAL = 9002L;

    private static final UserId USER = new UserId(7L);
    private static final UserId OTHER_USER = new UserId(9L);

    private JdbcMultiSeatHoldRepository holdRepository;
    private JdbcSeatPaymentRepository paymentRepository;
    private Task2gQuotaCounter quota;
    private Task2gSaleEventScope scope;
    private List<Long> seatsA;
    private List<Long> seatsB;
    private List<Long> seatsC;
    private List<Long> seatsUnmapped;

    @BeforeEach
    void setUp() {
        Task2gQuotaCounter.createTable(jdbc());
        Task2gQuotaCounter.truncate(jdbc());

        holdRepository = new JdbcMultiSeatHoldRepository(
                dataSource(), transactionManager(), HOLD_DURATION);
        paymentRepository = new JdbcSeatPaymentRepository(dataSource(), HOLD_DURATION);
        quota = new Task2gQuotaCounter(dataSource());

        scope = new Task2gSaleEventScope();
        // 추석 판매 회차에 운행편 두 개가 속한다. 설 판매 회차는 별개다.
        scope.assign(SCHEDULE_A, SALE_EVENT_CHUSEOK);
        scope.assign(SCHEDULE_B, SALE_EVENT_CHUSEOK);
        scope.assign(SCHEDULE_C, SALE_EVENT_SEOLLAL);

        seatsA = insertSeats(SCHEDULE_A);
        seatsB = insertSeats(SCHEDULE_B);
        seatsC = insertSeats(SCHEDULE_C);
        // 어느 판매 이벤트에도 배정하지 않은 운행편. scope 조회가 실패해야 한다.
        seatsUnmapped = insertSeats(SCHEDULE_UNMAPPED);
    }

    private static List<Long> insertSeats(long scheduleId) {
        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            ids.add(insertAvailableSeat(scheduleId, i + "A"));
        }
        return ids;
    }

    private static List<SeatId> seatIds(List<Long> ids, int count) {
        return ids.subList(0, count).stream().map(SeatId::new).toList();
    }

    /** 이 사용자가 해당 운행편들에서 점유한 활성 좌석 수. */
    private long activeSeatsIn(UserId user, long... scheduleIds) {
        long total = 0;
        for (long scheduleId : scheduleIds) {
            total += jdbc().queryForObject("""
                    SELECT COUNT(*) FROM seat_inventory
                     WHERE held_by = ? AND schedule_id = ? AND status IN ('HELD', 'PAYING')
                    """, Long.class, user.value(), scheduleId);
        }
        return total;
    }

    /** quota 테이블 전체 행 수와 held_seats 합계. 부수 효과 부재를 확인하는 데 쓴다. */
    private record QuotaSnapshot(long rows, long totalHeldSeats) { }

    private QuotaSnapshot quotaSnapshot() {
        Long rows = jdbc().queryForObject(
                "SELECT COUNT(*) FROM " + Task2gQuotaCounter.TABLE, Long.class);
        Long sum = jdbc().queryForObject(
                "SELECT COALESCE(SUM(held_seats), 0) FROM " + Task2gQuotaCounter.TABLE, Long.class);
        return new QuotaSnapshot(rows, sum);
    }

    /** 운행편이 아니라 그 운행편이 속한 판매 이벤트를 범위로 쓴다. */
    private HoldId holdInSaleEvent(UserId user, long scheduleId, List<Long> seats, int count) {
        // 매핑 조회가 먼저다. 미매핑이면 좌석·quota 를 건드리기 전에 여기서 끝난다.
        return holdWithQuota(user, scope.saleEventIdOf(scheduleId), seats, count);
    }

    private int quotaOf(UserId user, long saleEventId) {
        return quota.heldSeats(user.value(), saleEventId);
    }

    /** quota 확보 실패를 나타내는 테스트 전용 신호. 운영 예외를 추가하지 않는다. */
    private static final class QuotaRejected extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    /**
     * 하나의 선점 요청 — quota 를 먼저 확보하고 성공한 경우에만 좌석을 잡는다.
     *
     * @param scopeId quota 범위 키. §1 은 여기에 {@code scheduleId} 를, §2 는 {@code saleEventId} 를 넣는다.
     */
    private HoldId holdWithQuota(UserId user, long scopeId, List<Long> seats, int count) {
        HoldId holdId = HoldId.newId();
        TransactionTemplate tx = newTransactionTemplate();
        tx.executeWithoutResult(status -> {
            quota.ensureRow(user.value(), scopeId);
            if (!quota.tryAcquire(user.value(), scopeId, count)) {
                throw new QuotaRejected();
            }
            holdRepository.holdAll(seatIds(seats, count), holdId, user);
        });
        // 결제 경로(startPayment/confirm)는 hold_id 를 조건으로 쓰므로 돌려준다.
        return holdId;
    }

    // ==================================================================

    @Nested
    @DisplayName("1. ★ 실패 재현 — 운행편을 quota 범위로 쓰면 상한이 우회된다")
    class 운행편_범위_우회 {

        /**
         * <b>이 테스트가 통과하는 것은 우회를 관측했다는 뜻이다.</b>
         *
         * <p>{@code schedule_id} 를 범위 키로 쓰면 운행편마다 별도의 카운터 행이 생긴다.
         * 같은 사용자가 <b>같은 판매 이벤트 안에서</b> 운행편만 바꿔가며 각각 4석까지 잡을 수 있다.
         * 운행편이 100개면 최대 400석이다. FR-2.6 의 사재기 방지 목적이 달성되지 않는다.
         */
        @Test
        void schedule_을_quota_scope_로_쓰면_같은_판매_이벤트에서_6석을_허용한다() {
            holdWithQuota(USER, SCHEDULE_A, seatsA, 3);
            holdWithQuota(USER, SCHEDULE_B, seatsB, 3);

            assertThat(quota.heldSeats(USER.value(), SCHEDULE_A))
                    .as("운행편 A 카운터는 3 이라 상한을 지킨 것처럼 보인다").isEqualTo(3);
            assertThat(quota.heldSeats(USER.value(), SCHEDULE_B))
                    .as("운행편 B 카운터도 따로 3").isEqualTo(3);

            assertThat(activeSeatsIn(USER, SCHEDULE_A, SCHEDULE_B))
                    .as("★ 그러나 같은 판매 이벤트에서 실제로는 6석을 점유했다 — I-12 위반")
                    .isEqualTo(6);
        }

        @Test
        void 두_운행편이_같은_판매_이벤트에_속함에도_카운터가_분리된다() {
            assertThat(scope.saleEventIdOf(SCHEDULE_A))
                    .as("두 운행편은 같은 판매 회차다")
                    .isEqualTo(scope.saleEventIdOf(SCHEDULE_B));

            holdWithQuota(USER, SCHEDULE_A, seatsA, 4);

            // 운행편 범위에서는 A 가 이미 상한인데도 B 요청이 통과한다.
            holdWithQuota(USER, SCHEDULE_B, seatsB, 4);

            assertThat(activeSeatsIn(USER, SCHEDULE_A, SCHEDULE_B))
                    .as("★ 같은 판매 이벤트에서 8석")
                    .isEqualTo(8);
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("2. 채택 — 판매 이벤트 단위로 합산한다")
    class 판매_이벤트_범위 {

        @Test
        void 같은_판매_이벤트의_두_운행편에서_각각_3석을_동시_요청하면_하나만_성공한다()
                throws Exception {
            CyclicBarrier beforeAcquire = new CyclicBarrier(2);
            quota.ensureRow(USER.value(), SALE_EVENT_CHUSEOK);

            AtomicInteger succeeded = new AtomicInteger();
            AtomicInteger rejected = new AtomicInteger();
            List<String> errors = new CopyOnWriteArrayList<>();
            CountDownLatch done = new CountDownLatch(2);

            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                for (Object[] plan : new Object[][] {
                        {SCHEDULE_A, seatsA}, {SCHEDULE_B, seatsB}}) {
                    @SuppressWarnings("unchecked")
                    List<Long> seats = (List<Long>) plan[1];
                    long scheduleId = (long) plan[0];
                    pool.submit(() -> {
                        try {
                            // 배리어는 quota 잠금 획득 직전에 둔다. 그 뒤부터는 카운터 행의
                            // 행 잠금이 두 트랜잭션을 직렬화한다 (Task 2G-A 에서 확인한 성질).
                            beforeAcquire.await(60, TimeUnit.SECONDS);
                            holdInSaleEvent(USER, scheduleId, seats, 3);
                            succeeded.incrementAndGet();
                        } catch (QuotaRejected e) {
                            rejected.incrementAndGet();
                        } catch (Exception e) {
                            errors.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                        } finally {
                            done.countDown();
                        }
                    });
                }
                assertThat(done.await(90, TimeUnit.SECONDS)).as("두 작업자 종료").isTrue();
            }

            assertThat(errors).as("시스템 오류 / deadlock / lock timeout").isEmpty();
            assertThat(succeeded.get()).as("3 + 3 = 6 > 4 이므로 하나만 성공").isEqualTo(1);
            assertThat(rejected.get()).isEqualTo(1);
            assertThat(quotaOf(USER, SALE_EVENT_CHUSEOK)).as("최종 quota").isEqualTo(3);
            assertThat(activeSeatsIn(USER, SCHEDULE_A, SCHEDULE_B))
                    .as("총 활성 선점은 4석 이하").isLessThanOrEqualTo(4);
        }

        @Test
        void 같은_판매_이벤트에서_2석씩_두_운행편은_합산해_정확히_4석이_된다() {
            holdInSaleEvent(USER, SCHEDULE_A, seatsA, 2);
            holdInSaleEvent(USER, SCHEDULE_B, seatsB, 2);

            assertThat(quotaOf(USER, SALE_EVENT_CHUSEOK)).as("2 + 2 = 4").isEqualTo(4);
            assertThat(activeSeatsIn(USER, SCHEDULE_A, SCHEDULE_B)).isEqualTo(4);
        }

        @Test
        void 이미_3석을_가진_상태에서_다른_운행편_2석_요청은_거부된다() {
            holdInSaleEvent(USER, SCHEDULE_A, seatsA, 3);

            assertThatThrownBy(() -> holdInSaleEvent(USER, SCHEDULE_B, seatsB, 2))
                    .as("3 + 2 = 5 > 4")
                    .isInstanceOf(QuotaRejected.class);

            assertThat(quotaOf(USER, SALE_EVENT_CHUSEOK)).as("기존 quota 유지").isEqualTo(3);
            assertThat(activeSeatsIn(USER, SCHEDULE_B)).as("운행편 B 좌석 미점유").isZero();
        }

        @Test
        void 서로_다른_판매_이벤트에서는_각각_4석까지_독립적으로_성공한다() {
            holdInSaleEvent(USER, SCHEDULE_A, seatsA, 4);
            holdInSaleEvent(USER, SCHEDULE_C, seatsC, 4);

            assertThat(quotaOf(USER, SALE_EVENT_CHUSEOK)).isEqualTo(4);
            assertThat(quotaOf(USER, SALE_EVENT_SEOLLAL)).isEqualTo(4);
            assertThat(activeSeatsIn(USER, SCHEDULE_A, SCHEDULE_B)).isEqualTo(4);
            assertThat(activeSeatsIn(USER, SCHEDULE_C)).isEqualTo(4);
        }

        @Test
        void 한_판매_이벤트에서_상한에_도달해도_다른_이벤트_요청에는_영향이_없다() {
            holdInSaleEvent(USER, SCHEDULE_A, seatsA, 4);

            assertThatThrownBy(() -> holdInSaleEvent(USER, SCHEDULE_B, seatsB, 1))
                    .as("추석 회차는 상한")
                    .isInstanceOf(QuotaRejected.class);

            // 설 회차는 별개 행이므로 영향이 없다.
            holdInSaleEvent(USER, SCHEDULE_C, seatsC, 3);

            assertThat(quotaOf(USER, SALE_EVENT_CHUSEOK)).isEqualTo(4);
            assertThat(quotaOf(USER, SALE_EVENT_SEOLLAL)).isEqualTo(3);
        }

        @Test
        void 다른_사용자는_같은_판매_이벤트에서도_각자의_quota_행을_쓴다() {
            holdInSaleEvent(USER, SCHEDULE_A, seatsA, 4);
            holdInSaleEvent(OTHER_USER, SCHEDULE_B, seatsB, 4);

            assertThat(quotaOf(USER, SALE_EVENT_CHUSEOK)).isEqualTo(4);
            assertThat(quotaOf(OTHER_USER, SALE_EVENT_CHUSEOK)).isEqualTo(4);
            assertThat(activeSeatsIn(USER, SCHEDULE_A)).isEqualTo(4);
            assertThat(activeSeatsIn(OTHER_USER, SCHEDULE_B)).isEqualTo(4);
        }

        /**
         * 활성 quota 대상은 {@code HELD}·{@code PAYING} 뿐이라는 <b>좌석 상태 기반 분류</b>를 검증한다.
         *
         * <p>상태를 직접 UPDATE 하지 않고 <b>운영 결제 경로</b>로 전이시킨다 —
         * {@code holdAll} → {@link JdbcSeatPaymentRepository#startPayment}
         * → {@link JdbcSeatPaymentRepository#confirm}. 그래야 "SOLD 를 제외한다" 가
         * 실제 전이를 거친 좌석에 대한 주장이 된다.
         *
         * <p>네 상태를 동시에 만든다: {@code SOLD} / {@code PAYING} / {@code HELD} / {@code AVAILABLE}.
         *
         * <p><b>이 테스트가 검증하지 않는 것.</b> 확정 시 quota 카운터를 줄이는 경로는
         * 아직 구현 범위가 아니다(2G-C 에서 후속 과제로 남겼다). 그래서 좌석이 SOLD 가 돼도
         * 카운터는 그대로다 — <b>counter 감소나 reconciliation 완성 여부는 여기서 주장하지 않는다.</b>
         */
        @Test
        void 활성_quota_대상은_HELD_와_PAYING_뿐이다() {
            // 3석을 잡는다. seatsA 의 나머지 1석은 손대지 않아 AVAILABLE 로 남는다.
            HoldId hold = holdInSaleEvent(USER, SCHEDULE_A, seatsA, 3);

            long sold = seatsA.get(0);
            long paying = seatsA.get(1);
            long held = seatsA.get(2);
            long available = seatsA.get(3);

            // AVAILABLE -> HELD -> PAYING -> SOLD (운영 경로)
            assertThat(paymentRepository.startPayment(new SeatId(sold), hold))
                    .isEqualTo(SeatPaymentOutcome.STARTED);
            assertThat(paymentRepository.confirm(
                    new SeatId(sold), hold, new ReservationId(5001L)))
                    .isEqualTo(SeatConfirmationOutcome.CONFIRMED);

            // AVAILABLE -> HELD -> PAYING (확정하지 않고 둔다)
            assertThat(paymentRepository.startPayment(new SeatId(paying), hold))
                    .isEqualTo(SeatPaymentOutcome.STARTED);

            assertThat(statusOf(sold)).as("확정된 좌석").isEqualTo("SOLD");
            assertThat(statusOf(paying)).as("결제 중인 좌석").isEqualTo("PAYING");
            assertThat(statusOf(held)).as("선점만 된 좌석").isEqualTo("HELD");
            assertThat(statusOf(available)).as("손대지 않은 좌석").isEqualTo("AVAILABLE");

            long activeSeats = jdbc().queryForObject("""
                    SELECT COUNT(*) FROM seat_inventory
                     WHERE held_by = ? AND status IN ('HELD', 'PAYING')
                    """, Long.class, USER.value());

            assertThat(activeSeats)
                    .as("★ HELD 1 + PAYING 1 = 2. SOLD 와 AVAILABLE 은 제외된다")
                    .isEqualTo(2);

            // SOLD 는 held_by 가 지워져 사용자 활성 좌석에서 구조적으로 빠진다.
            assertThat(heldByOf(sold)).as("확정 시 held_by 제거 (2B)").isNull();
            assertThat(heldByOf(available)).as("점유된 적 없음").isNull();

            // 카운터는 여전히 3 이다. 확정 경로의 감소가 아직 없기 때문이며,
            // 이 테스트는 그 격차를 '검증' 하지 않고 '기록' 만 한다.
            assertThat(quotaOf(USER, SALE_EVENT_CHUSEOK))
                    .as("확정 경로 quota 감소는 미구현 — 이 테스트의 주장 범위 밖이다")
                    .isEqualTo(3);
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("3. 매핑 계약과 잠금 키")
    class 매핑_계약 {

        /** resolver 단위 계약. 실제 흐름 검증은 아래 통합 테스트가 담당한다. */
        @Test
        void 매핑되지_않은_운행편은_resolver_에서_실패한다() {
            assertThatThrownBy(() -> scope.saleEventIdOf(SCHEDULE_UNMAPPED))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("판매 이벤트")
                    .hasMessageContaining(String.valueOf(SCHEDULE_UNMAPPED));
        }

        /**
         * ★ <b>실제 선점 흐름</b>에 미매핑 운행편을 넣어 부수 효과가 전혀 없음을 확인한다.
         *
         * <p>resolver 만 직접 부르면 "quota·좌석보다 먼저 실패한다" 는 통합 계약이 증명되지 않는다.
         * 여기서는 {@code holdInSaleEvent(...)} 를 그대로 통과시킨다.
         *
         * <p>매핑 조회가 {@code holdWithQuota(...)} 인자로 평가되므로 quota 행 생성도,
         * 좌석 UPDATE 도 시작되지 않는다.
         */
        @Test
        void 미매핑_운행편은_선점_흐름에서_부수_효과_없이_거부된다() {
            QuotaSnapshot before = quotaSnapshot();

            assertThatThrownBy(() -> holdInSaleEvent(USER, SCHEDULE_UNMAPPED, seatsUnmapped, 2))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(String.valueOf(SCHEDULE_UNMAPPED));

            assertThat(quotaSnapshot())
                    .as("★ quota 행 수와 합계가 그대로 — 새 행이 생기지도 않았다")
                    .isEqualTo(before);

            for (long seatId : seatsUnmapped) {
                assertThat(statusOf(seatId)).as("좌석 상태").isEqualTo("AVAILABLE");
                assertThat(holdIdOf(seatId)).as("hold_id").isNull();
                assertThat(heldByOf(seatId)).as("held_by").isNull();
                assertThat(expiresAtOf(seatId)).as("expires_at").isNull();
                assertThat(versionOf(seatId)).as("version — UPDATE 자체가 없었다").isZero();
            }
        }

        @Test
        void scheduleId_를_saleEventId_로_암묵_대입하지_않는다() {
            assertThat(scope.saleEventIdOf(SCHEDULE_A))
                    .as("운행편 식별자와 판매 이벤트 식별자는 다른 값이다")
                    .isNotEqualTo(SCHEDULE_A)
                    .isEqualTo(SALE_EVENT_CHUSEOK);
        }

        @Test
        void 하나의_운행편은_정확히_하나의_판매_이벤트에_속한다() {
            // 같은 이벤트로의 재배정은 멱등하다.
            scope.assign(SCHEDULE_A, SALE_EVENT_CHUSEOK);

            assertThatThrownBy(() -> scope.assign(SCHEDULE_A, SALE_EVENT_SEOLLAL))
                    .as("판매 시작 후 소속을 옮기면 기존 quota 의 의미가 달라진다")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("이미 판매 이벤트");
        }

        @Test
        void quota_키는_saleEventId_userId_오름차순으로_정렬된다() {
            List<Task2gSaleEventScope.QuotaKey> keys = new ArrayList<>(List.of(
                    new Task2gSaleEventScope.QuotaKey(SALE_EVENT_SEOLLAL, OTHER_USER.value()),
                    new Task2gSaleEventScope.QuotaKey(SALE_EVENT_CHUSEOK, OTHER_USER.value()),
                    new Task2gSaleEventScope.QuotaKey(SALE_EVENT_SEOLLAL, USER.value()),
                    new Task2gSaleEventScope.QuotaKey(SALE_EVENT_CHUSEOK, USER.value())));
            keys.sort(null);

            assertThat(keys).containsExactly(
                    new Task2gSaleEventScope.QuotaKey(SALE_EVENT_CHUSEOK, USER.value()),
                    new Task2gSaleEventScope.QuotaKey(SALE_EVENT_CHUSEOK, OTHER_USER.value()),
                    new Task2gSaleEventScope.QuotaKey(SALE_EVENT_SEOLLAL, USER.value()),
                    new Task2gSaleEventScope.QuotaKey(SALE_EVENT_SEOLLAL, OTHER_USER.value()));
        }

        @Test
        void 같은_판매_이벤트의_여러_운행편은_하나의_quota_키로_모인다() {
            assertThat(scope.quotaKeyOf(SCHEDULE_A, USER.value()))
                    .as("운행편이 달라도 같은 판매 이벤트면 같은 카운터 행")
                    .isEqualTo(scope.quotaKeyOf(SCHEDULE_B, USER.value()));
            assertThat(scope.quotaKeyOf(SCHEDULE_C, USER.value()))
                    .as("다른 판매 이벤트는 다른 행")
                    .isNotEqualTo(scope.quotaKeyOf(SCHEDULE_A, USER.value()));
        }
    }
}
