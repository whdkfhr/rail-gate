package com.railgate.reservation.application.expiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.expiry.ExpiredSeat;
import com.railgate.reservation.expiry.ExpiryCandidate;
import com.railgate.reservation.expiry.ExpiryCursor;
import com.railgate.reservation.expiry.SeatExpiryPort;
import com.railgate.reservation.quota.HoldAttribution;
import com.railgate.reservation.quota.QuotaAcquireOutcome;
import com.railgate.reservation.quota.QuotaReleaseOutcome;
import com.railgate.reservation.quota.UserHoldQuotaPort;
import com.railgate.reservation.saleevent.SaleEventId;
import com.railgate.reservation.seat.SeatId;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.sql.SQLTransactionRollbackException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * 만료 배치의 <b>순서·감소량·실패 분류·재시도 횟수</b>를 DB 없이 고정한다.
 *
 * <p>트랜잭션 관리자는 실제 Spring {@link AbstractPlatformTransactionManager} 를 상속한
 * 기록 스텁이다 — begin/commit/rollback 이 실제 순서로 호출되고, 스텁 포트는 "트랜잭션 안에서
 * 불렸는가" 를 기록한다. 실제 DB 원자성은 {@code ExpireHoldsServiceIntegrationTest} 가 검증한다.
 */
@DisplayName("ExpireHoldsService — 만료 배치")
class ExpireHoldsServiceTest {

    private static final SaleEventId CHUSEOK = new SaleEventId(9001L);
    private static final SaleEventId SEOLLAL = new SaleEventId(9002L);
    private static final UserId USER_A = new UserId(1L);
    private static final UserId USER_B = new UserId(2L);

    private RecordingExpiry expiry;
    private RecordingQuota quota;
    private RecordingTransactionManager transactions;
    private List<Integer> backoffs;
    private List<String> calls;
    private ExpireHoldsService service;

    @BeforeEach
    void setUp() {
        calls = new ArrayList<>();
        expiry = new RecordingExpiry(calls);
        quota = new RecordingQuota(calls);
        transactions = new RecordingTransactionManager(calls);
        backoffs = new ArrayList<>();
        service = new ExpireHoldsService(expiry, quota, transactions, attempt -> {
            calls.add("backoff(" + attempt + ")");
            backoffs.add(attempt);
            transactions.activeDuringBackoff.add(transactions.isActive());
        });
    }

    /** 정렬 키는 좌석 id 순으로 단조 증가시킨다 — 커서 전진을 검증할 수 있게. */
    private static LocalDateTime expiresAtFor(long id) {
        return LocalDateTime.of(2026, 1, 1, 0, 0).plusSeconds(id);
    }

    private static ExpiredSeat seat(long id, HoldId hold, UserId user, SaleEventId event) {
        return new ExpiredSeat(new SeatId(id), Optional.of(hold), Optional.of(user), event, expiresAtFor(id));
    }

    private static ExpiredSeat corrupt(long id, HoldId hold, UserId user, SaleEventId event) {
        return new ExpiredSeat(new SeatId(id), Optional.ofNullable(hold), Optional.ofNullable(user),
                event, expiresAtFor(id));
    }

    @Nested
    @DisplayName("§1 ★ 순서와 감소량")
    class 순서와_감소량 {

        @Test
        void 그룹마다_TX_열고_quota_잠금_다음_회수_다음_실제_수만큼_감소_다음_커밋() {
            HoldId h = HoldId.newId();
            expiry.candidates = List.of(seat(10, h, USER_A, CHUSEOK), seat(11, h, USER_A, CHUSEOK));
            expiry.affectedByGroup.put(key(CHUSEOK, USER_A), 2);

            ExpirySweepResult result = service.sweep(100);

            assertThat(calls).containsExactly(
                    "expiry.find(100)",
                    "tx.begin",
                    "quota.lockRow(9001,1)",
                    "expiry.expire([10, 11])",
                    "quota.release(9001,1,2)",
                    "tx.commit");
            assertThat(result.reclaimed()).isEqualTo(2);
            assertThat(result.succeeded()).containsEntry(key(CHUSEOK, USER_A), 2);
            assertThat(result.totalAttempts()).isEqualTo(1);
        }

        /** ★ 감소량은 후보 수가 아니라 실제 affected_rows 다. */
        @Test
        void 부분_stale_이면_실제_회수_수만큼만_줄인다() {
            HoldId h = HoldId.newId();
            expiry.candidates = List.of(seat(10, h, USER_A, CHUSEOK), seat(11, h, USER_A, CHUSEOK), seat(12, h, USER_A, CHUSEOK));
            expiry.affectedByGroup.put(key(CHUSEOK, USER_A), 2);

            ExpirySweepResult result = service.sweep(100);

            assertThat(calls).contains("quota.release(9001,1,2)");
            assertThat(result.reclaimed()).isEqualTo(2);
            assertThat(result.staleCandidates()).isEqualTo(1);
            assertThat(result.failed()).isEmpty();
        }

        /** ★ 실제 회수 0 이면 release(0) 을 부르지 않는다. */
        @Test
        void 전부_stale_이면_감소를_호출하지_않고_성공_그룹으로_센다() {
            HoldId h = HoldId.newId();
            expiry.candidates = List.of(seat(10, h, USER_A, CHUSEOK));
            expiry.affectedByGroup.put(key(CHUSEOK, USER_A), 0);

            ExpirySweepResult result = service.sweep(100);

            assertThat(calls).noneMatch(c -> c.startsWith("quota.release"));
            assertThat(calls).contains("tx.commit");
            assertThat(result.succeeded()).containsEntry(key(CHUSEOK, USER_A), 0);
            assertThat(result.staleCandidates()).isEqualTo(1);
        }

        /** 그룹은 (회차, 사용자) 키 오름차순이며 그룹 안 좌석은 PK 오름차순이다. */
        @Test
        void 사용자_회차별로_묶고_키_오름차순으로_처리한다() {
            HoldId h1 = HoldId.newId(), h2 = HoldId.newId(), h3 = HoldId.newId();
            expiry.candidates = List.of(
                    seat(30, h3, USER_B, SEOLLAL),
                    seat(21, h2, USER_B, CHUSEOK),
                    seat(12, h1, USER_A, CHUSEOK),
                    seat(11, h1, USER_A, CHUSEOK));
            expiry.affectedByGroup.put(key(CHUSEOK, USER_A), 2);
            expiry.affectedByGroup.put(key(CHUSEOK, USER_B), 1);
            expiry.affectedByGroup.put(key(SEOLLAL, USER_B), 1);

            ExpirySweepResult result = service.sweep(100);

            assertThat(calls).filteredOn(c -> c.startsWith("quota.lockRow")).containsExactly(
                    "quota.lockRow(9001,1)", "quota.lockRow(9001,2)", "quota.lockRow(9002,2)");
            assertThat(calls).filteredOn(c -> c.startsWith("expiry.expire")).containsExactly(
                    "expiry.expire([11, 12])", "expiry.expire([21])", "expiry.expire([30])");
            assertThat(calls).filteredOn(c -> c.startsWith("tx.")).hasSize(6);
            assertThat(result.reclaimed()).isEqualTo(4);
        }

        @Test
        void 후보가_없으면_트랜잭션을_열지_않는다() {
            expiry.candidates = List.of();

            ExpirySweepResult result = service.sweep(100);

            assertThat(calls).containsExactly("expiry.find(100)");
            assertThat(result.candidatesFound()).isZero();
            assertThat(result.hasFailures()).isFalse();
        }
    }

    @Nested
    @DisplayName("§2 ★ 실패 격리와 손상 후보")
    class 실패_격리 {

        /** ★ quota 행 부재는 좌석 UPDATE 전에 그 그룹만 실패시킨다 (2G-F 계약 3). */
        @Test
        void quota_행이_없으면_좌석_UPDATE_전에_그_그룹만_실패한다() {
            HoldId hA = HoldId.newId(), hB = HoldId.newId();
            expiry.candidates = List.of(seat(10, hA, USER_A, CHUSEOK), seat(20, hB, USER_B, CHUSEOK));
            expiry.affectedByGroup.put(key(CHUSEOK, USER_A), 1);
            quota.missingRows.add(key(CHUSEOK, USER_B));

            ExpirySweepResult result = service.sweep(100);

            assertThat(calls).doesNotContain("expiry.expire([20])");
            assertThat(calls).contains("expiry.expire([10])", "quota.release(9001,1,1)");
            assertThat(result.succeeded()).containsOnlyKeys(key(CHUSEOK, USER_A));
            assertThat(result.failed()).singleElement().satisfies(f -> {
                assertThat(f.key()).isEqualTo(key(CHUSEOK, USER_B));
                assertThat(f.kind()).isEqualTo(GroupFailureKind.QUOTA_ROW_MISSING);
                assertThat(f.attempts()).as("업무 실패는 1회").isEqualTo(1);
            });
            assertThat(backoffs).as("업무 실패는 백오프도 없다").isEmpty();
            assertThat(calls).filteredOn(c -> c.startsWith("tx.")).containsExactly(
                    "tx.begin", "tx.commit", "tx.begin", "tx.rollback");
        }

        @Test
        void 감소_거절은_그_그룹을_롤백하고_재시도하지_않는다() {
            HoldId h = HoldId.newId();
            expiry.candidates = List.of(seat(10, h, USER_A, CHUSEOK));
            expiry.affectedByGroup.put(key(CHUSEOK, USER_A), 1);
            quota.rejectRelease.add(key(CHUSEOK, USER_A));

            ExpirySweepResult result = service.sweep(100);

            assertThat(calls).containsSubsequence("expiry.expire([10])", "quota.release(9001,1,1)", "tx.rollback");
            assertThat(result.failed()).singleElement()
                    .satisfies(f -> assertThat(f.kind()).isEqualTo(GroupFailureKind.QUOTA_DECREASE_REJECTED));
            assertThat(result.reclaimed()).as("롤백된 회수는 세지 않는다").isZero();
            assertThat(backoffs).isEmpty();
        }

        /** ★ 손상 후보는 회수하지 않고 보고한다. 정상 그룹은 계속 처리한다. */
        @Test
        void 손상_후보는_회수하지_않고_보고하며_정상_그룹은_계속한다() {
            HoldId h = HoldId.newId();
            expiry.candidates = List.of(
                    corrupt(10, null, USER_A, CHUSEOK),
                    corrupt(11, h, null, CHUSEOK),
                    seat(12, h, USER_A, CHUSEOK));
            expiry.affectedByGroup.put(key(CHUSEOK, USER_A), 1);

            ExpirySweepResult result = service.sweep(100);

            assertThat(result.corrupt()).extracting(c -> c.seatId().value()).containsExactly(10L, 11L);
            assertThat(result.corrupt().get(0).reason()).contains("hold_id");
            assertThat(result.corrupt().get(1).reason()).contains("held_by");
            assertThat(calls).contains("expiry.expire([12])");
            assertThat(calls).noneMatch(c -> c.contains("lockRow(9001,0)"));
            assertThat(result.reclaimed()).isEqualTo(1);
            assertThat(result.candidatesFound()).isEqualTo(3);
        }

        @Test
        void 알_수_없는_오류는_재시도하지_않고_UNKNOWN_으로_분류한다() {
            HoldId h = HoldId.newId();
            expiry.candidates = List.of(seat(10, h, USER_A, CHUSEOK));
            expiry.failures.put(key(CHUSEOK, USER_A), List.of(new IllegalStateException("알 수 없음")));

            ExpirySweepResult result = service.sweep(100);

            assertThat(result.failed()).singleElement().satisfies(f -> {
                assertThat(f.kind()).isEqualTo(GroupFailureKind.UNKNOWN);
                assertThat(f.attempts()).isEqualTo(1);
            });
            assertThat(backoffs).isEmpty();
        }
    }

    @Nested
    @DisplayName("§3 ★ 재시도")
    class 재시도 {

        private RuntimeException deadlock() {
            return new PessimisticLockingFailureException("savepoint 1305 로 덮임",
                    new SQLException("Deadlock found", "40001", 1213));
        }

        private RuntimeException lockWaitTimeout() {
            return new PessimisticLockingFailureException("lock wait",
                    new SQLException("Lock wait timeout exceeded", "HY000", 1205));
        }

        @Test
        void 데드락은_새_트랜잭션으로_재시도하고_두_번째에_성공하면_실패_그룹이_아니다() {
            HoldId h = HoldId.newId();
            expiry.candidates = List.of(seat(10, h, USER_A, CHUSEOK));
            expiry.affectedByGroup.put(key(CHUSEOK, USER_A), 1);
            expiry.failures.put(key(CHUSEOK, USER_A), new ArrayList<>(List.of(deadlock())));

            ExpirySweepResult result = service.sweep(100);

            assertThat(calls).filteredOn(c -> c.startsWith("tx.") || c.startsWith("backoff"))
                    .containsExactly("tx.begin", "tx.rollback", "backoff(1)", "tx.begin", "tx.commit");
            assertThat(transactions.activeDuringBackoff).as("★ 백오프는 트랜잭션 밖").containsExactly(false);
            assertThat(result.failed()).isEmpty();
            assertThat(result.succeeded()).containsEntry(key(CHUSEOK, USER_A), 1);
            assertThat(result.totalAttempts()).isEqualTo(2);
        }

        @Test
        void 세_번_모두_실패하면_시도_3회_백오프_2회로_끝나고_네_번째는_없다() {
            HoldId h = HoldId.newId();
            expiry.candidates = List.of(seat(10, h, USER_A, CHUSEOK));
            expiry.failures.put(key(CHUSEOK, USER_A),
                    new ArrayList<>(List.of(lockWaitTimeout(), lockWaitTimeout(), lockWaitTimeout(), lockWaitTimeout())));

            ExpirySweepResult result = service.sweep(100);

            assertThat(backoffs).containsExactly(1, 2);
            assertThat(calls).filteredOn(c -> c.equals("tx.begin")).hasSize(3);
            assertThat(result.failed()).singleElement().satisfies(f -> {
                assertThat(f.kind()).isEqualTo(GroupFailureKind.LOCK_WAIT_TIMEOUT);
                assertThat(f.attempts()).isEqualTo(3);
            });
            assertThat(result.totalAttempts()).isEqualTo(3);
        }

        /** ★ 성공한 그룹은 다른 그룹의 재시도에서 다시 실행되지 않는다. */
        @Test
        void 성공한_그룹은_다시_실행하지_않는다() {
            HoldId hA = HoldId.newId(), hB = HoldId.newId();
            expiry.candidates = List.of(seat(10, hA, USER_A, CHUSEOK), seat(20, hB, USER_B, CHUSEOK));
            expiry.affectedByGroup.put(key(CHUSEOK, USER_A), 1);
            expiry.affectedByGroup.put(key(CHUSEOK, USER_B), 1);
            expiry.failures.put(key(CHUSEOK, USER_B), new ArrayList<>(List.of(deadlock())));

            ExpirySweepResult result = service.sweep(100);

            assertThat(calls).filteredOn(c -> c.equals("expiry.expire([10])")).hasSize(1);
            assertThat(calls).filteredOn(c -> c.equals("expiry.expire([20])")).hasSize(2);
            assertThat(result.succeeded()).containsKeys(key(CHUSEOK, USER_A), key(CHUSEOK, USER_B));
            assertThat(result.totalAttempts()).isEqualTo(3);
        }

        @Test
        void vendor_code_없는_transient_rollback_과_generic_lock_failure_는_별도_분류로_재시도한다() {
            HoldId hA = HoldId.newId(), hB = HoldId.newId();
            expiry.candidates = List.of(seat(10, hA, USER_A, CHUSEOK), seat(20, hB, USER_B, CHUSEOK));
            expiry.failures.put(key(CHUSEOK, USER_A), new ArrayList<>(List.of(
                    new RuntimeException(new SQLTransactionRollbackException("rolled back")),
                    new RuntimeException(new SQLTransactionRollbackException("rolled back")),
                    new RuntimeException(new SQLTransactionRollbackException("rolled back")))));
            expiry.failures.put(key(CHUSEOK, USER_B), new ArrayList<>(List.of(
                    new PessimisticLockingFailureException("no code"),
                    new PessimisticLockingFailureException("no code"),
                    new PessimisticLockingFailureException("no code"))));

            ExpirySweepResult result = service.sweep(100);

            assertThat(result.failed()).extracting(ExpirySweepResult.GroupFailure::kind)
                    .containsExactly(GroupFailureKind.TRANSIENT_TRANSACTION_ROLLBACK,
                            GroupFailureKind.TRANSIENT_LOCK_FAILURE);
            assertThat(result.failed()).allSatisfy(f -> assertThat(f.attempts()).isEqualTo(3));
        }

        /** ★ 인터럽트를 삼키지 않는다 — 플래그를 복원하고 배치를 중단한다. */
        @Test
        void 백오프_중_인터럽트는_플래그를_복원하고_전파한다() {
            HoldId h = HoldId.newId();
            expiry.candidates = List.of(seat(10, h, USER_A, CHUSEOK));
            expiry.failures.put(key(CHUSEOK, USER_A), new ArrayList<>(List.of(deadlock())));
            ExpireHoldsService interrupting = new ExpireHoldsService(expiry, quota, transactions,
                    attempt -> { throw new InterruptedException("중단"); });

            try {
                assertThatThrownBy(() -> interrupting.sweep(100))
                        .isInstanceOf(IllegalStateException.class)
                        .hasCauseInstanceOf(InterruptedException.class);
                assertThat(Thread.currentThread().isInterrupted()).as("★ 플래그 복원").isTrue();
            } finally {
                Thread.interrupted();   // 다른 테스트에 새지 않게 지운다
            }
        }
    }

    @Nested
    @DisplayName("§4 입력 검증")
    class 입력_검증 {

        @Test
        void batchSize_가_0_이하면_거부한다() {
            assertThatThrownBy(() -> service.sweep(0)).isInstanceOf(IllegalArgumentException.class);
            assertThat(calls).isEmpty();
        }
    }

    private static QuotaGroupKey key(SaleEventId event, UserId user) {
        return new QuotaGroupKey(event, user);
    }

    // ------------------------------------------------------------------ 스텁

    private static final class RecordingExpiry implements SeatExpiryPort {
        private final List<String> calls;
        private List<ExpiredSeat> candidates = List.of();
        private final Map<QuotaGroupKey, Integer> affectedByGroup = new HashMap<>();
        /** 그룹별로 expire 호출 시 순서대로 던질 예외. 소진되면 정상 동작. */
        private final Map<QuotaGroupKey, List<RuntimeException>> failures = new HashMap<>();
        private final Map<List<Long>, QuotaGroupKey> keyBySeats = new HashMap<>();

        private RecordingExpiry(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public List<ExpiredSeat> findExpiredSeats(int pageSize, Optional<ExpiryCursor> after) {
            calls.add("expiry.find(" + pageSize + after.map(c -> ", after " + c.seatId()).orElse("") + ")");
            for (ExpiredSeat s : candidates) {
                if (s.isSettleable()) {
                    keyBySeats.computeIfAbsent(List.of(s.seatId().value()),
                            k -> new QuotaGroupKey(s.saleEventId(), s.heldBy().orElseThrow()));
                }
            }
            // 커서 뒤부터 pageSize 만큼. 실제 저장소의 키셋 의미론을 그대로 흉내 낸다.
            return candidates.stream()
                    .filter(s -> after.map(c -> s.expiresAt().isAfter(c.expiresAt())
                            || (s.expiresAt().equals(c.expiresAt()) && s.seatId().value() > c.seatId()))
                            .orElse(true))
                    .limit(pageSize)
                    .toList();
        }

        @Override
        public int expire(List<ExpiryCandidate> group) {
            List<Long> ids = group.stream().map(c -> c.seatId().value()).toList();
            calls.add("expiry.expire(" + ids + ")");
            QuotaGroupKey key = keyBySeats.get(List.of(ids.get(0)));
            List<RuntimeException> queued = failures.get(key);
            if (queued != null && !queued.isEmpty()) {
                throw queued.remove(0);
            }
            return affectedByGroup.getOrDefault(key, 0);
        }
    }

    private static final class RecordingQuota implements UserHoldQuotaPort {
        private final List<String> calls;
        private final List<QuotaGroupKey> missingRows = new ArrayList<>();
        private final List<QuotaGroupKey> rejectRelease = new ArrayList<>();

        private RecordingQuota(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public void ensureRow(SaleEventId saleEventId, UserId userId) {
            throw new AssertionError("★ 만료 경로는 행을 만들지 않는다");
        }

        @Override
        public QuotaAcquireOutcome tryAcquire(SaleEventId saleEventId, UserId userId, int seats) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Integer> lockRow(SaleEventId saleEventId, UserId userId) {
            calls.add("quota.lockRow(%d,%d)".formatted(saleEventId.value(), userId.value()));
            return missingRows.contains(new QuotaGroupKey(saleEventId, userId)) ? Optional.empty() : Optional.of(4);
        }

        @Override
        public QuotaReleaseOutcome release(SaleEventId saleEventId, UserId userId, int seats) {
            calls.add("quota.release(%d,%d,%d)".formatted(saleEventId.value(), userId.value(), seats));
            return rejectRelease.contains(new QuotaGroupKey(saleEventId, userId))
                    ? QuotaReleaseOutcome.REJECTED : QuotaReleaseOutcome.RELEASED;
        }
    }

    /**
     * begin/commit/rollback 을 기록하는 실제 {@link AbstractPlatformTransactionManager} 구현.
     * 리소스는 없지만 상태 전이와 "트랜잭션 활성 여부" 는 Spring 이 실제로 관리한다.
     */
    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager
            implements PlatformTransactionManager {
        private static final long serialVersionUID = 1L;
        private final transient List<String> calls;
        private final transient List<Boolean> activeDuringBackoff = new ArrayList<>();
        private boolean active;

        private RecordingTransactionManager(List<String> calls) {
            this.calls = calls;
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return active;
        }

        @Override
        protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {
            active = true;
            calls.add("tx.begin");
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            active = false;
            calls.add("tx.commit");
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            active = false;
            calls.add("tx.rollback");
        }

        boolean isActive() {
            return active;
        }
    }
}
