package com.railgate.reservation.infra.quota;

import static org.assertj.core.api.Assertions.assertThat;

import com.railgate.reservation.UserId;
import com.railgate.reservation.infra.MySqlTestSupport;
import com.railgate.reservation.saleevent.SaleEventId;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ★ {@code lockRow} 가 실제로 어떤 잠금을 잡는가 (Task 2G-G 리뷰 보완).
 *
 * <h2>왜 이 테스트가 필요한가</h2>
 *
 * <p>이전 문서에 <b>"PK 전체를 등호로 지정하므로 REPEATABLE READ 에서 일반적으로 갭 잠금이
 * 생기지 않는다"</b> 고 적혀 있었다. <b>측정하지 않은 추측이었고, 틀렸다.</b>
 * 없는 키를 조회하면 InnoDB 는 다음 레코드 앞의 <b>갭에 잠금을 잡는다.</b>
 *
 * <p>그래서 {@code lockRow} 의 빈 반환값은 <b>"일치하는 행이 없다"</b> 는 뜻이지
 * <b>"잠금이 없다"</b> 는 뜻이 아니다. 이 테스트가 그 사실을 운영 코드로 확인한다.
 *
 * <h2>관측 방법</h2>
 *
 * <p>{@code performance_schema.data_locks} 를 <b>잠금이 유지되는 동안</b> 별도 커넥션에서 읽는다.
 * 다른 잠금을 잘못 잡아내지 않도록 <b>대상 테이블·인덱스·해당 트랜잭션의 스레드</b>로 좁힌다.
 *
 * <p>격리 수준은 <b>REPEATABLE READ 로 명시</b>한다. 갭 잠금 여부가 격리 수준에 좌우되므로
 * MySQL 기본값에 기대지 않는다. <b>이 테스트 때문에 운영 잠금 정책이나 격리 수준을 바꾸지 않는다.</b>
 */
@Timeout(300)
@DisplayName("lockRow 의 잠금 관측 — 있는 키와 없는 키 (Task 2G-G)")
class QuotaLockRowGapLockTest extends MySqlTestSupport {

    private static final SaleEventId CHUSEOK = new SaleEventId(9001L);

    /** 존재하는 카운터의 사용자. */
    private static final UserId PRESENT = new UserId(30L);
    /** 존재하지 <b>않는</b> 카운터의 사용자. {@link #PRESENT} 보다 앞이라 그 앞의 갭을 건드린다. */
    private static final UserId ABSENT_BEFORE = new UserId(20L);

    private JdbcUserHoldQuotaRepository quota;

    @BeforeEach
    void setUp() {
        quota = new JdbcUserHoldQuotaRepository(dataSource());

        jdbc().update("""
                INSERT INTO sale_event (id, name, opens_at, status)
                VALUES (?, '2G-G 잠금 관측', DATE_SUB(NOW(3), INTERVAL 1 DAY), 'OPEN')
                """, CHUSEOK.value());
        jdbc().update("""
                INSERT INTO user_hold_quota (sale_event_id, user_id, held_seats)
                VALUES (?, ?, 1)
                """, CHUSEOK.value(), PRESENT.value());
    }

    /** 한 건의 행 잠금 관측값. */
    private record ObservedLock(String lockType, String lockMode, String lockData, String indexName) {
        @Override
        public String toString() {
            return "%s/%s on %s [%s]".formatted(lockType, lockMode, indexName, lockData);
        }

        /**
         * {@code LOCK_MODE} 에 <b>{@code GAP} 플래그가 명시</b>돼 있는가.
         *
         * <p><b>{@code contains("GAP")} 을 쓰면 안 된다.</b> {@code X,REC_NOT_GAP} 도
         * 참이 되어 "갭 잠금이 아니다" 를 "갭 잠금이다" 로 뒤집는다.
         * {@code LOCK_MODE} 는 쉼표로 구분된 플래그 목록이므로 <b>토큰으로 비교</b>한다.
         *
         * <p>이 판별의 범위는 <b>{@code GAP} 플래그 하나</b>다.
         * {@code INSERT_INTENTION}·{@code NEXT KEY} 등 다른 유형까지 다루는 일반 파서를
         * 만들지 않는다 — 이 테스트가 묻는 것은 "갭이 잠겼는가" 뿐이다.
         */
        boolean hasExplicitGapFlag() {
            return Arrays.stream(lockMode.split(","))
                    .map(String::trim)
                    .anyMatch("GAP"::equals);
        }

        /** {@code LOCK_DATA} 를 정규화한 전체 키. 관측값은 {@code "9001, 30"} 형태다. */
        String normalizedKey() {
            return Arrays.stream(lockData.split(","))
                    .map(String::trim)
                    .reduce((a, b) -> a + "," + b)
                    .orElse("");
        }

        boolean isOnKey(long saleEventId, long userId) {
            return normalizedKey().equals(saleEventId + "," + userId);
        }
    }

    /** 관측값 비교에 쓸 전체 키 문자열. */
    private static String fullKey(SaleEventId saleEventId, UserId userId) {
        return saleEventId.value() + "," + userId.value();
    }

    /**
     * 지금 이 커넥션(스레드)이 {@code user_hold_quota} 에 잡고 있는 <b>레코드 잠금</b>을 읽는다.
     *
     * <p>테이블·잠금 종류·스레드로 좁힌다. 스레드로 좁히지 않으면 같은 컨테이너의 다른
     * 테스트나 커넥션이 잡은 잠금을 잘못 집어올 수 있다.
     */
    private static List<ObservedLock> observeRecordLocks(long processId) {
        List<ObservedLock> locks = new ArrayList<>();
        try (Connection root = DriverManager.getConnection(jdbcUrl(), "root", rootPassword());
                PreparedStatement statement = root.prepareStatement("""
                        SELECT dl.LOCK_TYPE, dl.LOCK_MODE, dl.LOCK_DATA, dl.INDEX_NAME
                          FROM performance_schema.data_locks dl
                          JOIN performance_schema.threads t ON t.THREAD_ID = dl.THREAD_ID
                         WHERE dl.OBJECT_NAME = 'user_hold_quota'
                           AND dl.LOCK_TYPE = 'RECORD'
                           AND t.PROCESSLIST_ID = ?
                         ORDER BY dl.LOCK_DATA
                        """)) {
            statement.setLong(1, processId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    locks.add(new ObservedLock(
                            rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("잠금 관측 실패", e);
        }
        return locks;
    }

    /** REPEATABLE READ 로 명시한 트랜잭션 안에서 잠금을 잡고, 그 상태로 관측한다. */
    private List<ObservedLock> lockAndObserve(SaleEventId saleEventId, UserId userId,
            java.util.function.Consumer<java.util.Optional<Integer>> resultCheck) {
        TransactionTemplate template = newTransactionTemplate();
        template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);

        return template.execute(status -> {
            long processId = jdbc().queryForObject("SELECT CONNECTION_ID()", Long.class);

            resultCheck.accept(quota.lockRow(saleEventId, userId));

            // 트랜잭션이 아직 열려 있으므로 잠금이 유지된 상태에서 관측한다.
            return observeRecordLocks(processId);
        });
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("§1 존재하는 키")
    class 존재하는_키 {

        @Test
        void 값을_돌려주고_그_레코드에_잠금을_잡는다() {
            List<ObservedLock> locks = lockAndObserve(CHUSEOK, PRESENT,
                    result -> assertThat(result).contains(1));

            System.out.printf("[Task 2G-G] 있는 키 (%d,%d) 잠금 = %s%n",
                    CHUSEOK.value(), PRESENT.value(), locks);

            assertThat(locks)
                    .as("존재하는 행에는 레코드 잠금이 잡힌다")
                    .singleElement()
                    .satisfies(lock -> {
                        assertThat(lock.indexName()).isEqualTo("PRIMARY");
                        assertThat(lock.normalizedKey())
                                .as("전체 키로 비교한다 — 부분 문자열 비교는 다른 키도 통과시킨다")
                                .isEqualTo(fullKey(CHUSEOK, PRESENT));
                        assertThat(lock.lockMode()).isEqualTo("X,REC_NOT_GAP");
                        assertThat(lock.hasExplicitGapFlag())
                                .as("★ REC_NOT_GAP 은 갭 잠금이 아니다")
                                .isFalse();
                    });
        }
    }

    /**
     * ★ {@code GAP} 플래그 판별 자체를 고정한다.
     *
     * <p>{@code contains("GAP")} 이면 {@code X,REC_NOT_GAP} 까지 참이 되어 결론이 뒤집힌다.
     */
    @Nested
    @DisplayName("§0 ★ GAP 플래그 판별")
    class GAP_플래그_판별 {

        private static ObservedLock withMode(String lockMode) {
            return new ObservedLock("RECORD", lockMode, "9001, 30", "PRIMARY");
        }

        @Test
        void X_GAP_은_갭_잠금이다() {
            assertThat(withMode("X,GAP").hasExplicitGapFlag()).isTrue();
        }

        @Test
        void X_REC_NOT_GAP_은_갭_잠금이_아니다() {
            assertThat(withMode("X,REC_NOT_GAP").hasExplicitGapFlag())
                    .as("★ contains(\"GAP\") 이면 여기서 true 가 되어 판정이 뒤집힌다")
                    .isFalse();
        }

        @Test
        void 다른_모드도_토큰으로_구분한다() {
            assertThat(withMode("X").hasExplicitGapFlag()).isFalse();
            assertThat(withMode("S,GAP").hasExplicitGapFlag()).isTrue();
            assertThat(withMode("X,INSERT_INTENTION").hasExplicitGapFlag()).isFalse();
        }

        @Test
        void 전체_키로_비교한다() {
            ObservedLock lock = withMode("X,GAP");

            assertThat(lock.isOnKey(9001L, 30L)).isTrue();
            assertThat(lock.isOnKey(9001L, 3L))
                    .as("부분 문자열이면 통과했을 키")
                    .isFalse();
            assertThat(lock.isOnKey(900L, 30L)).isFalse();
        }
    }

    /**
     * ★ §2 존재하지 않는 키 — 여기가 이전 설명이 틀렸던 지점이다.
     */
    @Nested
    @DisplayName("§2 ★ 존재하지 않는 키")
    class 존재하지_않는_키 {

        @Test
        void 빈_값을_돌려주지만_갭_잠금이_생긴다() {
            List<ObservedLock> locks = lockAndObserve(CHUSEOK, ABSENT_BEFORE,
                    result -> assertThat(result)
                            .as("일치하는 행이 없으므로 빈 값이다")
                            .isEmpty());

            System.out.printf("[Task 2G-G] 없는 키 (%d,%d) 잠금 = %s%n",
                    CHUSEOK.value(), ABSENT_BEFORE.value(), locks);

            assertThat(locks)
                    .as("★ 빈 반환값이 '잠금 없음' 을 뜻하지 않는다")
                    .singleElement()
                    .satisfies(lock -> {
                        assertThat(lock.indexName()).isEqualTo("PRIMARY");
                        assertThat(lock.normalizedKey())
                                .as("★ 다음 레코드의 전체 키에 표시된다")
                                .isEqualTo(fullKey(CHUSEOK, PRESENT));
                        assertThat(lock.lockMode()).isEqualTo("X,GAP");
                        assertThat(lock.hasExplicitGapFlag())
                                .as("★ 갭 잠금이다: %s", lock)
                                .isTrue();
                    });
        }

        /** 관측된 갭 잠금은 <b>다음 레코드</b>(존재하는 키) 위에 표시된다. */
        @Test
        void 갭_잠금은_다음_레코드_위에_표시된다() {
            List<ObservedLock> locks = lockAndObserve(CHUSEOK, ABSENT_BEFORE,
                    result -> assertThat(result).isEmpty());

            assertThat(locks)
                    .filteredOn(ObservedLock::hasExplicitGapFlag)
                    .as("갭은 (9001,20) 과 (9001,30) 사이이며 (9001,30) 에 기록된다")
                    .singleElement()
                    .satisfies(lock -> assertThat(lock.isOnKey(CHUSEOK.value(), PRESENT.value()))
                            .as("전체 키 비교: %s", lock)
                            .isTrue());
        }

        /** 잠금이 생겨도 <b>행을 만들지는 않는다.</b> 계약은 그대로다. */
        @Test
        void 잠금이_생겨도_행을_만들지_않는다() {
            lockAndObserve(CHUSEOK, ABSENT_BEFORE, result -> assertThat(result).isEmpty());

            assertThat(jdbc().queryForObject("""
                    SELECT COUNT(*) FROM user_hold_quota
                     WHERE sale_event_id = ? AND user_id = ?
                    """, Long.class, CHUSEOK.value(), ABSENT_BEFORE.value()))
                    .isZero();
        }
    }

    /** 트랜잭션이 끝나면 잠금이 사라진다 — 관측 방법이 신뢰할 만하다는 확인. */
    @Test
    void 트랜잭션이_끝나면_관측되는_잠금이_없다() {
        long[] processId = new long[1];
        TransactionTemplate template = newTransactionTemplate();
        template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        template.executeWithoutResult(status -> {
            processId[0] = jdbc().queryForObject("SELECT CONNECTION_ID()", Long.class);
            quota.lockRow(CHUSEOK, ABSENT_BEFORE);
        });

        assertThat(observeRecordLocks(processId[0]))
                .as("커밋 뒤에는 잠금이 남지 않는다")
                .isEmpty();
    }
}
