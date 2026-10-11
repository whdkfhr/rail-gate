package com.railgate.reservation.application.hold.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.application.hold.idempotency.StoredHoldOutcome.Rejected;
import com.railgate.reservation.application.hold.idempotency.StoredHoldOutcome.RejectionCode;
import com.railgate.reservation.application.hold.idempotency.StoredHoldOutcome.Succeeded;
import com.railgate.reservation.saleevent.SaleEventId;
import com.railgate.reservation.support.MySqlSpringTestSupport;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * ★ PR #28 리뷰 P2 — <b>삭제·재생성된 키 행을 과거 세대의 작업자가 바꿀 수 없다.</b>
 *
 * <p>키 행은 보존 기간이 지나면 삭제되고, 같은 키의 새 요청은 새 행을 만든다. 새 행의 fence 도 1 부터
 * 시작하므로 <b>fence 만으로는 과거 행의 fence 1 과 새 행의 fence 1 을 구별할 수 없다.</b>
 * 과거 세대의 작업자가 그 fence 를 들고 늦게 돌아오면 새 요청의 행에 과거 결과를 기록하게 된다.
 *
 * <p>순서(모두 DB 시각과 직접 SQL 로 결정적으로 만든다, {@code Thread.sleep} 없음):
 * <ol>
 *   <li>구 작업자 A 가 키를 확보한다 (fence 1)</li>
 *   <li>리스를 DB 시각 기준 과거로 옮기고 B 가 인수해 완료한다 (fence 2)</li>
 *   <li>완료 행을 삭제한다 — 보존 기간 종료 삭제를 흉내 낸다</li>
 *   <li>같은 키의 새 요청이 행을 다시 만든다 (fence 1)</li>
 *   <li>A 가 자신의 소유권으로 기록·포기를 시도한다 → <b>0 건이어야 한다</b></li>
 * </ol>
 *
 * <p>새 요청의 fingerprint 가 같은 경우와 다른 경우를 모두 본다. fingerprint 를 조건에 넣는 것만으로는
 * 같은 요청으로 키를 재사용하는 경우를 막지 못한다.
 */
@DisplayName("Task 2I-A P2 — 키 세대(generation) 소유권 (MySQL)")
class IdempotencyKeyGenerationTest extends MySqlSpringTestSupport {

    private static final UserId USER = new UserId(31L);
    private static final IdempotencyScope SCOPE =
            IdempotencyScope.holdSeats(USER, new IdempotencyKey("generation-key-0001"));
    private static final String FINGERPRINT = "a".repeat(64);
    private static final String OTHER_FINGERPRINT = "b".repeat(64);
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Succeeded SUCCEEDED =
            new Succeeded(HoldId.newId(), new SaleEventId(FIXTURE_SALE_EVENT_ID), 1);

    private Task2iaIdempotencyStore store;

    @BeforeEach
    void prepare() {
        Task2iaIdempotencyStore.createTableIfAbsent(jdbc());
        jdbc().execute("DELETE FROM " + Task2iaIdempotencyStore.TABLE);
        store = new Task2iaIdempotencyStore(dataSource());
    }

    // ------------------------------------------------------------------ 픽스처

    private void expireLease() {
        assertThat(jdbc().update("""
                UPDATE task2ia_idempotency_key SET lease_until = NOW(3) - INTERVAL 1 SECOND
                 WHERE user_id = ? AND operation = ? AND idempotency_key = ? AND state = 'IN_PROGRESS'
                """, USER.value(), SCOPE.operation(), SCOPE.key().value())).isEqualTo(1);
    }

    /** 보존 기간 종료 삭제. 완료 시각을 DB 시각 기준 과거로 옮긴 뒤 보존 기간 조건으로 지운다. */
    private void purgeAfterRetention() {
        jdbc().update("""
                UPDATE task2ia_idempotency_key SET completed_at = NOW(3) - INTERVAL 2 DAY
                 WHERE user_id = ? AND state = 'COMPLETED'
                """, USER.value());
        assertThat(jdbc().update("""
                DELETE FROM task2ia_idempotency_key
                 WHERE state = 'COMPLETED' AND completed_at <= NOW(3) - INTERVAL 1 DAY
                """)).as("보존 기간이 지난 완료 행 하나가 지워진다").isEqualTo(1);
    }

    private Map<String, Object> row() {
        return jdbc().queryForMap("""
                SELECT request_fingerprint, state, fence, result_type, hold_id,
                       CAST(lease_until <= NOW(3) AS SIGNED) AS lease_expired
                  FROM task2ia_idempotency_key
                 WHERE user_id = ? AND operation = ? AND idempotency_key = ?
                """, USER.value(), SCOPE.operation(), SCOPE.key().value());
    }

    /**
     * 1~4 단계. 구 작업자 A 의 소유권(세대 g1, fence 1)을 돌려준다. 새 행은 {@code newFingerprint} 로 만들어져
     * {@code IN_PROGRESS}·fence 1·리스 유효 상태다.
     */
    private KeyOwnership staleOwnerAfterRecreation(String newFingerprint) {
        KeyOwnership stale = store.insert(SCOPE, FINGERPRINT, LEASE);           // A: (g1, 1)
        expireLease();
        KeyOwnership taker = store.takeOver(SCOPE, stale, LEASE).orElseThrow(); // B: (g1, 2)
        assertThat(store.completeSucceeded(SCOPE, taker, SUCCEEDED)).isEqualTo(1);
        purgeAfterRetention();
        KeyOwnership fresh = store.insert(SCOPE, newFingerprint, LEASE);        // 새 요청: (g2, 1)
        assertThat(fresh.fence()).as("새 행도 fence 1 — fence 만으로는 구별되지 않는다").isEqualTo(stale.fence());
        assertThat(fresh.generation()).isNotEqualTo(stale.generation());
        return stale;
    }

    private void assertNewRowUntouched(String newFingerprint) {
        assertThat(row())
                .containsEntry("request_fingerprint", newFingerprint)
                .containsEntry("state", "IN_PROGRESS")
                .containsEntry("fence", 1L)
                .containsEntry("lease_expired", 0L);
        assertThat(row().get("result_type")).isNull();
        assertThat(row().get("hold_id")).isNull();
    }

    private static String fingerprintFor(boolean sameRequest) {
        return sameRequest ? FINGERPRINT : OTHER_FINGERPRINT;
    }

    // ------------------------------------------------------------------ 시나리오

    @ParameterizedTest(name = "새 요청 fingerprint 동일={0}")
    @ValueSource(booleans = {true, false})
    void 과거_세대의_실패_기록은_새_행을_바꾸지_않는다(boolean sameRequest) {
        String newFingerprint = fingerprintFor(sameRequest);
        KeyOwnership stale = staleOwnerAfterRecreation(newFingerprint);

        assertThat(store.completeRejected(SCOPE, stale, new Rejected(RejectionCode.SEAT_UNAVAILABLE)))
                .as("★ 새 요청의 소유권을 침해하지 않는다").isZero();

        assertNewRowUntouched(newFingerprint);
    }

    @ParameterizedTest(name = "새 요청 fingerprint 동일={0}")
    @ValueSource(booleans = {true, false})
    void 과거_세대의_성공_기록은_새_행을_바꾸지_않는다(boolean sameRequest) {
        String newFingerprint = fingerprintFor(sameRequest);
        KeyOwnership stale = staleOwnerAfterRecreation(newFingerprint);

        assertThat(store.completeSucceeded(SCOPE, stale, SUCCEEDED)).isZero();

        assertNewRowUntouched(newFingerprint);
    }

    @ParameterizedTest(name = "새 요청 fingerprint 동일={0}")
    @ValueSource(booleans = {true, false})
    void 과거_세대의_포기는_새_행의_리스를_만료시키지_않는다(boolean sameRequest) {
        String newFingerprint = fingerprintFor(sameRequest);
        KeyOwnership stale = staleOwnerAfterRecreation(newFingerprint);

        assertThat(store.abandon(SCOPE, stale)).isZero();

        assertNewRowUntouched(newFingerprint);
    }

    /**
     * 삭제 전에 읽은 스냅숏(fence 1, 리스 만료)으로 재생성된 행을 인수할 수 없다.
     * 새 행의 리스도 만료시켜 "리스가 살아 있어서" 막히는 경우와 구별한다 — 세대가 달라서 막혀야 한다.
     */
    @ParameterizedTest(name = "새 요청 fingerprint 동일={0}")
    @ValueSource(booleans = {true, false})
    void 삭제_전_스냅숏으로_재생성된_행을_인수할_수_없다(boolean sameRequest) {
        store.insert(SCOPE, FINGERPRINT, LEASE);
        expireLease();
        IdempotencyRecord snapshot = store.find(SCOPE).orElseThrow();
        assertThat(snapshot.leaseExpired()).isTrue();
        // 스냅숏을 쥔 채 지연되는 동안: 다른 작업자가 인수·완료하고 행이 보존 기간 뒤 삭제된다.
        KeyOwnership taker = store.takeOver(SCOPE, snapshot.ownership(), LEASE).orElseThrow();
        assertThat(store.completeSucceeded(SCOPE, taker, SUCCEEDED)).isEqualTo(1);
        purgeAfterRetention();
        store.insert(SCOPE, fingerprintFor(sameRequest), LEASE);
        expireLease();

        assertThat(store.takeOver(SCOPE, snapshot.ownership(), LEASE))
                .as("★ 다른 세대의 스냅숏으로는 인수하지 못한다").isEmpty();

        assertThat(row()).containsEntry("fence", 1L).containsEntry("state", "IN_PROGRESS");
    }

    /** 대조 — 같은 세대 안에서의 인수와 기록은 그대로 동작한다. */
    @Test
    void 같은_세대의_인수와_완료는_그대로_동작한다() {
        store.insert(SCOPE, FINGERPRINT, LEASE);
        expireLease();
        IdempotencyRecord snapshot = store.find(SCOPE).orElseThrow();

        KeyOwnership taker = store.takeOver(SCOPE, snapshot.ownership(), LEASE).orElseThrow();
        assertThat(taker).isEqualTo(new KeyOwnership(snapshot.ownership().generation(), 2));
        assertThat(store.completeRejected(SCOPE, snapshot.ownership(), new Rejected(RejectionCode.SEAT_UNAVAILABLE)))
                .as("인수당한 fence 는 기록하지 못한다").isZero();
        assertThat(store.completeSucceeded(SCOPE, taker, SUCCEEDED)).isEqualTo(1);
        assertThat(row()).containsEntry("state", "COMPLETED").containsEntry("fence", 2L);
        assertThat(generationInRow()).as("인수는 세대를 바꾸지 않는다").isEqualTo(snapshot.ownership().generation());
    }

    private String generationInRow() {
        return jdbc().queryForObject("""
                SELECT generation FROM task2ia_idempotency_key
                 WHERE user_id = ? AND operation = ? AND idempotency_key = ?
                """, String.class, USER.value(), SCOPE.operation(), SCOPE.key().value());
    }
}
