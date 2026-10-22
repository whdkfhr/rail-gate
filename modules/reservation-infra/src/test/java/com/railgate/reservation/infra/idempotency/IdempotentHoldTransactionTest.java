package com.railgate.reservation.infra.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.idempotency.IdempotencyKey;
import com.railgate.reservation.idempotency.IdempotencyScope;
import com.railgate.reservation.idempotency.KeyOwnership;
import com.railgate.reservation.idempotency.KeyWriteOutcome;
import com.railgate.reservation.idempotency.RequestFingerprint;
import com.railgate.reservation.idempotency.StoredHoldOutcome.Succeeded;
import com.railgate.reservation.infra.MySqlTestSupport;
import com.railgate.reservation.infra.quota.JdbcUserHoldQuotaRepository;
import com.railgate.reservation.infra.seat.JdbcMultiSeatHoldRepository;
import com.railgate.reservation.quota.QuotaAcquireOutcome;
import com.railgate.reservation.saleevent.SaleEventId;
import com.railgate.reservation.seat.SeatId;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ★ Task 2I-B — <b>선점과 성공 결과 기록이 한 트랜잭션(TX1)</b>일 때, 성공 기록이 거절되면 좌석·quota 까지
 * 롤백되는지 운영 저장소들로 확인한다.
 *
 * <p>여기서 TX1 을 여는 것은 테스트다. 운영 조정자는 아직 없다 — 이 테스트는 "저장소들이 같은
 * DataSource 트랜잭션에 참여해 함께 롤백된다" 는 저장소 계약을 고정할 뿐 요청 경로를 검증하지 않는다.
 *
 * <p>TX1 의 잠금 순서는 {@code HoldSeatsService} 와 같은 <b>quota → seat(PK 오름차순) → 키 행</b>이다.
 */
@DisplayName("Task 2I-B — 선점 + 성공 기록 한 트랜잭션 (MySQL)")
class IdempotentHoldTransactionTest extends MySqlTestSupport {

    private static final long SCHEDULE = 7_900L;
    private static final UserId USER = new UserId(51L);
    private static final IdempotencyScope SCOPE =
            IdempotencyScope.holdSeats(USER, new IdempotencyKey("hold-tx-key-0001"));
    private static final RequestFingerprint FINGERPRINT = new RequestFingerprint("c".repeat(64));
    private static final Duration LEASE = Duration.ofSeconds(30);

    private JdbcIdempotencyKeyRepository keys;
    private JdbcUserHoldQuotaRepository quota;
    private JdbcMultiSeatHoldRepository seats;

    /** TX1 의 본 작업이 끝까지 갔는데 성공 기록이 거절됐다는 표시. 이 예외로 TX1 이 롤백된다. */
    private static final class NotOwnerException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    @BeforeEach
    void wire() {
        jdbc().execute("DELETE FROM idempotency_key");
        keys = new JdbcIdempotencyKeyRepository(dataSource());
        quota = new JdbcUserHoldQuotaRepository(dataSource());
        seats = new JdbcMultiSeatHoldRepository(dataSource(), transactionManager(), Duration.ofMinutes(5));
    }

    private KeyOwnership acquire() {
        return newTransactionTemplate().execute(status -> keys.tryInsert(SCOPE, FINGERPRINT, LEASE)).orElseThrow();
    }

    /** {@code HoldSeatsService} 의 본 작업과 같은 순서에 성공 기록을 마지막으로 붙인 TX1. */
    private HoldId holdAndRecord(KeyOwnership mine, long... seatIds) {
        List<SeatId> ids = java.util.Arrays.stream(seatIds).mapToObj(SeatId::new).toList();
        SaleEventId saleEvent = new SaleEventId(FIXTURE_SALE_EVENT_ID);
        return newTransactionTemplate().execute(status -> {
            quota.ensureRow(saleEvent, USER);
            assertThat(quota.tryAcquire(saleEvent, USER, ids.size())).isEqualTo(QuotaAcquireOutcome.ACQUIRED);
            HoldId holdId = HoldId.newId();
            seats.holdAll(ids, holdId, USER);
            Succeeded succeeded = new Succeeded(holdId, saleEvent, ids.size());
            if (keys.completeSucceeded(SCOPE, mine, succeeded) != KeyWriteOutcome.APPLIED) {
                throw new NotOwnerException();
            }
            return holdId;
        });
    }

    private void expireLease() {
        jdbc().update("""
                UPDATE idempotency_key SET lease_until = NOW(3) - INTERVAL 1 SECOND
                 WHERE user_id = ? AND state = 'IN_PROGRESS'
                """, USER.value());
    }

    private Map<String, Object> keyRow() {
        return jdbc().queryForMap(
                "SELECT state, fence, result_type, hold_id FROM idempotency_key WHERE user_id = ?", USER.value());
    }

    private int quotaOf() {
        Integer value = jdbc().query(
                "SELECT held_seats FROM user_hold_quota WHERE sale_event_id = ? AND user_id = ?",
                rs -> rs.next() ? rs.getInt(1) : null, FIXTURE_SALE_EVENT_ID, USER.value());
        return value == null ? -1 : value;
    }

    @Test
    void 현재_소유자의_성공_기록은_선점과_함께_커밋된다() {
        long a = insertAvailableSeat(SCHEDULE, "1A");
        long b = insertAvailableSeat(SCHEDULE, "1B");
        KeyOwnership mine = acquire();

        HoldId held = holdAndRecord(mine, a, b);

        assertThat(statusOf(a)).isEqualTo("HELD");
        assertThat(holdIdOf(b)).isEqualTo(held.asString());
        assertThat(quotaOf()).isEqualTo(2);
        assertThat(keyRow()).containsEntry("state", "COMPLETED").containsEntry("hold_id", held.asString());
    }

    /** 리스를 빼앗긴(같은 세대, 낡은 fence) 작업자의 TX1 은 좌석·quota·키 모두 롤백된다. */
    @Test
    void 인수당한_작업자의_성공_기록이_거절되면_좌석과_quota_까지_롤백된다() {
        long a = insertAvailableSeat(SCHEDULE, "1A");
        long b = insertAvailableSeat(SCHEDULE, "1B");
        KeyOwnership stale = acquire();
        expireLease();
        newTransactionTemplate().execute(status -> keys.takeOver(SCOPE, stale, LEASE)).orElseThrow();

        assertThatThrownBy(() -> holdAndRecord(stale, a, b)).isInstanceOf(NotOwnerException.class);

        assertThat(statusOf(a)).isEqualTo("AVAILABLE");
        assertThat(statusOf(b)).isEqualTo("AVAILABLE");
        assertThat(quotaOf()).as("같은 TX1 에서 만든 quota 행까지 사라진다").isEqualTo(-1);
        assertThat(keyRow()).containsEntry("state", "IN_PROGRESS").containsEntry("fence", 2L);
    }

    /** ★ 행이 삭제·재생성된 뒤 돌아온 과거 세대 작업자의 TX1 도 같은 방식으로 롤백된다. */
    @Test
    void 과거_세대_작업자의_성공_기록이_거절되면_좌석과_quota_까지_롤백된다() {
        long a = insertAvailableSeat(SCHEDULE, "1A");
        KeyOwnership stale = acquire();
        // 인수자가 업무 실패로 완료하고, 그 완료 행이 보존 기간 뒤 삭제된다. 진행 중 행은 지우지 않는다.
        expireLease();
        KeyOwnership taker = newTransactionTemplate().execute(status -> keys.takeOver(SCOPE, stale, LEASE)).orElseThrow();
        newTransactionTemplate().execute(status -> keys.completeRejected(SCOPE, taker,
                new com.railgate.reservation.idempotency.StoredHoldOutcome.Rejected(
                        com.railgate.reservation.idempotency.HoldRejectionCode.SEAT_UNAVAILABLE)));
        jdbc().update("UPDATE idempotency_key SET completed_at = NOW(3) - INTERVAL 2 DAY WHERE user_id = ?", USER.value());
        assertThat(jdbc().update("""
                DELETE FROM idempotency_key
                 WHERE state = 'COMPLETED' AND completed_at <= NOW(3) - INTERVAL 1 DAY
                """)).isEqualTo(1);
        KeyOwnership fresh = acquire();
        assertThat(fresh.fence()).isEqualTo(stale.fence());

        assertThatThrownBy(() -> holdAndRecord(stale, a)).isInstanceOf(NotOwnerException.class);

        assertThat(statusOf(a)).isEqualTo("AVAILABLE");
        assertThat(quotaOf()).isEqualTo(-1);
        assertThat(keyRow()).containsEntry("state", "IN_PROGRESS").containsEntry("fence", 1L);

        HoldId held = holdAndRecord(fresh, a);
        assertThat(holdIdOf(a)).as("새 세대의 작업자는 정상 완료").isEqualTo(held.asString());
        assertThat(quotaOf()).isEqualTo(1);
    }
}
