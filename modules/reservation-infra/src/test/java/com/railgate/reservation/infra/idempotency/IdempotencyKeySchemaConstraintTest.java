package com.railgate.reservation.infra.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.idempotency.HoldRejectionCode;
import com.railgate.reservation.idempotency.IdempotencyKey;
import com.railgate.reservation.idempotency.IdempotencyKeyState;
import com.railgate.reservation.idempotency.IdempotencyRecord;
import com.railgate.reservation.idempotency.IdempotencyScope;
import com.railgate.reservation.idempotency.StoredHoldOutcome.Rejected;
import com.railgate.reservation.idempotency.StoredHoldOutcome.Succeeded;
import com.railgate.reservation.infra.MySqlTestSupport;
import com.railgate.reservation.saleevent.SaleEventId;
import java.sql.SQLException;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * ★ PR #29 리뷰 보완 — V7 {@code ck_idempotency_key_shape} 가 <b>NULL 로 우회되지 않는지</b> 실제 MySQL·Flyway 로 검증한다.
 *
 * <p>MySQL 의 CHECK 는 식이 <b>FALSE 일 때만</b> 위반이다. NULL 열과 비교한 식은 UNKNOWN 이 되고, UNKNOWN 은 통과한다.
 * 보완 전 V7 에서는 다음 두 행이 실제로 들어갔고, 이후 {@code JdbcIdempotencyKeyRepository.find()} 의 결과 복원이 실패했다.
 *
 * <ul>
 *   <li>A — {@code COMPLETED} 인데 {@code result_type} 과 성공 필드가 모두 NULL</li>
 *   <li>B — {@code SUCCEEDED} 인데 {@code seat_count} 만 NULL</li>
 * </ul>
 *
 * <p>Java 값 객체의 검증은 저장소를 거치지 않은 쓰기를 막지 못한다. 이 테스트는 SQL 을 직접 보낸다.
 * 위반이 <b>의도한 CHECK</b> 에서 났는지는 MySQL 오류 코드 3819 와 제약 이름으로 확인한다.
 */
@DisplayName("PR #29 보완 — V7 상태·결과 CHECK 의 NULL 우회 (MySQL)")
class IdempotencyKeySchemaConstraintTest extends MySqlTestSupport {

    private static final int MYSQL_CHECK_CONSTRAINT_VIOLATED = 3819;
    private static final String SHAPE = "ck_idempotency_key_shape";
    private static final long USER = 77L;
    private static final String FINGERPRINT = "c".repeat(64);
    private static final String HOLD = HoldId.newId().asString();

    private JdbcIdempotencyKeyRepository keys;

    @BeforeEach
    void reset() {
        jdbc().execute("DELETE FROM idempotency_key");
        keys = new JdbcIdempotencyKeyRepository(dataSource());
    }

    // ------------------------------------------------------------------ 픽스처

    /** 저장소를 거치지 않는 직접 INSERT. 시각 열은 SQL 식(NOW(3) 또는 NULL)으로 넣는다. */
    private static void insertRaw(String key, String state, String leaseUntilSql, String resultType,
                                  String holdId, Long saleEventId, Integer seatCount, String completedAtSql) {
        jdbc().update("""
                INSERT INTO idempotency_key
                    (user_id, operation, idempotency_key, request_fingerprint, state, generation, fence,
                     lease_until, result_type, hold_id, sale_event_id, seat_count, completed_at)
                VALUES (?, 'HOLD_SEATS', ?, ?, ?, UUID(), 1, %s, ?, ?, ?, ?, %s)
                """.formatted(leaseUntilSql, completedAtSql),
                USER, key, FINGERPRINT, state, resultType, holdId, saleEventId, seatCount);
    }

    private static void insertInProgress(String key) {
        insertRaw(key, "IN_PROGRESS", "NOW(3) + INTERVAL 30 SECOND", null, null, null, null, "NULL");
    }

    private static void insertSucceeded(String key) {
        insertRaw(key, "COMPLETED", "NULL", "SUCCEEDED", HOLD, FIXTURE_SALE_EVENT_ID, 2, "NOW(3)");
    }

    private static void insertRejected(String key, HoldRejectionCode code) {
        insertRaw(key, "COMPLETED", "NULL", code.name(), null, null, null, "NOW(3)");
    }

    private static Map<String, Object> row(String key) {
        return jdbc().queryForMap("""
                SELECT state, lease_until, result_type, hold_id, sale_event_id, seat_count, completed_at
                  FROM idempotency_key WHERE user_id = ? AND operation = 'HOLD_SEATS' AND idempotency_key = ?
                """, USER, key);
    }

    private static long rowCount() {
        return jdbc().queryForObject("SELECT COUNT(*) FROM idempotency_key", Long.class);
    }

    private static IdempotencyScope scope(String key) {
        return IdempotencyScope.holdSeats(new UserId(USER), new IdempotencyKey(key));
    }

    /** 위반이 3819(CHECK) 이고 그 제약 이름이 {@value #SHAPE} 인지 확인한다. 다른 제약이나 NOT NULL 위반이면 실패한다. */
    private static void assertRejectedByShape(ThrowingCallable write) {
        Throwable thrown = catchThrowable(write);
        assertThat((Object) thrown).as("CHECK 가 거부해야 한다 — 통과했다면 UNKNOWN 으로 우회된 것").isNotNull();
        SQLException sql = findSqlException(thrown);
        assertThat((Object) sql).as("SQLException 원인").isNotNull();
        assertThat(sql.getErrorCode()).as("MySQL CHECK 위반 코드").isEqualTo(MYSQL_CHECK_CONSTRAINT_VIOLATED);
        assertThat(sql.getMessage()).contains("Check constraint '" + SHAPE + "' is violated");
    }

    private static SQLException findSqlException(Throwable thrown) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ §1 INSERT

    @Nested
    @DisplayName("§1 ★ INSERT — NULL 이 섞인 완료 행을 거부한다")
    class 삽입 {

        /** 리뷰 A. result_type 이 NULL 이면 두 결과 분기가 모두 UNKNOWN 이 되어 통과했다. */
        @Test
        void A_완료인데_결과_종류와_성공_필드가_모두_NULL_이면_거부한다() {
            assertRejectedByShape(() ->
                    insertRaw("bad-null-0001", "COMPLETED", "NULL", null, null, null, null, "NOW(3)"));
            assertThat(rowCount()).isZero();
        }

        /** 리뷰 B. seat_count >= 1 이 UNKNOWN 이 되어 성공 분기가 통과했다. */
        @Test
        void B_성공인데_좌석_수만_NULL_이면_거부한다() {
            assertRejectedByShape(() ->
                    insertRaw("bad-null-0002", "COMPLETED", "NULL", "SUCCEEDED", HOLD, FIXTURE_SALE_EVENT_ID, null,
                            "NOW(3)"));
            assertThat(rowCount()).isZero();
        }

        /** 같은 원인의 다른 조합 — 결과 종류만 NULL 이고 성공 필드는 모두 있다. */
        @Test
        void 완료인데_결과_종류만_NULL_이고_성공_필드가_있으면_거부한다() {
            assertRejectedByShape(() ->
                    insertRaw("bad-null-0003", "COMPLETED", "NULL", null, HOLD, FIXTURE_SALE_EVENT_ID, 2, "NOW(3)"));
        }

        @Test
        void 완료인데_결과_종류만_NULL_이고_좌석_수만_있으면_거부한다() {
            assertRejectedByShape(() ->
                    insertRaw("bad-null-0004", "COMPLETED", "NULL", null, null, null, 2, "NOW(3)"));
        }

        @Test
        void 성공인데_회차가_NULL_이면_거부한다() {
            assertRejectedByShape(() ->
                    insertRaw("bad-null-0005", "COMPLETED", "NULL", "SUCCEEDED", HOLD, null, 2, "NOW(3)"));
        }

        @Test
        void 성공인데_성공_필드가_모두_NULL_이면_거부한다() {
            assertRejectedByShape(() ->
                    insertRaw("bad-null-0006", "COMPLETED", "NULL", "SUCCEEDED", null, null, null, "NOW(3)"));
        }

        @Test
        void 성공인데_좌석_수가_0_이면_거부한다() {
            assertRejectedByShape(() ->
                    insertRaw("bad-null-0007", "COMPLETED", "NULL", "SUCCEEDED", HOLD, FIXTURE_SALE_EVENT_ID, 0,
                            "NOW(3)"));
        }

        @Test
        void 완료인데_완료_시각이_NULL_이면_거부한다() {
            assertRejectedByShape(() ->
                    insertRaw("bad-null-0008", "COMPLETED", "NULL", "SEAT_UNAVAILABLE", null, null, null, "NULL"));
        }

        @Test
        void 진행_중인데_리스가_NULL_이면_거부한다() {
            assertRejectedByShape(() ->
                    insertRaw("bad-null-0009", "IN_PROGRESS", "NULL", null, null, null, null, "NULL"));
        }

        @Test
        void 진행_중인데_좌석_수만_있으면_거부한다() {
            assertRejectedByShape(() ->
                    insertRaw("bad-null-0010", "IN_PROGRESS", "NOW(3)", null, null, null, 2, "NULL"));
        }

        @Test
        void 업무_실패인데_좌석_수가_있으면_거부한다() {
            assertRejectedByShape(() ->
                    insertRaw("bad-null-0011", "COMPLETED", "NULL", "QUOTA_EXCEEDED", null, null, 2, "NOW(3)"));
        }
    }

    // ------------------------------------------------------------------ §2 UPDATE

    @Nested
    @DisplayName("§2 ★ UPDATE — 정상 행을 잘못된 완료 상태로 바꾸지 못한다")
    class 갱신 {

        @Test
        void 진행_중_행을_결과_종류_없는_완료로_바꾸지_못한다() {
            insertInProgress("upd-key-0001");
            Map<String, Object> before = row("upd-key-0001");

            assertRejectedByShape(() -> jdbc().update("""
                    UPDATE idempotency_key
                       SET state = 'COMPLETED', lease_until = NULL, completed_at = NOW(3)
                     WHERE idempotency_key = 'upd-key-0001'
                    """));
            assertThat(row("upd-key-0001")).as("행이 그대로다").isEqualTo(before);
        }

        @Test
        void 진행_중_행을_좌석_수_없는_성공으로_바꾸지_못한다() {
            insertInProgress("upd-key-0002");
            Map<String, Object> before = row("upd-key-0002");

            assertRejectedByShape(() -> jdbc().update("""
                    UPDATE idempotency_key
                       SET state = 'COMPLETED', lease_until = NULL, completed_at = NOW(3),
                           result_type = 'SUCCEEDED', hold_id = ?, sale_event_id = ?
                     WHERE idempotency_key = 'upd-key-0002'
                    """, HOLD, FIXTURE_SALE_EVENT_ID));
            assertThat(row("upd-key-0002")).isEqualTo(before);
        }

        @Test
        void 성공_행의_좌석_수를_NULL_로_지우지_못한다() {
            insertSucceeded("upd-key-0003");
            Map<String, Object> before = row("upd-key-0003");

            assertRejectedByShape(() -> jdbc().update(
                    "UPDATE idempotency_key SET seat_count = NULL WHERE idempotency_key = 'upd-key-0003'"));
            assertThat(row("upd-key-0003")).isEqualTo(before);
        }

        @Test
        void 업무_실패_행의_결과_종류를_NULL_로_지우지_못한다() {
            insertRejected("upd-key-0004", HoldRejectionCode.SEAT_UNAVAILABLE);
            Map<String, Object> before = row("upd-key-0004");

            assertRejectedByShape(() -> jdbc().update(
                    "UPDATE idempotency_key SET result_type = NULL WHERE idempotency_key = 'upd-key-0004'"));
            assertThat(row("upd-key-0004")).isEqualTo(before);
        }

        @Test
        void 성공_행의_결과_종류를_NULL_로_지우지_못한다() {
            insertSucceeded("upd-key-0005");
            Map<String, Object> before = row("upd-key-0005");

            assertRejectedByShape(() -> jdbc().update(
                    "UPDATE idempotency_key SET result_type = NULL WHERE idempotency_key = 'upd-key-0005'"));
            assertThat(row("upd-key-0005")).isEqualTo(before);
        }
    }

    // ------------------------------------------------------------------ §3 정상 행

    @Nested
    @DisplayName("§3 정상 행은 계속 허용되고 저장소가 복원한다")
    class 정상_행 {

        @Test
        void 진행_중_행은_허용되고_결과_없이_복원된다() {
            assertThatCode(() -> insertInProgress("ok-key-0001")).doesNotThrowAnyException();

            IdempotencyRecord record = keys.find(scope("ok-key-0001")).orElseThrow();
            assertThat(record.state()).isEqualTo(IdempotencyKeyState.IN_PROGRESS);
            assertThat(record.outcome()).isEmpty();
        }

        @Test
        void 성공_결과는_허용되고_그대로_복원된다() {
            assertThatCode(() -> insertSucceeded("ok-key-0002")).doesNotThrowAnyException();

            assertThat(keys.find(scope("ok-key-0002")).orElseThrow().outcome()).contains(
                    new Succeeded(HoldId.of(HOLD), new SaleEventId(FIXTURE_SALE_EVENT_ID), 2));
        }

        @ParameterizedTest
        @EnumSource(HoldRejectionCode.class)
        void 업무_실패_3종은_허용되고_그대로_복원된다(HoldRejectionCode code) {
            String key = "ok-rej-" + code.ordinal() + "-0001";
            assertThatCode(() -> insertRejected(key, code)).doesNotThrowAnyException();

            assertThat(keys.find(scope(key)).orElseThrow().outcome()).contains(new Rejected(code));
        }

        @Test
        void 업무_실패_종류는_3개다() {
            assertThat(HoldRejectionCode.values()).hasSize(3);
        }
    }
}
