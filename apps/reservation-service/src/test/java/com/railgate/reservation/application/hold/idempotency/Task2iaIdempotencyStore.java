package com.railgate.reservation.application.hold.idempotency;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.application.hold.idempotency.StoredHoldOutcome.Rejected;
import com.railgate.reservation.application.hold.idempotency.StoredHoldOutcome.RejectionCode;
import com.railgate.reservation.application.hold.idempotency.StoredHoldOutcome.Succeeded;
import com.railgate.reservation.saleevent.SaleEventId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Task 2I-A 실험용 멱등키 저장소. <b>테스트 전용 테이블 {@value #TABLE} 을 쓴다.</b>
 *
 * <p>운영 migration 이 아니다. 테이블은 테스트가 {@link #createTableIfAbsent} 로 만들고,
 * Flyway 이력과 운영 스키마에는 들어가지 않는다. 이 클래스도 트랜잭션을 열지 않는다 —
 * 호출자({@code Task2ia*IdempotentHold})가 TX0·TX1·TX2 를 연다.
 *
 * <h2>모든 쓰기는 조건부 UPDATE 이고 affected_rows 로 판정한다</h2>
 *
 * <ul>
 *   <li>인수 — {@code state='IN_PROGRESS' AND fence=:seen AND lease_until <= NOW(3)}</li>
 *   <li>완료 기록 — {@code state='IN_PROGRESS' AND fence=:mine}. 0 이면 내가 더 이상 소유자가 아니다</li>
 *   <li>포기(리스 즉시 만료) — {@code state='IN_PROGRESS' AND fence=:mine}</li>
 * </ul>
 *
 * <p>리스 만료 판정은 전부 DB 의 {@code NOW(3)} 이다 (규칙 7).
 */
final class Task2iaIdempotencyStore {

    static final String TABLE = "task2ia_idempotency_key";

    static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS task2ia_idempotency_key (
                user_id             BIGINT       NOT NULL,
                operation           VARCHAR(32)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                idempotency_key     VARCHAR(64)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                request_fingerprint CHAR(64)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                state               VARCHAR(16)  NOT NULL,
                fence               BIGINT       NOT NULL,
                lease_until         DATETIME(3)  NULL,
                result_type         VARCHAR(32)  NULL,
                hold_id             CHAR(36)     NULL,
                sale_event_id       BIGINT       NULL,
                seat_count          INT          NULL,
                created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                completed_at        DATETIME(3)  NULL,
                PRIMARY KEY (user_id, operation, idempotency_key)
            ) ENGINE = InnoDB
            """;

    private static final String INSERT_SQL = """
            INSERT INTO task2ia_idempotency_key
                (user_id, operation, idempotency_key, request_fingerprint, state, fence, lease_until)
            VALUES (?, ?, ?, ?, 'IN_PROGRESS', 1, NOW(3) + INTERVAL ? MICROSECOND)
            """;

    private static final String FIND_SQL = """
            SELECT request_fingerprint, state, fence,
                   (lease_until IS NOT NULL AND lease_until <= NOW(3)) AS lease_expired,
                   result_type, hold_id, sale_event_id, seat_count
              FROM task2ia_idempotency_key
             WHERE user_id = ? AND operation = ? AND idempotency_key = ?
            """;

    private static final String TAKE_OVER_SQL = """
            UPDATE task2ia_idempotency_key
               SET fence = fence + 1,
                   lease_until = NOW(3) + INTERVAL ? MICROSECOND
             WHERE user_id = ? AND operation = ? AND idempotency_key = ?
               AND state = 'IN_PROGRESS'
               AND fence = ?
               AND lease_until <= NOW(3)
            """;

    private static final String COMPLETE_SUCCEEDED_SQL = """
            UPDATE task2ia_idempotency_key
               SET state = 'COMPLETED', result_type = 'SUCCEEDED',
                   hold_id = ?, sale_event_id = ?, seat_count = ?,
                   lease_until = NULL, completed_at = NOW(3)
             WHERE user_id = ? AND operation = ? AND idempotency_key = ?
               AND state = 'IN_PROGRESS'
               AND fence = ?
            """;

    private static final String COMPLETE_REJECTED_SQL = """
            UPDATE task2ia_idempotency_key
               SET state = 'COMPLETED', result_type = ?,
                   lease_until = NULL, completed_at = NOW(3)
             WHERE user_id = ? AND operation = ? AND idempotency_key = ?
               AND state = 'IN_PROGRESS'
               AND fence = ?
            """;

    private static final String ABANDON_SQL = """
            UPDATE task2ia_idempotency_key
               SET lease_until = NOW(3)
             WHERE user_id = ? AND operation = ? AND idempotency_key = ?
               AND state = 'IN_PROGRESS'
               AND fence = ?
            """;

    private final JdbcTemplate jdbc;

    Task2iaIdempotencyStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
    }

    static void createTableIfAbsent(JdbcTemplate jdbc) {
        jdbc.execute(CREATE_TABLE_SQL);
    }

    /**
     * 새 키를 {@code IN_PROGRESS}·fence 1 로 넣는다.
     *
     * <p>이미 있으면 {@link DuplicateKeyException} 을 그대로 던진다 — 호출자의 TX0 가 롤백돼야
     * 하므로 여기서 삼키지 않는다. 같은 키를 동시에 넣으면 뒤의 INSERT 는 앞의 TX0 가 끝날 때까지
     * 중복 검사 잠금에서 기다린다. TX0 는 문장 하나라 대기가 짧다.
     */
    void insert(IdempotencyScope scope, String fingerprint, Duration lease) {
        jdbc.update(INSERT_SQL, scope.userId().value(), scope.operation(), scope.key().value(),
                fingerprint, micros(lease));
    }

    Optional<IdempotencyRecord> find(IdempotencyScope scope) {
        List<IdempotencyRecord> rows = jdbc.query(FIND_SQL, Task2iaIdempotencyStore::mapRecord,
                scope.userId().value(), scope.operation(), scope.key().value());
        return rows.stream().findFirst();
    }

    /** @return 인수했으면 {@code true}. 남이 먼저 인수했거나 완료됐거나 리스가 살아 있으면 {@code false} */
    boolean takeOver(IdempotencyScope scope, long seenFence, Duration lease) {
        return jdbc.update(TAKE_OVER_SQL, micros(lease), scope.userId().value(), scope.operation(),
                scope.key().value(), seenFence) == 1;
    }

    /** @return 영향 행 수. 1 이 아니면 이 fence 는 더 이상 소유자가 아니다 */
    int completeSucceeded(IdempotencyScope scope, long fence, Succeeded outcome) {
        return jdbc.update(COMPLETE_SUCCEEDED_SQL,
                outcome.holdId().asString(), outcome.saleEventId().value(), outcome.seatCount(),
                scope.userId().value(), scope.operation(), scope.key().value(), fence);
    }

    /** @return 영향 행 수. 1 이 아니면 이 fence 는 더 이상 소유자가 아니다 */
    int completeRejected(IdempotencyScope scope, long fence, Rejected outcome) {
        return jdbc.update(COMPLETE_REJECTED_SQL, outcome.code().name(),
                scope.userId().value(), scope.operation(), scope.key().value(), fence);
    }

    /** 리스를 즉시 만료시켜 다음 요청이 바로 인수하게 한다. 완료된 행은 건드리지 않는다. */
    int abandon(IdempotencyScope scope, long fence) {
        return jdbc.update(ABANDON_SQL,
                scope.userId().value(), scope.operation(), scope.key().value(), fence);
    }

    private static long micros(Duration lease) {
        if (lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("lease 는 양수여야 한다: " + lease);
        }
        return lease.toNanos() / 1_000;
    }

    private static IdempotencyRecord mapRecord(ResultSet rs, int rowNum) throws SQLException {
        IdempotencyRecord.State state = IdempotencyRecord.State.valueOf(rs.getString("state"));
        StoredHoldOutcome outcome = null;
        if (state == IdempotencyRecord.State.COMPLETED) {
            String type = rs.getString("result_type");
            outcome = "SUCCEEDED".equals(type)
                    ? new Succeeded(HoldId.of(rs.getString("hold_id")),
                            new SaleEventId(rs.getLong("sale_event_id")), rs.getInt("seat_count"))
                    : new Rejected(RejectionCode.valueOf(type));
        }
        return new IdempotencyRecord(rs.getString("request_fingerprint"), state, rs.getLong("fence"),
                rs.getBoolean("lease_expired"), outcome);
    }
}
