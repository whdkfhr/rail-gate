package com.railgate.reservation.application.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.railgate.reservation.UserId;
import com.railgate.reservation.idempotency.IdempotencyKey;
import com.railgate.reservation.idempotency.IdempotencyKeyPort;
import com.railgate.reservation.idempotency.IdempotencyScope;
import com.railgate.reservation.idempotency.KeyOwnership;
import com.railgate.reservation.idempotency.RequestFingerprint;
import com.railgate.reservation.support.MySqlSpringTestSupport;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ★ PR #32 CI 실패의 원인 재현과 대안 비교 — 같은 멱등키의 동시 INSERT 와 MySQL 데드락(1213).
 *
 * <p>조정자가 아니라 <b>저장소·원시 SQL 수준</b>에서 DB 동작을 고정한다. 순서는 "같은 키 행을 쥔 열린 트랜잭션" 과
 * {@code performance_schema.data_lock_waits} 관측으로 맞춘다 — 시간을 재지 않는다.
 *
 * <p>관측(SHOW ENGINE INNODB STATUS, TASK-002J-B §7): 두 INSERT 가 PRIMARY 의 같은 레코드에 {@code S locks rec but not gap}
 * 을 쥐고 {@code X locks rec but not gap} 을 기다리다 하나가 롤백된다.
 */
@DisplayName("PR #32 — 멱등키 동시 INSERT 데드락 재현과 대안 비교 (MySQL)")
class IdempotencyKeyInsertDeadlockReproductionTest extends MySqlSpringTestSupport {

    private static final UserId USER = new UserId(77L);
    private static final RequestFingerprint FP = new RequestFingerprint("d".repeat(64));
    private static final Duration LEASE = Duration.ofSeconds(30);

    @Autowired
    private IdempotencyKeyPort keys;

    @Autowired
    private DataSourceTransactionManager transactionManager;

    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        jdbc().execute("DELETE FROM idempotency_key");
        pool = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    /** 운영 저장소의 INSERT 를 자기 트랜잭션에서 실행하고 결과를 분류한다. */
    private String repositoryInsert(IdempotencyScope scope) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            Optional<KeyOwnership> owned = tx.execute(status -> keys.tryInsert(scope, FP, LEASE));
            return owned != null && owned.isPresent() ? "OWNED" : "DUPLICATE";
        } catch (RuntimeException e) {
            return vendorCode(e);
        }
    }

    /** 원시 SQL INSERT (격리 수준·ON DUPLICATE KEY UPDATE 대안 비교용). */
    private String rawInsert(String key, String isolation, boolean onDuplicateKeyUpdate) {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("SET SESSION TRANSACTION ISOLATION LEVEL " + isolation);
                st.executeUpdate("INSERT INTO idempotency_key (user_id, operation, idempotency_key, request_fingerprint,"
                        + " state, generation, fence, lease_until) VALUES (77, 'HOLD_SEATS', '" + key + "', '"
                        + "d".repeat(64) + "', 'IN_PROGRESS', UUID(), 1, NOW(3) + INTERVAL 30 SECOND)"
                        + (onDuplicateKeyUpdate ? " ON DUPLICATE KEY UPDATE user_id = user_id" : ""));
                c.commit();
                return "OK";
            } catch (SQLException e) {
                c.rollback();
                return "SQL" + e.getErrorCode();
            } finally {
                try (Statement st = c.createStatement()) {
                    st.execute("SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ");
                }
            }
        } catch (SQLException e) {
            return "CONN" + e.getErrorCode();
        }
    }

    private static String vendorCode(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return "SQL" + sql.getErrorCode();
            }
        }
        return failure.getClass().getSimpleName();
    }

    /**
     * 같은 키 행을 쥔 열린 트랜잭션 뒤에서 두 INSERT 를 기다리게 한 뒤 커밋하고, 정렬한 두 결과를 돌려준다.
     *
     * @param delete {@code true} 면 그 행을 지우고, {@code false} 면 {@code SELECT ... FOR UPDATE} 로 잠그기만 한다
     */
    private List<String> twoInsertsBehindHolder(String key, boolean delete, Callable<String> insert) throws Exception {
        try (Connection holder = dataSource().getConnection()) {
            holder.setAutoCommit(false);
            try (Statement st = holder.createStatement()) {
                String where = " WHERE user_id = 77 AND operation = 'HOLD_SEATS' AND idempotency_key = '" + key + "'";
                if (delete) {
                    st.executeUpdate("DELETE FROM idempotency_key" + where);
                } else {
                    st.executeQuery("SELECT * FROM idempotency_key" + where + " FOR UPDATE").close();
                }
            }
            Future<String> a = pool.submit(insert);
            Future<String> b = pool.submit(insert);
            awaitLockWaits(2);
            holder.commit();
            List<String> results = new ArrayList<>(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)));
            results.sort(String::compareTo);
            return results;
        }
    }

    private void seed(String key) {
        assertThat(repositoryInsert(IdempotencyScope.holdSeats(USER, new IdempotencyKey(key)))).isEqualTo("OWNED");
    }

    // ------------------------------------------------------------------ 재현

    /** ★ 같은 키 행을 지우는 트랜잭션이 커밋되면, 뒤에서 기다리던 운영 저장소 INSERT 둘 중 하나가 1213 으로 롤백된다. */
    @Test
    void 지우는_트랜잭션_뒤의_두_INSERT_중_하나는_데드락으로_롤백된다() throws Exception {
        String key = "repro-delete-01";
        seed(key);
        IdempotencyScope scope = IdempotencyScope.holdSeats(USER, new IdempotencyKey(key));

        assertThat(twoInsertsBehindHolder(key, true, () -> repositoryInsert(scope)))
                .containsExactly("OWNED", "SQL1213");
    }

    /** 대조군 — 행이 남아 있으면(지우지 않고 잠그기만 하면) 둘 다 중복으로 끝나고 데드락이 없다. */
    @Test
    void 행이_남아_있으면_두_INSERT_모두_중복이다() throws Exception {
        String key = "repro-locked-01";
        seed(key);
        IdempotencyScope scope = IdempotencyScope.holdSeats(USER, new IdempotencyKey(key));

        assertThat(twoInsertsBehindHolder(key, false, () -> repositoryInsert(scope)))
                .containsExactly("DUPLICATE", "DUPLICATE");
    }

    // ------------------------------------------------------------------ 대안 비교

    /** READ COMMITTED 는 이 데드락을 없애지 못한다 — 간격 잠금이 아니라 레코드 S·X 잠금의 충돌이다. */
    @Test
    void READ_COMMITTED_로_바꿔도_데드락이_난다() throws Exception {
        String key = "alt-rc-0001";
        seed(key);

        assertThat(twoInsertsBehindHolder(key, true, () -> rawInsert(key, "READ COMMITTED", false)))
                .containsExactly("OK", "SQL1213");
    }

    /**
     * {@code INSERT ... ON DUPLICATE KEY UPDATE} 는 같은 시나리오에서 데드락이 없다. 다만 저장소의 "이미 있으면 빈 값" 판정을
     * 영향 행 수로 바꿔야 하고 그 수는 드라이버 설정({@code useAffectedRows})에 따라 다르다 — 채택하지 않았다 (TASK-002J-B §7).
     */
    @Test
    void ON_DUPLICATE_KEY_UPDATE_는_같은_시나리오에서_데드락이_없다() throws Exception {
        String key = "alt-odku-001";
        seed(key);

        assertThat(twoInsertsBehindHolder(key, true, () -> rawInsert(key, "REPEATABLE READ", true)))
                .containsExactly("OK", "OK");
    }

    // ------------------------------------------------------------------ 관측

    private void awaitLockWaits(int count) throws Exception {
        HikariDataSource app = (HikariDataSource) dataSource();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try (Connection root = DriverManager.getConnection(app.getJdbcUrl(), "root", app.getPassword());
             Statement st = root.createStatement()) {
            while (System.nanoTime() < deadline) {
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM performance_schema.data_lock_waits")) {
                    rs.next();
                    if (rs.getLong(1) >= count) {
                        return;
                    }
                }
                Thread.onSpinWait();
            }
        }
        throw new IllegalStateException("잠금 대기가 관측되지 않았다");
    }
}
