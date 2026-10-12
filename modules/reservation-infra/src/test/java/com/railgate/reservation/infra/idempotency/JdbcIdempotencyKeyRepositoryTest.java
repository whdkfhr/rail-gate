package com.railgate.reservation.infra.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.idempotency.HoldRejectionCode;
import com.railgate.reservation.idempotency.IdempotencyKey;
import com.railgate.reservation.idempotency.IdempotencyKeyState;
import com.railgate.reservation.idempotency.IdempotencyRecord;
import com.railgate.reservation.idempotency.IdempotencyScope;
import com.railgate.reservation.idempotency.KeyOwnership;
import com.railgate.reservation.idempotency.KeyWriteOutcome;
import com.railgate.reservation.idempotency.RequestFingerprint;
import com.railgate.reservation.idempotency.StoredHoldOutcome.Rejected;
import com.railgate.reservation.idempotency.StoredHoldOutcome.Succeeded;
import com.railgate.reservation.infra.MySqlTestSupport;
import com.railgate.reservation.saleevent.SaleEventId;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ★ Task 2I-B — 운영 멱등키 저장소({@link JdbcIdempotencyKeyRepository})를 Flyway V7 스키마와 실제 MySQL 에서 검증한다.
 *
 * <p>리스 만료와 보존 기간 경과는 {@code Thread.sleep} 이 아니라 <b>DB 시각 기준으로 행의 시각 열을 옮겨</b> 만든다.
 * 경합은 래치로 출발을 맞춘다.
 */
@DisplayName("Task 2I-B — JdbcIdempotencyKeyRepository (MySQL)")
class JdbcIdempotencyKeyRepositoryTest extends MySqlTestSupport {

    private static final UserId USER = new UserId(41L);
    private static final UserId OTHER_USER = new UserId(42L);
    private static final IdempotencyKey KEY = new IdempotencyKey("store-key-0001");
    private static final IdempotencyScope SCOPE = IdempotencyScope.holdSeats(USER, KEY);
    private static final RequestFingerprint FINGERPRINT = new RequestFingerprint("a".repeat(64));
    private static final RequestFingerprint OTHER_FINGERPRINT = new RequestFingerprint("b".repeat(64));
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Succeeded SUCCEEDED =
            new Succeeded(HoldId.newId(), new SaleEventId(FIXTURE_SALE_EVENT_ID), 2);
    private static final Rejected REJECTED = new Rejected(HoldRejectionCode.SEAT_UNAVAILABLE);

    private JdbcIdempotencyKeyRepository keys;

    @BeforeEach
    void reset() {
        jdbc().execute("DELETE FROM idempotency_key");
        keys = new JdbcIdempotencyKeyRepository(dataSource());
    }

    // ------------------------------------------------------------------ 픽스처

    private static <T> T tx(Supplier<T> work) {
        return newTransactionTemplate().execute(status -> work.get());
    }

    private KeyOwnership insert(IdempotencyScope scope, RequestFingerprint fingerprint) {
        return tx(() -> keys.tryInsert(scope, fingerprint, LEASE)).orElseThrow();
    }

    private KeyOwnership insert() {
        return insert(SCOPE, FINGERPRINT);
    }

    private void expireLease(IdempotencyScope scope) {
        assertThat(jdbc().update("""
                UPDATE idempotency_key SET lease_until = NOW(3) - INTERVAL 1 SECOND
                 WHERE user_id = ? AND operation = ? AND idempotency_key = ? AND state = 'IN_PROGRESS'
                """, scope.userId().value(), scope.operation().name(), scope.key().value()))
                .as("만료시킬 진행 중 행").isEqualTo(1);
    }

    /** 보존 기간 종료 삭제를 흉내 낸다. 완료 시각을 DB 시각 기준 과거로 옮긴 뒤 보존 조건으로 지운다. */
    private void purgeCompletedAfterRetention() {
        jdbc().update("UPDATE idempotency_key SET completed_at = NOW(3) - INTERVAL 2 DAY WHERE state = 'COMPLETED'");
        assertThat(jdbc().update("""
                DELETE FROM idempotency_key
                 WHERE state = 'COMPLETED' AND completed_at <= NOW(3) - INTERVAL 1 DAY
                """)).as("보존 기간이 지난 완료 행").isEqualTo(1);
    }

    private Map<String, Object> row(IdempotencyScope scope) {
        return jdbc().queryForMap("""
                SELECT request_fingerprint, state, generation, fence, lease_until, result_type,
                       hold_id, sale_event_id, seat_count, completed_at,
                       CAST(lease_until <= NOW(3) AS SIGNED) AS lease_expired
                  FROM idempotency_key
                 WHERE user_id = ? AND operation = ? AND idempotency_key = ?
                """, scope.userId().value(), scope.operation().name(), scope.key().value());
    }

    private long rowCount() {
        return jdbc().queryForObject("SELECT COUNT(*) FROM idempotency_key", Long.class);
    }

    private Timestamp dbNow() {
        return jdbc().queryForObject("SELECT NOW(3)", Timestamp.class);
    }

    // ------------------------------------------------------------------ §1

    @Nested
    @DisplayName("§1 생성·조회·결과 복원")
    class 생성과_조회 {

        @Test
        void V7_마이그레이션이_적용된다() {
            assertThat(jdbc().queryForList(
                    "SELECT version FROM flyway_schema_history WHERE success = 1", String.class))
                    .contains("6", "7");
        }

        @Test
        void 새_키는_새_세대_fence_1_진행_중이고_리스는_DB_시각_기준이다() {
            Timestamp before = dbNow();
            KeyOwnership owned = insert();
            Timestamp after = dbNow();

            assertThat(owned.fence()).isEqualTo(1);
            Map<String, Object> row = row(SCOPE);
            assertThat(row)
                    .containsEntry("state", "IN_PROGRESS")
                    .containsEntry("generation", owned.generation().toString())
                    .containsEntry("fence", 1L)
                    .containsEntry("request_fingerprint", FINGERPRINT.value());
            assertThat(row.get("result_type")).isNull();
            assertThat(row.get("completed_at")).isNull();
            Timestamp leaseUntil = Timestamp.valueOf((java.time.LocalDateTime) row.get("lease_until"));
            assertThat(leaseUntil.getTime())
                    .isBetween(before.getTime() + LEASE.toMillis(), after.getTime() + LEASE.toMillis());
        }

        @Test
        void 진행_중_조회는_소유권과_DB_가_계산한_리스_상태를_돌려준다() {
            KeyOwnership owned = insert();

            IdempotencyRecord live = keys.find(SCOPE).orElseThrow();
            assertThat(live.state()).isEqualTo(IdempotencyKeyState.IN_PROGRESS);
            assertThat(live.ownership()).isEqualTo(owned);
            assertThat(live.fingerprint()).isEqualTo(FINGERPRINT);
            assertThat(live.leaseExpired()).isFalse();
            assertThat(live.outcome()).isEmpty();

            expireLease(SCOPE);
            assertThat(keys.find(SCOPE).orElseThrow().leaseExpired()).isTrue();
        }

        @Test
        void 없는_키는_빈_값이다() {
            assertThat(keys.find(SCOPE)).isEmpty();
        }

        @Test
        void 성공_결과를_기록하고_그대로_복원한다() {
            KeyOwnership owned = insert();
            Timestamp before = dbNow();

            assertThat(tx(() -> keys.completeSucceeded(SCOPE, owned, SUCCEEDED))).isEqualTo(KeyWriteOutcome.APPLIED);

            Timestamp after = dbNow();
            IdempotencyRecord done = keys.find(SCOPE).orElseThrow();
            assertThat(done.state()).isEqualTo(IdempotencyKeyState.COMPLETED);
            assertThat(done.outcome()).contains(SUCCEEDED);
            assertThat(done.leaseExpired()).as("완료 행에는 리스가 없다").isFalse();
            assertThat(done.ownership()).isEqualTo(owned);
            Map<String, Object> row = row(SCOPE);
            assertThat(row.get("lease_until")).isNull();
            assertThat(Timestamp.valueOf((java.time.LocalDateTime) row.get("completed_at"))).isBetween(before, after, true, true);
        }

        @ParameterizedTest
        @EnumSource(HoldRejectionCode.class)
        void 업무_실패를_기록하고_그대로_복원한다(HoldRejectionCode code) {
            KeyOwnership owned = insert();

            assertThat(tx(() -> keys.completeRejected(SCOPE, owned, new Rejected(code))))
                    .isEqualTo(KeyWriteOutcome.APPLIED);

            IdempotencyRecord done = keys.find(SCOPE).orElseThrow();
            assertThat(done.outcome()).contains(new Rejected(code));
            Map<String, Object> row = row(SCOPE);
            assertThat(row.get("hold_id")).isNull();
            assertThat(row.get("sale_event_id")).isNull();
            assertThat(row.get("seat_count")).isNull();
        }

        @Test
        void 완료된_행은_다시_기록하지_않는다() {
            KeyOwnership owned = insert();
            tx(() -> keys.completeSucceeded(SCOPE, owned, SUCCEEDED));

            assertThat(tx(() -> keys.completeRejected(SCOPE, owned, REJECTED))).isEqualTo(KeyWriteOutcome.NOT_OWNER);
            assertThat(tx(() -> keys.completeSucceeded(SCOPE, owned,
                    new Succeeded(HoldId.newId(), new SaleEventId(1L), 1)))).isEqualTo(KeyWriteOutcome.NOT_OWNER);
            assertThat(tx(() -> keys.abandon(SCOPE, owned))).as("커밋 불명 뒤의 포기도 완료 행을 바꾸지 않는다")
                    .isEqualTo(KeyWriteOutcome.NOT_OWNER);
            assertThat(keys.find(SCOPE).orElseThrow().outcome()).contains(SUCCEEDED);
        }

        @Test
        void 포기는_리스만_즉시_만료시키고_결과를_남기지_않는다() {
            KeyOwnership owned = insert();

            assertThat(tx(() -> keys.abandon(SCOPE, owned))).isEqualTo(KeyWriteOutcome.APPLIED);

            IdempotencyRecord record = keys.find(SCOPE).orElseThrow();
            assertThat(record.state()).isEqualTo(IdempotencyKeyState.IN_PROGRESS);
            assertThat(record.leaseExpired()).isTrue();
            assertThat(record.outcome()).isEmpty();
        }
    }

    // ------------------------------------------------------------------ §2

    @Nested
    @DisplayName("§2 키 범위 격리")
    class 격리 {

        @Test
        void 대소문자가_다르면_다른_키다() {
            IdempotencyScope upper = IdempotencyScope.holdSeats(USER, new IdempotencyKey("STORE-key-0001"));
            KeyOwnership lower = insert();
            KeyOwnership other = insert(upper, OTHER_FINGERPRINT);

            assertThat(other.generation()).isNotEqualTo(lower.generation());
            assertThat(rowCount()).isEqualTo(2);
            assertThat(keys.find(upper).orElseThrow().fingerprint()).isEqualTo(OTHER_FINGERPRINT);
            assertThat(keys.find(SCOPE).orElseThrow().fingerprint()).isEqualTo(FINGERPRINT);
        }

        @Test
        void 사용자가_다르면_같은_키라도_다른_행이다() {
            insert();
            IdempotencyScope otherUser = IdempotencyScope.holdSeats(OTHER_USER, KEY);
            KeyOwnership owned = insert(otherUser, OTHER_FINGERPRINT);

            tx(() -> keys.completeSucceeded(otherUser, owned, SUCCEEDED));

            assertThat(keys.find(SCOPE).orElseThrow().state()).as("A 의 행은 그대로").isEqualTo(IdempotencyKeyState.IN_PROGRESS);
            assertThat(keys.find(otherUser).orElseThrow().outcome()).contains(SUCCEEDED);
        }

        /**
         * 연산이 하나뿐이라 포트로는 다른 연산을 만들 수 없다. 다른 연산의 행을 직접 넣어
         * PK 에 연산이 포함돼 선점 범위와 섞이지 않는다는 것을 확인한다.
         */
        @Test
        void 연산이_다른_행은_선점_범위와_섞이지_않는다() {
            jdbc().update("""
                    INSERT INTO idempotency_key
                        (user_id, operation, idempotency_key, request_fingerprint, state, generation, fence, lease_until)
                    VALUES (?, 'OTHER_OPERATION', ?, ?, 'IN_PROGRESS', UUID(), 1, NOW(3) + INTERVAL 30 SECOND)
                    """, USER.value(), KEY.value(), OTHER_FINGERPRINT.value());

            assertThat(keys.find(SCOPE)).as("다른 연산의 행을 읽지 않는다").isEmpty();
            KeyOwnership owned = insert();
            assertThat(owned.fence()).isEqualTo(1);
            assertThat(rowCount()).isEqualTo(2);
        }
    }

    // ------------------------------------------------------------------ §3

    @Nested
    @DisplayName("§3 동시 INSERT 와 장애 구분")
    class 동시_삽입 {

        @Test
        void 같은_키_동시_INSERT_는_하나만_소유하고_나머지는_예외_없이_빈_값이다() throws Exception {
            int threads = 16;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                CountDownLatch ready = new CountDownLatch(threads);
                CountDownLatch go = new CountDownLatch(1);
                List<Future<Optional<KeyOwnership>>> futures = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    futures.add(pool.submit(() -> {
                        ready.countDown();
                        go.await(10, TimeUnit.SECONDS);
                        return tx(() -> keys.tryInsert(SCOPE, FINGERPRINT, LEASE));
                    }));
                }
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                go.countDown();

                List<Optional<KeyOwnership>> results = new ArrayList<>();
                for (Future<Optional<KeyOwnership>> future : futures) {
                    results.add(future.get(30, TimeUnit.SECONDS)); // 예외가 하나라도 있으면 여기서 실패
                }
                List<KeyOwnership> owners = results.stream().flatMap(Optional::stream).toList();
                assertThat(owners).hasSize(1);
                assertThat(rowCount()).isEqualTo(1);
                assertThat(keys.find(SCOPE).orElseThrow().ownership()).isEqualTo(owners.getFirst());
            } finally {
                pool.shutdownNow();
            }
        }

        /**
         * ★ 중복이 아닌 DB 장애는 빈 값으로 삼키지 않는다. 다른 트랜잭션이 같은 키를 넣고 커밋하지 않은 채
         * 쥐고 있으면 뒤의 INSERT 는 잠금 대기 초과(3초, 규칙 9)로 실패한다 — "이미 있음" 이 아니다.
         */
        @Test
        void 잠금_대기_초과는_중복으로_삼키지_않고_던진다() throws Exception {
            CountDownLatch inserted = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<Optional<KeyOwnership>> holder = pool.submit(() -> tx(() -> {
                    Optional<KeyOwnership> owned = keys.tryInsert(SCOPE, FINGERPRINT, LEASE);
                    inserted.countDown();
                    try {
                        release.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return owned;
                }));
                assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();

                assertThatThrownBy(() -> tx(() -> keys.tryInsert(SCOPE, FINGERPRINT, LEASE)))
                        .isInstanceOf(PessimisticLockingFailureException.class);

                release.countDown();
                assertThat(holder.get(30, TimeUnit.SECONDS)).isPresent();
                assertThat(rowCount()).isEqualTo(1);
            } finally {
                release.countDown();
                pool.shutdownNow();
            }
        }
    }

    // ------------------------------------------------------------------ §4

    @Nested
    @DisplayName("§4 리스 인수와 같은 세대의 stale fence")
    class 인수 {

        @Test
        void 리스가_살아_있으면_인수하지_못한다() {
            KeyOwnership owned = insert();

            assertThat(tx(() -> keys.takeOver(SCOPE, owned, LEASE))).isEmpty();
            assertThat(row(SCOPE)).containsEntry("fence", 1L);
        }

        @Test
        void 리스가_만료되면_같은_세대에서_fence_만_올려_인수한다() {
            KeyOwnership owned = insert();
            expireLease(SCOPE);

            Optional<KeyOwnership> taken = tx(() -> keys.takeOver(SCOPE, owned, LEASE));

            assertThat(taken).contains(new KeyOwnership(owned.generation(), 2));
            assertThat(row(SCOPE))
                    .containsEntry("generation", owned.generation().toString())
                    .containsEntry("fence", 2L)
                    .containsEntry("lease_expired", 0L);
        }

        @Test
        void 인수당한_fence_는_기록도_포기도_인수도_못한다() {
            KeyOwnership stale = insert();
            expireLease(SCOPE);
            KeyOwnership taker = tx(() -> keys.takeOver(SCOPE, stale, LEASE)).orElseThrow();

            assertThat(tx(() -> keys.completeSucceeded(SCOPE, stale, SUCCEEDED))).isEqualTo(KeyWriteOutcome.NOT_OWNER);
            assertThat(tx(() -> keys.completeRejected(SCOPE, stale, REJECTED))).isEqualTo(KeyWriteOutcome.NOT_OWNER);
            assertThat(tx(() -> keys.abandon(SCOPE, stale))).isEqualTo(KeyWriteOutcome.NOT_OWNER);
            expireLease(SCOPE);
            assertThat(tx(() -> keys.takeOver(SCOPE, stale, LEASE))).as("같은 스냅숏으로 두 번 인수하지 못한다").isEmpty();

            assertThat(row(SCOPE)).containsEntry("state", "IN_PROGRESS").containsEntry("fence", 2L);
            assertThat(tx(() -> keys.completeSucceeded(SCOPE, taker, SUCCEEDED))).isEqualTo(KeyWriteOutcome.APPLIED);
        }

        @Test
        void 같은_스냅숏으로_동시에_인수하면_하나만_성공한다() throws Exception {
            KeyOwnership owned = insert();
            expireLease(SCOPE);
            int threads = 8;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                CountDownLatch go = new CountDownLatch(1);
                List<Future<Optional<KeyOwnership>>> futures = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    futures.add(pool.submit(() -> {
                        go.await(10, TimeUnit.SECONDS);
                        return tx(() -> keys.takeOver(SCOPE, owned, LEASE));
                    }));
                }
                go.countDown();
                long winners = 0;
                for (Future<Optional<KeyOwnership>> future : futures) {
                    winners += future.get(30, TimeUnit.SECONDS).isPresent() ? 1 : 0;
                }
                assertThat(winners).isEqualTo(1);
                assertThat(row(SCOPE)).containsEntry("fence", 2L);
            } finally {
                pool.shutdownNow();
            }
        }

        @Test
        void 완료된_행은_인수하지_못한다() {
            KeyOwnership owned = insert();
            tx(() -> keys.completeSucceeded(SCOPE, owned, SUCCEEDED));

            assertThat(tx(() -> keys.takeOver(SCOPE, owned, LEASE))).isEmpty();
        }
    }

    // ------------------------------------------------------------------ §5

    @Nested
    @DisplayName("§5 ★ 삭제·재생성 뒤 과거 세대의 소유권")
    class 세대 {

        /** A(g1,1) 확보 → 인수자 B(g1,2) 완료 → 보존 기간 뒤 삭제 → 새 요청(g2,1). A 의 소유권을 돌려준다. */
        private KeyOwnership staleAfterRecreation(RequestFingerprint newFingerprint) {
            KeyOwnership stale = insert();
            expireLease(SCOPE);
            KeyOwnership taker = tx(() -> keys.takeOver(SCOPE, stale, LEASE)).orElseThrow();
            assertThat(tx(() -> keys.completeSucceeded(SCOPE, taker, SUCCEEDED))).isEqualTo(KeyWriteOutcome.APPLIED);
            purgeCompletedAfterRetention();
            KeyOwnership fresh = insert(SCOPE, newFingerprint);
            assertThat(fresh.fence()).as("새 행도 fence 1 — fence 만으로는 구별되지 않는다").isEqualTo(stale.fence());
            assertThat(fresh.generation()).isNotEqualTo(stale.generation());
            return stale;
        }

        private void assertFreshRowUntouched(RequestFingerprint newFingerprint) {
            Map<String, Object> row = row(SCOPE);
            assertThat(row)
                    .containsEntry("request_fingerprint", newFingerprint.value())
                    .containsEntry("state", "IN_PROGRESS")
                    .containsEntry("fence", 1L)
                    .containsEntry("lease_expired", 0L);
            assertThat(row.get("result_type")).isNull();
            assertThat(row.get("completed_at")).isNull();
        }

        private RequestFingerprint fingerprintFor(boolean sameRequest) {
            return sameRequest ? FINGERPRINT : OTHER_FINGERPRINT;
        }

        @ParameterizedTest(name = "새 요청 fingerprint 동일={0}")
        @ValueSource(booleans = {true, false})
        void 과거_세대의_성공_기록은_새_행을_바꾸지_않는다(boolean sameRequest) {
            KeyOwnership stale = staleAfterRecreation(fingerprintFor(sameRequest));

            assertThat(tx(() -> keys.completeSucceeded(SCOPE, stale, SUCCEEDED))).isEqualTo(KeyWriteOutcome.NOT_OWNER);

            assertFreshRowUntouched(fingerprintFor(sameRequest));
        }

        @ParameterizedTest(name = "새 요청 fingerprint 동일={0}")
        @ValueSource(booleans = {true, false})
        void 과거_세대의_실패_기록은_새_행을_바꾸지_않는다(boolean sameRequest) {
            KeyOwnership stale = staleAfterRecreation(fingerprintFor(sameRequest));

            assertThat(tx(() -> keys.completeRejected(SCOPE, stale, REJECTED))).isEqualTo(KeyWriteOutcome.NOT_OWNER);

            assertFreshRowUntouched(fingerprintFor(sameRequest));
        }

        @ParameterizedTest(name = "새 요청 fingerprint 동일={0}")
        @ValueSource(booleans = {true, false})
        void 과거_세대의_포기는_새_행의_리스를_만료시키지_않는다(boolean sameRequest) {
            KeyOwnership stale = staleAfterRecreation(fingerprintFor(sameRequest));

            assertThat(tx(() -> keys.abandon(SCOPE, stale))).isEqualTo(KeyWriteOutcome.NOT_OWNER);

            assertFreshRowUntouched(fingerprintFor(sameRequest));
        }

        /** 새 행의 리스까지 만료시켜, 리스가 아니라 세대 불일치로 막힌다는 것을 확인한다. */
        @ParameterizedTest(name = "새 요청 fingerprint 동일={0}")
        @ValueSource(booleans = {true, false})
        void 삭제_전_스냅숏으로_재생성된_행을_인수하지_못한다(boolean sameRequest) {
            insert();
            expireLease(SCOPE);
            IdempotencyRecord snapshot = keys.find(SCOPE).orElseThrow();
            KeyOwnership taker = tx(() -> keys.takeOver(SCOPE, snapshot.ownership(), LEASE)).orElseThrow();
            tx(() -> keys.completeSucceeded(SCOPE, taker, SUCCEEDED));
            purgeCompletedAfterRetention();
            insert(SCOPE, fingerprintFor(sameRequest));
            expireLease(SCOPE);

            assertThat(tx(() -> keys.takeOver(SCOPE, snapshot.ownership(), LEASE))).isEmpty();

            assertThat(row(SCOPE)).containsEntry("fence", 1L).containsEntry("state", "IN_PROGRESS");
        }
    }

    // ------------------------------------------------------------------ §6

    @Nested
    @DisplayName("§6 ★ 호출자 트랜잭션 참여")
    class 트랜잭션 {

        @Test
        void 트랜잭션_밖의_변경은_SQL_전에_거부한다() {
            assertThatThrownBy(() -> keys.tryInsert(SCOPE, FINGERPRINT, LEASE))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(rowCount()).as("행이 생기지 않았다").isZero();

            KeyOwnership owned = insert();
            expireLease(SCOPE);
            Map<String, Object> before = row(SCOPE);
            assertThatThrownBy(() -> keys.takeOver(SCOPE, owned, LEASE)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> keys.completeSucceeded(SCOPE, owned, SUCCEEDED))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> keys.completeRejected(SCOPE, owned, REJECTED))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> keys.abandon(SCOPE, owned)).isInstanceOf(IllegalStateException.class);
            assertThat(row(SCOPE)).isEqualTo(before);
        }

        @Test
        void 조회는_트랜잭션_밖에서도_된다() {
            insert();
            assertThat(keys.find(SCOPE)).isPresent();
        }

        @Test
        void 호출자가_롤백하면_INSERT_도_사라진다() {
            newTransactionTemplate().executeWithoutResult(status -> {
                assertThat(keys.tryInsert(SCOPE, FINGERPRINT, LEASE)).isPresent();
                status.setRollbackOnly();
            });

            assertThat(rowCount()).isZero();
        }

        @Test
        void 호출자가_롤백하면_완료_기록과_인수도_사라진다() {
            KeyOwnership owned = insert();
            newTransactionTemplate().executeWithoutResult(status -> {
                assertThat(keys.completeSucceeded(SCOPE, owned, SUCCEEDED)).isEqualTo(KeyWriteOutcome.APPLIED);
                status.setRollbackOnly();
            });
            assertThat(row(SCOPE)).containsEntry("state", "IN_PROGRESS");

            expireLease(SCOPE);
            newTransactionTemplate().executeWithoutResult(status -> {
                assertThat(keys.takeOver(SCOPE, owned, LEASE)).isPresent();
                status.setRollbackOnly();
            });
            assertThat(row(SCOPE)).containsEntry("fence", 1L).containsEntry("lease_expired", 1L);
        }

        /** 다른 DataSource 위의 트랜잭션은 참여가 아니다. 그 안에서 불러도 거부한다. */
        @Test
        void 다른_DataSource_의_트랜잭션_안에서는_거부한다() {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(jdbcUrl());
            config.setUsername("root");
            config.setPassword(rootPassword());
            config.setMaximumPoolSize(2);
            try (HikariDataSource other = new HikariDataSource(config)) {
                TransactionTemplate otherTx = new TransactionTemplate(new DataSourceTransactionManager(other));

                assertThatThrownBy(() -> otherTx.executeWithoutResult(status ->
                        keys.tryInsert(SCOPE, FINGERPRINT, LEASE)))
                        .isInstanceOf(IllegalStateException.class);
            }
            assertThat(rowCount()).isZero();
        }
    }

    // ------------------------------------------------------------------ §7

    @Nested
    @DisplayName("§7 입력 검증과 스키마 제약")
    class 검증 {

        @Test
        void 리스는_1ms_이상_밀리초_단위여야_한다() {
            assertThatThrownBy(() -> tx(() -> keys.tryInsert(SCOPE, FINGERPRINT, Duration.ZERO)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> tx(() -> keys.tryInsert(SCOPE, FINGERPRINT, Duration.ofMillis(-1))))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> tx(() -> keys.tryInsert(SCOPE, FINGERPRINT, Duration.ofNanos(1_500_000))))
                    .as("DATETIME(3) 가 담지 못하는 정밀도").isInstanceOf(IllegalArgumentException.class);
            assertThat(rowCount()).isZero();
        }

        @Test
        void null_을_거부한다() {
            KeyOwnership owned = insert();
            assertThatThrownBy(() -> tx(() -> keys.tryInsert(null, FINGERPRINT, LEASE))).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> tx(() -> keys.tryInsert(SCOPE, null, LEASE))).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> tx(() -> keys.tryInsert(SCOPE, FINGERPRINT, null))).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> tx(() -> keys.takeOver(SCOPE, null, LEASE))).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> tx(() -> keys.completeSucceeded(SCOPE, owned, null))).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> tx(() -> keys.completeRejected(SCOPE, null, REJECTED))).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> tx(() -> keys.abandon(null, owned))).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> keys.find(null)).isInstanceOf(NullPointerException.class);
        }

        /**
         * 저장소를 거치지 않은 쓰기도 상태와 결과 필드가 어긋난 행을 만들 수 없다 (최후의 방어선).
         * Spring 은 MySQL CHECK 위반(3819)을 {@code DataIntegrityViolationException} 이 아니라 분류되지 않은
         * 예외로 올리므로 제약 이름으로 확인한다 ({@code SaleEventSchemaMigrationTest} 와 같은 방식).
         */
        @Test
        void 상태와_결과_필드가_어긋난_행은_스키마가_거부한다() {
            String insert = """
                    INSERT INTO idempotency_key
                        (user_id, operation, idempotency_key, request_fingerprint, state, generation, fence,
                         lease_until, result_type, hold_id, sale_event_id, seat_count, completed_at)
                    VALUES (1, 'HOLD_SEATS', ?, ?, ?, UUID(), ?, %s, ?, ?, ?, ?, %s)
                    """;
            String fp = FINGERPRINT.value();
            // 진행 중인데 결과가 있다
            assertThatThrownBy(() -> jdbc().update(insert.formatted("NOW(3)", "NULL"),
                    "bad-key-0001", fp, "IN_PROGRESS", 1, "SEAT_UNAVAILABLE", null, null, null))
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_idempotency_key_shape");
            // 완료인데 리스가 남아 있다
            assertThatThrownBy(() -> jdbc().update(insert.formatted("NOW(3)", "NOW(3)"),
                    "bad-key-0002", fp, "COMPLETED", 1, "SEAT_UNAVAILABLE", null, null, null))
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_idempotency_key_shape");
            // 성공인데 holdId 가 없다
            assertThatThrownBy(() -> jdbc().update(insert.formatted("NULL", "NOW(3)"),
                    "bad-key-0003", fp, "COMPLETED", 1, "SUCCEEDED", null, 1L, 1))
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_idempotency_key_shape");
            // 업무 실패인데 성공 필드가 있다
            assertThatThrownBy(() -> jdbc().update(insert.formatted("NULL", "NOW(3)"),
                    "bad-key-0004", fp, "COMPLETED", 1, "QUOTA_EXCEEDED", HoldId.newId().asString(), 1L, 1))
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_idempotency_key_shape");
            // fence 0
            assertThatThrownBy(() -> jdbc().update(insert.formatted("NOW(3)", "NULL"),
                    "bad-key-0005", fp, "IN_PROGRESS", 0, null, null, null, null))
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_idempotency_key_fence");
            // 알 수 없는 결과 종류
            assertThatThrownBy(() -> jdbc().update(insert.formatted("NULL", "NOW(3)"),
                    "bad-key-0006", fp, "COMPLETED", 1, "TIMEOUT", null, null, null))
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_idempotency_key_");
            assertThat(rowCount()).isZero();
        }
    }
}
