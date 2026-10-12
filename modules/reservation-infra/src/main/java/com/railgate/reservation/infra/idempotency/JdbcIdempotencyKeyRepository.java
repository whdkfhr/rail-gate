package com.railgate.reservation.infra.idempotency;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.idempotency.HoldRejectionCode;
import com.railgate.reservation.idempotency.IdempotencyKeyPort;
import com.railgate.reservation.idempotency.IdempotencyKeyState;
import com.railgate.reservation.idempotency.IdempotencyRecord;
import com.railgate.reservation.idempotency.IdempotencyScope;
import com.railgate.reservation.idempotency.KeyOwnership;
import com.railgate.reservation.idempotency.KeyWriteOutcome;
import com.railgate.reservation.idempotency.RequestFingerprint;
import com.railgate.reservation.idempotency.StoredHoldOutcome;
import com.railgate.reservation.idempotency.StoredHoldOutcome.Rejected;
import com.railgate.reservation.idempotency.StoredHoldOutcome.Succeeded;
import com.railgate.reservation.saleevent.SaleEventId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

/**
 * 멱등키 저장소 (Task 2I-B). 테이블은 {@code V7__idempotency_key.sql}.
 *
 * <h2>모든 변경은 조건부 UPDATE 한 문장이다</h2>
 *
 * <table>
 *   <caption>소유권 조건</caption>
 *   <tr><th>연산</th><th>WHERE (키 범위에 더해)</th><th>SET</th></tr>
 *   <tr><td>인수</td><td>{@code state='IN_PROGRESS' AND generation=:seen AND fence=:seen AND lease_until <= NOW(3)}</td>
 *       <td>{@code fence+1}, 새 리스</td></tr>
 *   <tr><td>성공 기록</td><td>{@code state='IN_PROGRESS' AND generation=:mine AND fence=:mine}</td>
 *       <td>{@code COMPLETED}, 선점 결과, {@code completed_at=NOW(3)}</td></tr>
 *   <tr><td>실패 기록</td><td>위와 같다</td><td>{@code COMPLETED}, 실패 코드</td></tr>
 *   <tr><td>포기</td><td>위와 같다</td><td>{@code lease_until=NOW(3)}</td></tr>
 * </table>
 *
 * <p>성공은 {@code affected_rows} 로만 판정한다. 앞서 SELECT 로 소유권을 확인하지 않는다 — 확인과 변경
 * 사이의 창(TOCTOU)이 생긴다. 조건이 같은 행에 걸리므로 경쟁하는 쓰기는 InnoDB 행 잠금으로 직렬화된다.
 *
 * <p><b>{@code affected_rows} 의 의미.</b> MySQL Connector/J 의 기본값({@code useAffectedRows=false})은
 * {@code CLIENT_FOUND_ROWS} 를 켜서 "실제로 바뀐 행" 이 아니라 <b>조건에 일치한 행 수</b>를 돌려준다.
 * 조건이 소유권 전체(키 범위·세대·fence·상태)를 담으므로 일치는 곧 "현재 소유자였다" 이다.
 * 포기의 {@code lease_until = NOW(3)} 처럼 값이 그대로일 수 있는 SET 도 이 설정에서는 1 이다.
 * 드라이버 설정을 바꾸면 이 판정의 의미가 달라진다 — 이 프로젝트는 기본값을 쓴다.
 *
 * <h2>트랜잭션은 호출자가 소유한다</h2>
 *
 * <p>변경 연산은 이 {@link DataSource} 의 JDBC 트랜잭션에 실제로 참여할 때만 실행하고, 아니면 SQL 전에
 * {@link IllegalStateException} 으로 거부한다. 판정 방식은 {@code JdbcUserHoldQuotaRepository} 와 같다 —
 * {@code PROPAGATION_MANDATORY} 로 "이 리소스에 활성 트랜잭션이 있는가" 를 묻는다. 그 근거(다른 DataSource 의
 * 트랜잭션, 동기화 목적 바인딩, {@code autoCommit=false} 풀이 왜 참여가 아닌가)는 그 클래스에 있다.
 * 이 저장소는 독립 커밋을 만들지 않으므로 호출자가 롤백하면 키 변경도 함께 롤백된다.
 *
 * <p>{@link #find} 는 예외다. 읽기는 트랜잭션 밖에서도 부를 수 있다 (포트 문서).
 *
 * <h2>중복 INSERT 와 장애의 구분</h2>
 *
 * <p>{@link DuplicateKeyException}(MySQL 1062 의 Spring 번역)만 "이미 있음" 으로 바꾼다. 잠금 대기 초과,
 * 연결 장애, CHECK 위반 등은 그대로 던진다. <b>중복 뒤 같은 트랜잭션의 상태는 보장하지 않는다</b> —
 * MySQL InnoDB 는 중복 키 오류에서 문장만 롤백하지만, 그것을 모든 DB·드라이버·트랜잭션 관리자 구성의
 * 법칙으로 삼지 않는다. 계약은 호출자가 그 트랜잭션(TX0)을 끝내고 새 트랜잭션에서 조회하는 것이다.
 *
 * <h2>시간</h2>
 *
 * <p>리스 판정·완료 시각은 DB 의 {@code NOW(3)} 다 (규칙 7). 열은 {@code DATETIME(3)} 이므로 리스는 밀리초
 * 단위만 받는다. 리스 길이는 호출자가 매번 넘긴다 — 이 클래스에 정책 값이 없다 (ADR-009 C4 미결정).
 */
public class JdbcIdempotencyKeyRepository implements IdempotencyKeyPort {

    private static final String RESULT_SUCCEEDED = "SUCCEEDED";

    private static final String INSERT_SQL = """
            INSERT INTO idempotency_key
                (user_id, operation, idempotency_key, request_fingerprint, state, generation, fence, lease_until)
            VALUES (?, ?, ?, ?, 'IN_PROGRESS', ?, 1, NOW(3) + INTERVAL ? MICROSECOND)
            """;

    /**
     * {@code lease_expired} 는 DB 가 계산한다. 완료 행은 {@code lease_until} 이 NULL 이라
     * {@code IS NOT NULL} 조건 때문에 0 이 된다.
     */
    private static final String FIND_SQL = """
            SELECT request_fingerprint, state, generation, fence,
                   (lease_until IS NOT NULL AND lease_until <= NOW(3)) AS lease_expired,
                   result_type, hold_id, sale_event_id, seat_count
              FROM idempotency_key
             WHERE user_id = ? AND operation = ? AND idempotency_key = ?
            """;

    private static final String TAKE_OVER_SQL = """
            UPDATE idempotency_key
               SET fence = fence + 1,
                   lease_until = NOW(3) + INTERVAL ? MICROSECOND
             WHERE user_id = ? AND operation = ? AND idempotency_key = ?
               AND state = 'IN_PROGRESS'
               AND generation = ?
               AND fence = ?
               AND lease_until <= NOW(3)
            """;

    private static final String COMPLETE_SUCCEEDED_SQL = """
            UPDATE idempotency_key
               SET state = 'COMPLETED', result_type = 'SUCCEEDED',
                   hold_id = ?, sale_event_id = ?, seat_count = ?,
                   lease_until = NULL, completed_at = NOW(3)
             WHERE user_id = ? AND operation = ? AND idempotency_key = ?
               AND state = 'IN_PROGRESS'
               AND generation = ?
               AND fence = ?
            """;

    private static final String COMPLETE_REJECTED_SQL = """
            UPDATE idempotency_key
               SET state = 'COMPLETED', result_type = ?,
                   lease_until = NULL, completed_at = NOW(3)
             WHERE user_id = ? AND operation = ? AND idempotency_key = ?
               AND state = 'IN_PROGRESS'
               AND generation = ?
               AND fence = ?
            """;

    private static final String ABANDON_SQL = """
            UPDATE idempotency_key
               SET lease_until = NOW(3)
             WHERE user_id = ? AND operation = ? AND idempotency_key = ?
               AND state = 'IN_PROGRESS'
               AND generation = ?
               AND fence = ?
            """;

    private static final TransactionDefinition PARTICIPATION_PROBE = createProbeDefinition();

    private final JdbcTemplate jdbc;

    /** 참여 여부만 묻는 관리자. 이 저장소의 DataSource 로 직접 만들어 리소스가 어긋날 수 없게 한다. */
    private final PlatformTransactionManager participationProbe;

    public JdbcIdempotencyKeyRepository(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.jdbc = new JdbcTemplate(dataSource);
        this.participationProbe = new DataSourceTransactionManager(dataSource);
    }

    private static TransactionDefinition createProbeDefinition() {
        DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
        definition.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
        definition.setName("idempotency-key-participation-probe");
        return definition;
    }

    @Override
    public Optional<KeyOwnership> tryInsert(IdempotencyScope scope, RequestFingerprint fingerprint, Duration lease) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(fingerprint, "fingerprint");
        long leaseMicros = leaseMicros(lease);
        requireEnlistedTransaction("tryInsert");

        KeyOwnership owned = KeyOwnership.newGeneration();
        try {
            jdbc.update(INSERT_SQL, scope.userId().value(), scope.operation().name(), scope.key().value(),
                    fingerprint.value(), owned.generation().toString(), leaseMicros);
        } catch (DuplicateKeyException alreadyExists) {
            return Optional.empty();
        }
        return Optional.of(owned);
    }

    @Override
    public Optional<IdempotencyRecord> find(IdempotencyScope scope) {
        Objects.requireNonNull(scope, "scope");
        List<IdempotencyRecord> rows = jdbc.query(FIND_SQL, JdbcIdempotencyKeyRepository::mapRecord,
                scope.userId().value(), scope.operation().name(), scope.key().value());
        return rows.stream().findFirst();
    }

    @Override
    public Optional<KeyOwnership> takeOver(IdempotencyScope scope, KeyOwnership seen, Duration lease) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(seen, "seen");
        long leaseMicros = leaseMicros(lease);
        requireEnlistedTransaction("takeOver");

        int affected = jdbc.update(TAKE_OVER_SQL, leaseMicros,
                scope.userId().value(), scope.operation().name(), scope.key().value(),
                seen.generation().toString(), seen.fence());
        return affected == 1 ? Optional.of(seen.next()) : Optional.empty();
    }

    @Override
    public KeyWriteOutcome completeSucceeded(IdempotencyScope scope, KeyOwnership mine, Succeeded outcome) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(mine, "mine");
        Objects.requireNonNull(outcome, "outcome");
        requireEnlistedTransaction("completeSucceeded");

        return toOutcome(jdbc.update(COMPLETE_SUCCEEDED_SQL,
                outcome.holdId().asString(), outcome.saleEventId().value(), outcome.seatCount(),
                scope.userId().value(), scope.operation().name(), scope.key().value(),
                mine.generation().toString(), mine.fence()));
    }

    @Override
    public KeyWriteOutcome completeRejected(IdempotencyScope scope, KeyOwnership mine, Rejected outcome) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(mine, "mine");
        Objects.requireNonNull(outcome, "outcome");
        requireEnlistedTransaction("completeRejected");

        return toOutcome(jdbc.update(COMPLETE_REJECTED_SQL, outcome.code().name(),
                scope.userId().value(), scope.operation().name(), scope.key().value(),
                mine.generation().toString(), mine.fence()));
    }

    @Override
    public KeyWriteOutcome abandon(IdempotencyScope scope, KeyOwnership mine) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(mine, "mine");
        requireEnlistedTransaction("abandon");

        return toOutcome(jdbc.update(ABANDON_SQL,
                scope.userId().value(), scope.operation().name(), scope.key().value(),
                mine.generation().toString(), mine.fence()));
    }

    // ------------------------------------------------------------------

    private static KeyWriteOutcome toOutcome(int affectedRows) {
        return affectedRows == 1 ? KeyWriteOutcome.APPLIED : KeyWriteOutcome.NOT_OWNER;
    }

    /** 리스는 1ms 이상, 밀리초 단위. {@code DATETIME(3)} 이 담지 못하는 정밀도를 조용히 반올림하지 않는다. */
    private static long leaseMicros(Duration lease) {
        Objects.requireNonNull(lease, "lease");
        if (lease.compareTo(Duration.ofMillis(1)) < 0 || lease.toNanosPart() % 1_000_000 != 0) {
            throw new IllegalArgumentException("lease 는 1ms 이상, 밀리초 단위여야 한다: " + lease);
        }
        return lease.toMillis() * 1_000;
    }

    /**
     * 이 DataSource 의 JDBC 트랜잭션에 실제로 참여하는지 SQL 전에 확인한다.
     * 판정과 정리 방식은 {@code JdbcUserHoldQuotaRepository#requireEnlistedTransaction} 과 같다.
     */
    private void requireEnlistedTransaction(String operation) {
        TransactionStatus participation;
        try {
            participation = participationProbe.getTransaction(PARTICIPATION_PROBE);
        } catch (IllegalTransactionStateException e) {
            throw new IllegalStateException(
                    ("%s 는 이 저장소의 DataSource 에 열린 트랜잭션 안에서만 호출할 수 있다. "
                            + "트랜잭션 밖의 키 변경은 호출자의 롤백과 무관하게 남아, 롤백된 본 작업의 결과처럼 "
                            + "재생되거나 소유권을 잘못 넘긴다").formatted(operation), e);
        }
        if (participation.isRollbackOnly()) {
            participationProbe.rollback(participation);
        } else {
            participationProbe.commit(participation);
        }
    }

    private static IdempotencyRecord mapRecord(ResultSet rs, int rowNum) throws SQLException {
        IdempotencyKeyState state = IdempotencyKeyState.valueOf(rs.getString("state"));
        Optional<StoredHoldOutcome> outcome = Optional.empty();
        if (state == IdempotencyKeyState.COMPLETED) {
            String type = rs.getString("result_type");
            outcome = Optional.of(RESULT_SUCCEEDED.equals(type)
                    ? new Succeeded(HoldId.of(rs.getString("hold_id")),
                            new SaleEventId(rs.getLong("sale_event_id")), rs.getInt("seat_count"))
                    : new Rejected(HoldRejectionCode.valueOf(type)));
        }
        return new IdempotencyRecord(
                new RequestFingerprint(rs.getString("request_fingerprint")),
                state,
                new KeyOwnership(UUID.fromString(rs.getString("generation")), rs.getLong("fence")),
                rs.getBoolean("lease_expired"),
                outcome);
    }
}
