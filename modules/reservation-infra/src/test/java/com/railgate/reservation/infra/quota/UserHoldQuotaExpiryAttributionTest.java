package com.railgate.reservation.infra.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.ReservationId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.infra.MySqlTestSupport;
import com.railgate.reservation.infra.seat.ExpiryCandidate;
import com.railgate.reservation.infra.seat.JdbcMultiSeatHoldRepository;
import com.railgate.reservation.infra.seat.JdbcSeatExpiryRepository;
import com.railgate.reservation.infra.seat.JdbcSeatPaymentRepository;
import com.railgate.reservation.infra.seat.SeatConfirmationOutcome;
import com.railgate.reservation.infra.seat.SeatPaymentOutcome;
import com.railgate.reservation.seat.SeatId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ★ 만료 배치의 <b>사용자별 quota 귀속</b> 실험 — Task 2G-C.
 *
 * <h2>문제</h2>
 *
 * <p>{@link JdbcSeatExpiryRepository#expire} 는 <b>전체 {@code affected_rows} 하나</b>만 돌려준다.
 * 한 배치에 여러 사용자의 후보가 섞이고 그중 일부가 stale 하면,
 * <b>어느 사용자의 후보가 실제로 회수됐는지 알 수 없다.</b>
 *
 * <p>2G-A 는 이것을 지적만 했고, 2G-B 는 잠금 순서만 다뤘다. 이 Task 는
 * <b>같은 입력 요약과 같은 반환값이 서로 다른 정답을 갖는다</b>는 것을 테스트로 증명한다.
 *
 * <h2>§1 은 "통과하면 좋은" 테스트가 아니다</h2>
 *
 * <p>§1 이 통과한다는 것은 <b>귀속 불가능성을 관측했다</b>는 뜻이지 올바른 동작이라는 뜻이 아니다
 * (CLAUDE.md 규칙 27). 해결 후보 검증은 §2 다.
 *
 * <h2>범위</h2>
 *
 * <p>운영 코드를 한 줄도 바꾸지 않았다. 해결 후보는 <b>테스트 전용 coordinator</b> 이며
 * 기존 {@link JdbcSeatExpiryRepository#expire} 를 그대로 재사용한다.
 * quota scope 는 운영 계약으로 확정하지 않고 중립 식별자를 쓴다.
 */
@Timeout(180)
@DisplayName("만료 배치의 사용자별 quota 귀속 (Task 2G-C)")
class UserHoldQuotaExpiryAttributionTest extends MySqlTestSupport {

    private static final long SCHEDULE_ID = 1L;
    /** 실험용 중립 범위 키. event_id / schedule_id 결정은 여전히 보류다 (2G-A §9). */
    private static final long SCOPE_ID = 1L;
    private static final Duration HOLD_DURATION = Duration.ofMinutes(5);
    private static final Duration PAYMENT_DURATION = Duration.ofMinutes(5);

    private static final UserId USER_A = new UserId(11L);
    private static final UserId USER_B = new UserId(22L);

    private JdbcMultiSeatHoldRepository holdRepository;
    private JdbcSeatExpiryRepository expiryRepository;
    private JdbcSeatPaymentRepository paymentRepository;
    private Task2gQuotaCounter quota;
    private Task2gExpiryQuotaCoordinator coordinator;

    private HoldId holdA;
    private HoldId holdB;
    private List<Long> seatsOfA;
    private List<Long> seatsOfB;

    @BeforeEach
    void setUp() {
        Task2gQuotaCounter.createTable(jdbc());
        Task2gQuotaCounter.truncate(jdbc());

        holdRepository = new JdbcMultiSeatHoldRepository(
                dataSource(), transactionManager(), HOLD_DURATION);
        expiryRepository = new JdbcSeatExpiryRepository(dataSource());
        paymentRepository = new JdbcSeatPaymentRepository(dataSource(), PAYMENT_DURATION);
        quota = new Task2gQuotaCounter(dataSource());
        coordinator = new Task2gExpiryQuotaCoordinator(dataSource(), expiryRepository, quota);

        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            ids.add(insertAvailableSeat(SCHEDULE_ID, i + "A"));
        }
        seatsOfA = List.of(ids.get(0), ids.get(1), ids.get(2));   // 사용자 A 후보 3개
        seatsOfB = List.of(ids.get(3), ids.get(4));               // 사용자 B 후보 2개

        holdA = HoldId.newId();
        holdB = HoldId.newId();

        inTransaction(() -> {
            quota.ensureRow(USER_A.value(), SCOPE_ID);
            quota.ensureRow(USER_B.value(), SCOPE_ID);
            quota.tryAcquire(USER_A.value(), SCOPE_ID, 3);
            quota.tryAcquire(USER_B.value(), SCOPE_ID, 2);
            holdRepository.holdAll(toSeatIds(seatsOfA), holdA, USER_A);
            holdRepository.holdAll(toSeatIds(seatsOfB), holdB, USER_B);
        });

        // 다섯 좌석 모두 만료 후보가 되도록 만료 시각만 과거로 민다.
        // 좌석 상태·hold_id·held_by 는 건드리지 않는다 — 회수는 운영 저장소가 한다.
        expireAtPast(seatsOfA);
        expireAtPast(seatsOfB);
    }

    private static List<SeatId> toSeatIds(List<Long> ids) {
        return ids.stream().map(SeatId::new).toList();
    }

    private void expireAtPast(List<Long> ids) {
        for (long id : ids) {
            jdbc().update(
                    "UPDATE seat_inventory SET expires_at = NOW(3) - INTERVAL 1 MINUTE WHERE id = ?",
                    id);
        }
    }

    /**
     * 후보 하나를 stale 하게 만든다 — <b>운영 저장소의 실제 조건부 UPDATE</b>로.
     *
     * <p>{@code HELD → PAYING → SOLD} 를 거친다. 확정된 좌석은 만료 조건
     * {@code status IN ('HELD','PAYING')} 에 걸리지 않으므로 회수되지 않는다 (I-11).
     *
     * <p>{@code startPayment} 는 만료 시각을 미래로 밀므로, 후보 SELECT 에 잡히도록
     * 다시 과거로 되돌린다. 상태 전이 자체는 우회하지 않는다.
     */
    private void makeStaleByConfirm(long seatId, HoldId hold, long reservationId) {
        // startPayment 는 expires_at > NOW(3) 를 요구한다. 픽스처가 이미 과거로 밀어놨으므로
        // 잠시 미래로 되돌린 뒤 전이시키고, 다시 과거로 민다. 상태 전이는 우회하지 않는다.
        jdbc().update(
                "UPDATE seat_inventory SET expires_at = NOW(3) + INTERVAL 1 MINUTE WHERE id = ?",
                seatId);
        assertThat(paymentRepository.startPayment(new SeatId(seatId), hold))
                .as("HELD -> PAYING").isEqualTo(SeatPaymentOutcome.STARTED);
        expireAtPast(List.of(seatId));
        assertThat(paymentRepository.confirm(
                new SeatId(seatId), hold, new ReservationId(reservationId)))
                .as("PAYING -> SOLD").isEqualTo(SeatConfirmationOutcome.CONFIRMED);
    }

    /**
     * 후보 SELECT 이후의 경쟁 전이를 <b>독립 트랜잭션에서 커밋</b>한다.
     *
     * <p>스위퍼 정산 트랜잭션과 섞이면 안 된다. 같은 트랜잭션에 참여하면
     * seat 를 먼저 잠그고 quota 를 나중에 잠그는 역순이 되고, 정산이 실패할 때
     * 이 전이까지 함께 롤백되어 "다른 요청이 먼저 확정했다" 는 상황이 재현되지 않는다.
     *
     * <p>{@code sweepAndSettle} 이 <b>외부 트랜잭션을 거부</b>하므로 훅이 실행되는 시점에는
     * 활성 스위퍼 트랜잭션이 없다. 따라서 {@code newTransactionTemplate()} 이 기본
     * {@code PROPAGATION_REQUIRED} 로도 <b>새 최상위 트랜잭션</b>을 열고 반환 시점에 커밋한다.
     * {@code REQUIRES_NEW} 를 따로 붙일 필요가 없다.
     */
    private void confirmInSeparateTransaction(long seatId, HoldId hold, long reservationId) {
        newTransactionTemplate().executeWithoutResult(
                status -> makeStaleByConfirm(seatId, hold, reservationId));
    }

    /** 그 사용자의 후보 좌석 중 실제로 회수된(AVAILABLE 이 된) 수. */
    private long actuallyExpired(List<Long> candidateSeats) {
        return candidateSeats.stream()
                .filter(id -> "AVAILABLE".equals(statusOf(id)))
                .count();
    }

    private int quotaOf(UserId user) {
        return quota.heldSeats(user.value(), SCOPE_ID);
    }

    // ==================================================================

    @Nested
    @DisplayName("1. ★ RED — 전체 affected_rows 로는 사용자별 귀속이 불가능하다")
    class 귀속_불가능성 {

        /**
         * 스위퍼가 <b>실제로 볼 수 있는 것</b>만 담은 요약.
         *
         * <p>후보 시점의 사용자별 후보 수(= 후보에 {@code held_by} 를 넣었을 때 얻는 정보)와
         * {@code expire()} 의 전체 반환값이 전부다.
         */
        private record SweeperView(int candidatesForA, int candidatesForB, int totalAffected) { }

        /** 실제 정답 — DB 를 직접 들여다봐야만 알 수 있다. */
        private record Truth(long expiredForA, long expiredForB) { }

        private SweeperView view;
        private Truth truth;

        /** @param staleSeat 후보 SELECT 이후 확정시켜 stale 하게 만들 좌석 */
        private void 배치를_실행한다(long staleSeat, HoldId staleHold, long reservationId) {
            List<ExpiryCandidate> candidates = expiryRepository.findExpiredCandidates(10);
            assertThat(candidates).as("후보 5개로 시작한다").hasSize(5);

            // 후보에 held_by 를 넣었다면 얻었을 정보 (사용자별 후보 수)
            int forA = 3;
            int forB = 2;

            makeStaleByConfirm(staleSeat, staleHold, reservationId);

            int total = expiryRepository.expire(candidates);

            view = new SweeperView(forA, forB, total);
            truth = new Truth(actuallyExpired(seatsOfA), actuallyExpired(seatsOfB));
        }

        @Test
        void 같은_요약과_같은_반환값이_서로_다른_정답을_갖는다() {
            // --- 시나리오 1: A 의 후보 하나가 stale ---
            배치를_실행한다(seatsOfA.get(0), holdA, 1001L);
            SweeperView view1 = view;
            Truth truth1 = truth;

            // --- 같은 초기 조건으로 되돌리고 시나리오 2: B 의 후보 하나가 stale ---
            resetFixture();
            배치를_실행한다(seatsOfB.get(0), holdB, 2002L);
            SweeperView view2 = view;
            Truth truth2 = truth;

            System.out.println("[Task 2G-C] 시나리오1 view=" + view1 + " truth=" + truth1);
            System.out.println("[Task 2G-C] 시나리오2 view=" + view2 + " truth=" + truth2);

            assertThat(view1)
                    .as("★ 스위퍼가 볼 수 있는 것은 두 시나리오에서 완전히 같다")
                    .isEqualTo(view2);
            assertThat(view1.totalAffected()).as("전체 affected_rows").isEqualTo(4);

            assertThat(truth1).as("시나리오1 정답: A=2, B=2").isEqualTo(new Truth(2, 2));
            assertThat(truth2).as("시나리오2 정답: A=3, B=1").isEqualTo(new Truth(3, 1));
            assertThat(truth1)
                    .as("★ 그런데 정답은 서로 다르다 — 같은 관측에서 두 정답이 나올 수 없다")
                    .isNotEqualTo(truth2);
        }

        @Test
        void 후보에_held_by_를_넣어도_해결되지_않는다() {
            배치를_실행한다(seatsOfA.get(0), holdA, 1001L);
            SweeperView view1 = view;

            resetFixture();
            배치를_실행한다(seatsOfB.get(0), holdB, 2002L);
            SweeperView view2 = view;

            // candidatesForA / candidatesForB 가 바로 "후보에 held_by 가 있을 때 얻는 값" 이다.
            assertThat(view1.candidatesForA()).isEqualTo(view2.candidatesForA());
            assertThat(view1.candidatesForB()).isEqualTo(view2.candidatesForB());
            assertThat(view1.totalAffected()).isEqualTo(view2.totalAffected());

            assertThat(truth).as("정답은 시나리오2 쪽이다").isEqualTo(new Truth(3, 1));
        }

        @Test
        void 회수_후_재조회로도_귀속할_수_없다() {
            배치를_실행한다(seatsOfA.get(0), holdA, 1001L);

            // 만료 UPDATE 는 hold_id 와 held_by 를 지운다. 소유자 흔적이 남지 않는다.
            for (long id : seatsOfA.subList(1, 3)) {
                assertThat(statusOf(id)).isEqualTo("AVAILABLE");
                assertThat(holdIdOf(id)).as("hold_id 제거됨").isNull();
                assertThat(heldByOf(id)).as("★ held_by 도 제거됨 — 재조회로 소유자를 알 수 없다").isNull();
            }
        }

        /** 두 시나리오를 같은 초기 조건에서 비교하기 위해 픽스처를 다시 만든다. */
        private void resetFixture() {
            jdbc().update("DELETE FROM seat_inventory");
            Task2gQuotaCounter.truncate(jdbc());
            setUp();
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("2. GREEN — SELECT 이후 stale 이 나도 사용자별 실제 회수 수를 얻는다")
    class 그룹별_귀속 {

        /**
         * <b>quota 수치에 대한 제한.</b> {@code confirm} 경로의 quota 감소는 아직 운영에 없다.
         * 그래서 확정된 좌석 몫은 카운터에 남는다. 이 절이 검증하는 것은 오직
         * <b>만료 경로가 줄여야 할 사용자별 delta</b> 이며, 그것이 사용자별 실제
         * {@code affected_rows} 와 일치하는지다. 확정분에 해당하는 감소는 confirm 경로의 후속 과제다.
         *
         * <p>따라서 "최종 quota == 전체 활성 좌석 수" 를 단언하지 않는다. I-12 는 미완결이다.
         */
        @Test
        void SELECT_이후_A_하나가_stale_이면_감소량은_A2_B2_다() {
            Map<Task2gExpiryQuotaCoordinator.QuotaKey, Integer> result =
                    coordinator.sweepAndSettle(10, SCOPE_ID,
                            () -> confirmInSeparateTransaction(seatsOfA.get(0), holdA, 1001L));

            assertThat(coordinator.candidateCounts())
                    .as("후보 SELECT 시점에는 A=3, B=2 였다")
                    .containsEntry(key(USER_A), 3)
                    .containsEntry(key(USER_B), 2);

            assertThat(result.get(key(USER_A))).as("A 실제 회수").isEqualTo(2);
            assertThat(result.get(key(USER_B))).as("B 실제 회수").isEqualTo(2);
            assertThat(quotaOf(USER_A)).as("A quota 3 - 2 (만료 delta 만)").isEqualTo(1);
            assertThat(quotaOf(USER_B)).as("B quota 2 - 2").isZero();
        }

        @Test
        void SELECT_이후_B_하나가_stale_이면_감소량은_A3_B1_이다() {
            Map<Task2gExpiryQuotaCoordinator.QuotaKey, Integer> result =
                    coordinator.sweepAndSettle(10, SCOPE_ID,
                            () -> confirmInSeparateTransaction(seatsOfB.get(0), holdB, 2002L));

            assertThat(coordinator.candidateCounts())
                    .as("후보 수는 앞 테스트와 동일하다 — 달라지는 것은 stale 위치뿐")
                    .containsEntry(key(USER_A), 3)
                    .containsEntry(key(USER_B), 2);

            assertThat(result.get(key(USER_A))).as("A 실제 회수").isEqualTo(3);
            assertThat(result.get(key(USER_B))).as("B 실제 회수").isEqualTo(1);
            assertThat(quotaOf(USER_A)).as("A quota 3 - 3").isZero();
            assertThat(quotaOf(USER_B)).as("B quota 2 - 1 (만료 delta 만)").isEqualTo(1);
        }

        @Test
        void 같은_그룹의_나머지_후보는_회수된다() {
            coordinator.sweepAndSettle(10, SCOPE_ID,
                    () -> confirmInSeparateTransaction(seatsOfA.get(0), holdA, 1001L));

            assertThat(statusOf(seatsOfA.get(0))).as("stale 후보는 SOLD 유지").isEqualTo("SOLD");
            assertThat(statusOf(seatsOfA.get(1))).as("같은 그룹의 나머지").isEqualTo("AVAILABLE");
            assertThat(statusOf(seatsOfA.get(2))).isEqualTo("AVAILABLE");
        }

        @Test
        void 한_사용자의_stale_이_다른_사용자의_회수를_막지_않는다() {
            coordinator.sweepAndSettle(10, SCOPE_ID,
                    () -> confirmInSeparateTransaction(seatsOfA.get(0), holdA, 1001L));

            assertThat(actuallyExpired(seatsOfB)).as("B 는 온전히 회수된다").isEqualTo(2);
            assertThat(actuallyExpired(seatsOfA)).as("A 는 stale 하나를 제외하고 회수").isEqualTo(2);
        }

        @Test
        void A_후보_전체가_stale_이면_A_는_0_이고_감소_SQL_을_보내지_않는다() {
            Map<Task2gExpiryQuotaCoordinator.QuotaKey, Integer> result =
                    coordinator.sweepAndSettle(10, SCOPE_ID, () -> {
                        confirmInSeparateTransaction(seatsOfA.get(0), holdA, 1001L);
                        confirmInSeparateTransaction(seatsOfA.get(1), holdA, 1002L);
                        confirmInSeparateTransaction(seatsOfA.get(2), holdA, 1003L);
                    });

            assertThat(coordinator.candidateCounts())
                    .as("A 는 후보 3개로 배치에 포함됐다")
                    .containsEntry(key(USER_A), 3);
            assertThat(result.get(key(USER_A))).as("그런데 실제 회수는 0").isZero();
            assertThat(result.get(key(USER_B))).as("B 는 정상 회수").isEqualTo(2);
            assertThat(coordinator.quotaUpdateCalls())
                    .as("★ 0 인 사용자에게는 감소 SQL 을 보내지 않는다 (B 한 번뿐)")
                    .isEqualTo(1);
            assertThat(quotaOf(USER_A)).as("A quota 불변").isEqualTo(3);
            assertThat(quotaOf(USER_B)).as("B quota 2 - 2").isZero();
        }

        @Test
        void stale_사용자의_quota_를_과도하게_감소시키지_않는다() {
            coordinator.sweepAndSettle(10, SCOPE_ID,
                    () -> confirmInSeparateTransaction(seatsOfB.get(0), holdB, 2002L));

            // B 는 후보 2개였지만 실제 회수는 1개다. 후보 수(2)로 줄였다면 0 이 됐을 것이다.
            assertThat(quotaOf(USER_B))
                    .as("★ 후보 수가 아니라 실제 변경 행 수로 줄인다")
                    .isEqualTo(1);
        }

        @Test
        void 사용자별_감소량_합계는_실제_전체_회수_수와_같다() {
            Map<Task2gExpiryQuotaCoordinator.QuotaKey, Integer> result =
                    coordinator.sweepAndSettle(10, SCOPE_ID,
                            () -> confirmInSeparateTransaction(seatsOfA.get(0), holdA, 1001L));

            int sum = result.values().stream().mapToInt(Integer::intValue).sum();
            long actual = actuallyExpired(seatsOfA) + actuallyExpired(seatsOfB);

            assertThat((long) sum).as("합계 == 실제 회수 수").isEqualTo(actual);
        }

        @Test
        void 후보가_없는_사용자는_배치에_포함되지_않는다() {
            // SELECT 이전에 확정하면 A 는 후보 조회에 잡히지 않는다. 위 테스트들과 다른 계약이다.
            confirmInSeparateTransaction(seatsOfA.get(0), holdA, 1001L);
            confirmInSeparateTransaction(seatsOfA.get(1), holdA, 1002L);
            confirmInSeparateTransaction(seatsOfA.get(2), holdA, 1003L);

            Map<Task2gExpiryQuotaCoordinator.QuotaKey, Integer> result =
                    coordinator.sweepAndSettle(10, SCOPE_ID);

            assertThat(coordinator.candidateCounts()).doesNotContainKey(key(USER_A));
            assertThat(result).as("A 는 그룹 자체가 없다").doesNotContainKey(key(USER_A));
            assertThat(coordinator.lockOrder())
                    .as("후보가 없으면 quota 행도 잠그지 않는다")
                    .containsExactly(key(USER_B));
            assertThat(quotaOf(USER_A)).as("A quota 불변").isEqualTo(3);
        }

        @Test
        void quota_잠금은_scopeId_userId_오름차순이다() {
            coordinator.sweepAndSettle(10, SCOPE_ID);

            assertThat(coordinator.lockOrder())
                    .as("결정적 잠금 순서 — 배치마다 달라지면 스위퍼끼리 교착할 수 있다")
                    .containsExactly(key(USER_A), key(USER_B));
        }

        @Test
        void 각_사용자_그룹의_좌석은_seatId_오름차순으로_처리된다() {
            coordinator.sweepAndSettle(10, SCOPE_ID);

            assertThat(coordinator.seatOrderOf(key(USER_A)))
                    .as("규칙 2 — 그룹 안에서도 id 오름차순")
                    .isSorted();
            assertThat(coordinator.seatOrderOf(key(USER_B))).isSorted();
        }
    }

    @Nested
    @DisplayName("3. 트랜잭션 경계와 실패 계약")
    class 트랜잭션_경계 {

        @Test
        void quota_감소가_거부되면_좌석_만료도_롤백된다() {
            // A 의 카운터를 실제보다 작게 만들어(드리프트) 감소가 음수 방어에 걸리게 한다.
            jdbc().update("UPDATE " + Task2gQuotaCounter.TABLE
                    + " SET held_seats = 1 WHERE user_id = ? AND quota_scope_id = ?",
                    USER_A.value(), SCOPE_ID);

            assertThatThrownBy(() -> coordinator.sweepAndSettle(10, SCOPE_ID))
                    .as("1 - 3 < 0 이라 감소가 거부된다")
                    .isInstanceOf(IllegalStateException.class);

            assertThat(actuallyExpired(seatsOfA)).as("★ A 좌석 회수도 롤백").isZero();
            assertThat(actuallyExpired(seatsOfB)).as("★ 같은 트랜잭션이므로 B 도 롤백").isZero();
            assertThat(quotaOf(USER_A)).isEqualTo(1);
            assertThat(quotaOf(USER_B)).isEqualTo(2);
        }

        @Test
        void 외부_트랜잭션이_롤백되면_좌석과_quota_가_함께_복구된다() {
            TransactionTemplate tx = newTransactionTemplate();
            tx.executeWithoutResult(status -> {
                coordinator.settleWithin(10, SCOPE_ID);
                status.setRollbackOnly();
            });

            assertThat(actuallyExpired(seatsOfA)).isZero();
            assertThat(actuallyExpired(seatsOfB)).isZero();
            assertThat(quotaOf(USER_A)).isEqualTo(3);
            assertThat(quotaOf(USER_B)).isEqualTo(2);
        }

        /**
         * {@code settleWithin} 은 <b>호출자가 연 트랜잭션에 참여하는</b> API 다.
         *
         * <p>트랜잭션 없이 부르면 {@code SELECT ... FOR UPDATE} 로 잡은 quota 잠금이
         * 문장이 끝나는 순간 풀리고, 좌석 회수와 quota 감소가 <b>각각 autocommit</b> 된다.
         * 그 사이에 다른 트랜잭션이 끼어들 수 있고 실패해도 되돌릴 수 없다.
         *
         * <p>이 전제가 지금까지 <b>메서드 이름과 문서에만</b> 있었다. 런타임으로 강제한다.
         */
        @Test
        void settleWithin_은_활성_트랜잭션이_없으면_거부한다() {
            assertThatThrownBy(() -> coordinator.settleWithin(10, SCOPE_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("활성 트랜잭션");

            // quota 잠금 이전, 어떤 SQL 보다 먼저 실패해야 한다.
            assertThat(coordinator.lockOrder()).as("quota 를 잠그지 않았다").isEmpty();
            assertThat(actuallyExpired(seatsOfA)).as("좌석 불변").isZero();
            assertThat(actuallyExpired(seatsOfB)).isZero();
            assertThat(quotaOf(USER_A)).as("quota 불변").isEqualTo(3);
            assertThat(quotaOf(USER_B)).isEqualTo(2);
        }

        /**
         * {@code sweepAndSettle} 은 <b>자체 정산 트랜잭션을 여는</b> API 다.
         *
         * <p>{@link TransactionTemplate} 의 기본 전파 속성은 {@code PROPAGATION_REQUIRED} 이므로
         * 외부 트랜잭션 안에서 부르면 <b>조용히 그 트랜잭션에 참여</b>한다. 그러면
         * 경쟁 전이 훅도 같은 트랜잭션에 묶여 이 클래스가 분리해 둔 세 구간 경계가 무너진다.
         *
         * <p>{@code REQUIRES_NEW} 로 억지로 떼어내지 않고 <b>잘못된 호출 자체를 거부</b>한다.
         */
        @Test
        void sweepAndSettle_은_외부_트랜잭션_안에서_호출할_수_없다() {
            AtomicBoolean callbackRan = new AtomicBoolean(false);
            TransactionTemplate outer = newTransactionTemplate();

            assertThatThrownBy(() -> outer.executeWithoutResult(status ->
                    coordinator.sweepAndSettle(10, SCOPE_ID, () -> callbackRan.set(true))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("외부 트랜잭션")
                    .hasMessageContaining("settleWithin");

            assertThat(callbackRan).as("★ 후보 조회도 콜백도 실행되지 않았다").isFalse();
            assertThat(coordinator.lockOrder()).isEmpty();
            assertThat(actuallyExpired(seatsOfA)).as("좌석 불변").isZero();
            assertThat(actuallyExpired(seatsOfB)).isZero();
            assertThat(quotaOf(USER_A)).as("quota 불변").isEqualTo(3);
            assertThat(quotaOf(USER_B)).isEqualTo(2);
        }

        /**
         * ★ 경쟁 요청의 결과가 스위퍼 롤백에 끌려 들어가지 않는다.
         *
         * <p>후보 SELECT 이후 별도 트랜잭션에서 확정된 좌석은 <b>이미 커밋됐다.</b>
         * 그 뒤 정산 트랜잭션이 quota 감소 거부로 실패해도 그 확정은 그대로 남아야 한다.
         * 훅이 정산 트랜잭션 안에서 돌면 이 성질이 깨진다 — 그것이 이 테스트의 대상이다.
         */
        @Test
        void 스위퍼_롤백은_독립_커밋된_confirm_을_되돌리지_않는다() {
            // A 의 카운터를 실제보다 작게 만들어 정산이 반드시 실패하게 한다.
            jdbc().update("UPDATE " + Task2gQuotaCounter.TABLE
                    + " SET held_seats = 1 WHERE user_id = ? AND quota_scope_id = ?",
                    USER_A.value(), SCOPE_ID);

            assertThatThrownBy(() -> coordinator.sweepAndSettle(10, SCOPE_ID,
                    () -> confirmInSeparateTransaction(seatsOfA.get(0), holdA, 1001L)))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(statusOf(seatsOfA.get(0)))
                    .as("★ 독립 커밋된 confirm 은 스위퍼 롤백에 끌려가지 않는다")
                    .isEqualTo("SOLD");
            assertThat(statusOf(seatsOfA.get(1))).as("스위퍼의 회수는 롤백").isEqualTo("HELD");
            assertThat(statusOf(seatsOfA.get(2))).isEqualTo("HELD");
            assertThat(actuallyExpired(seatsOfB)).as("같은 트랜잭션이므로 B 도 롤백").isZero();
            assertThat(quotaOf(USER_A)).as("quota 도 롤백").isEqualTo(1);
            assertThat(quotaOf(USER_B)).isEqualTo(2);
        }

        @Test
        void quota_행이_없으면_조용히_무시하지_않고_실패한다() {
            jdbc().update("DELETE FROM " + Task2gQuotaCounter.TABLE + " WHERE user_id = ?",
                    USER_B.value());

            assertThatThrownBy(() -> coordinator.sweepAndSettle(10, SCOPE_ID))
                    .as("★ 누락된 quota 행은 drift 신호다. 조용히 넘기면 카운터가 영구히 어긋난다")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("quota 행이 없다");

            assertThat(actuallyExpired(seatsOfA)).as("전체 롤백").isZero();
            assertThat(actuallyExpired(seatsOfB)).isZero();
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("4. held_by 가 NULL 인 만료 후보 — drift 탐지")
    class held_by_누락 {

        /**
         * {@code status IN ('HELD','PAYING')} 과 {@code held_by} 의 결합을 강제하는 DB CHECK 가 없다.
         * 그래서 소유자가 없는 만료 후보가 생길 가능성을 <b>구조적으로 배제할 수 없다.</b>
         *
         * <p>후보 SQL 에 {@code held_by IS NOT NULL} 을 붙이면 그런 행은 조용히 제외되고
         * 영원히 회수되지 않으면서 아무도 그 사실을 모른다. 그래서 <b>탐지 대상</b>으로 둔다.
         *
         * <p>비정상 행은 테스트 fixture 로 만든다. 운영 마이그레이션이나 도메인 불변식은 바꾸지 않는다.
         */
        @BeforeEach
        void 소유자가_없는_만료_후보를_만든다() {
            jdbc().update("UPDATE seat_inventory SET held_by = NULL WHERE id = ?",
                    seatsOfA.get(0));
        }

        @Test
        void 조용히_제외하지_않고_명시적으로_실패한다() {
            assertThatThrownBy(() -> coordinator.sweepAndSettle(10, SCOPE_ID))
                    .isInstanceOf(Task2gExpiryQuotaCoordinator.QuotaDriftException.class)
                    .hasMessageContaining("held_by 가 NULL")
                    .hasMessageContaining(String.valueOf(seatsOfA.get(0)));
        }

        @Test
        void 해당_좌석은_회수되지_않는다() {
            assertThatThrownBy(() -> coordinator.sweepAndSettle(10, SCOPE_ID))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(statusOf(seatsOfA.get(0)))
                    .as("소유자 미상 좌석은 회수하지 않는다")
                    .isEqualTo("HELD");
        }

        @Test
        void quota_는_변경되지_않는다() {
            assertThatThrownBy(() -> coordinator.sweepAndSettle(10, SCOPE_ID))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(quotaOf(USER_A)).isEqualTo(3);
            assertThat(quotaOf(USER_B)).isEqualTo(2);
        }

        @Test
        void 다른_사용자의_좌석도_정산_트랜잭션_시작_전에_그대로_유지된다() {
            assertThatThrownBy(() -> coordinator.sweepAndSettle(10, SCOPE_ID))
                    .isInstanceOf(IllegalStateException.class);

            // 후보 탐색 단계에서 터지므로 정산 트랜잭션 자체가 시작되지 않는다.
            assertThat(actuallyExpired(seatsOfA)).isZero();
            assertThat(actuallyExpired(seatsOfB)).isZero();
            assertThat(coordinator.lockOrder()).as("quota 행을 잠그지도 않았다").isEmpty();
        }
    }

    private static Task2gExpiryQuotaCoordinator.QuotaKey key(UserId user) {
        return new Task2gExpiryQuotaCoordinator.QuotaKey(SCOPE_ID, user.value());
    }
}
