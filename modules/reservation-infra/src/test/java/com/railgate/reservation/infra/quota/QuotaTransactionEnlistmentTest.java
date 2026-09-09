package com.railgate.reservation.infra.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.infra.MySqlTestSupport;
import com.railgate.reservation.infra.seat.JdbcMultiSeatHoldRepository;
import com.railgate.reservation.saleevent.SaleEventId;
import com.railgate.reservation.seat.SeatId;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ★ quota 저장소의 트랜잭션 <b>참여</b> 검증 (Task 2G-G 리뷰 보완).
 *
 * <h2>"트랜잭션이 열려 있다" 로는 부족하다</h2>
 *
 * <p>{@code isActualTransactionActive()} 는 <b>어떤</b> DataSource 위의 트랜잭션이든 참이 된다.
 * 그 상태에서 quota UPDATE 를 보내면 이 저장소의 커넥션은 그 트랜잭션에 참여하지 않고
 * <b>autocommit 으로 확정</b>된다. 호출자가 롤백해도 quota 변경만 남는다 —
 * 잡지도 못한 좌석 때문에 상한이 소진된다.
 *
 * <p>{@code hasResource(key)} 만으로도 부족하다. <b>다른</b> DataSource 의 트랜잭션이 활성인
 * 동안 quota DataSource 로 조회하면 Spring 이 커넥션을 리소스로 <b>바인딩은 하지만</b>
 * 그 커넥션은 여전히 autocommit 이다 (동기화 목적의 바인딩이다).
 * 그래서 <b>실제 JDBC 트랜잭션 참여 여부</b>까지 봐야 한다.
 *
 * <p>{@code JdbcMultiSeatHoldRepository} 가 같은 문제를 리소스 키로 다룬다.
 * 여기서는 그 위에 "바인딩만 된 커넥션" 을 한 겹 더 구분한다.
 */
@Timeout(300)
@DisplayName("quota 저장소의 트랜잭션 참여 검증 (Task 2G-G)")
class QuotaTransactionEnlistmentTest extends MySqlTestSupport {

    private static final SaleEventId CHUSEOK = new SaleEventId(9001L);
    private static final long SCHEDULE = 101L;
    private static final UserId USER_A = new UserId(11L);

    /** 같은 MySQL 을 가리키지만 <b>커넥션 풀이 다른</b> DataSource. 같은 트랜잭션이 아니다. */
    private HikariDataSource otherPool;
    private DataSourceTransactionManager otherManager;

    private JdbcUserHoldQuotaRepository quota;

    @BeforeEach
    void setUp() {
        quota = new JdbcUserHoldQuotaRepository(dataSource());

        otherPool = new HikariDataSource(poolConfig());
        otherManager = new DataSourceTransactionManager(otherPool);

        jdbc().update("""
                INSERT INTO sale_event (id, name, opens_at, status)
                VALUES (?, '2G-G 실험 회차', DATE_SUB(NOW(3), INTERVAL 1 DAY), 'OPEN')
                """, CHUSEOK.value());
        jdbc().update("INSERT INTO train_schedule (id, sale_event_id) VALUES (?, ?)",
                SCHEDULE, CHUSEOK.value());
    }

    @AfterEach
    void closeOtherPool() {
        if (otherPool != null) {
            otherPool.close();
        }
    }

    private static HikariConfig poolConfig() {
        return poolConfig(true);
    }

    private static HikariConfig poolConfig(boolean autoCommit) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl());
        config.setUsername("railgate");
        config.setPassword("railgate");
        config.setAutoCommit(autoCommit);
        config.setMaximumPoolSize(4);
        return config;
    }

    private static long quotaRowCount() {
        return jdbc().queryForObject("SELECT COUNT(*) FROM user_hold_quota", Long.class);
    }

    private static Integer heldSeats() {
        List<Integer> rows = jdbc().queryForList("""
                SELECT held_seats FROM user_hold_quota
                 WHERE sale_event_id = ? AND user_id = ?
                """, Integer.class, CHUSEOK.value(), USER_A.value());
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("§1 트랜잭션 없음")
    class 트랜잭션_없음 {

        @Test
        void 모든_변경과_잠금이_거부된다() {
            assertThatThrownBy(() -> quota.ensureRow(CHUSEOK, USER_A))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> quota.tryAcquire(CHUSEOK, USER_A, 1))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> quota.release(CHUSEOK, USER_A, 1))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> quota.lockRow(CHUSEOK, USER_A))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> quota.lockAll(List.of(new QuotaKey(CHUSEOK, USER_A))))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(quotaRowCount()).isZero();
        }
    }

    /**
     * ★ §2 다른 DataSource 의 트랜잭션만 활성인 경우.
     *
     * <p><b>이것이 리뷰가 지적한 구멍이다.</b> 통과하면 quota 변경이 별도 커넥션에서
     * autocommit 되어, 외부 롤백 뒤에도 남는다.
     */
    @Nested
    @DisplayName("§2 ★ 다른 DataSource 의 트랜잭션")
    class 다른_데이터소스_트랜잭션 {

        @Test
        void 변경과_잠금이_모두_거부된다() {
            new TransactionTemplate(otherManager).executeWithoutResult(status -> {
                assertThatThrownBy(() -> quota.ensureRow(CHUSEOK, USER_A))
                        .isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(() -> quota.tryAcquire(CHUSEOK, USER_A, 1))
                        .isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(() -> quota.release(CHUSEOK, USER_A, 1))
                        .isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(() -> quota.lockRow(CHUSEOK, USER_A))
                        .isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(() -> quota.lockAll(List.of(new QuotaKey(CHUSEOK, USER_A))))
                        .isInstanceOf(IllegalStateException.class);
            });
        }

        /** ★ 거부는 SQL 실행 전이다 — 아무 흔적도 남지 않아야 한다. */
        @Test
        void 거부된_호출은_SQL_을_보내지_않는다() {
            new TransactionTemplate(otherManager).executeWithoutResult(status ->
                    assertThatThrownBy(() -> quota.ensureRow(CHUSEOK, USER_A))
                            .isInstanceOf(IllegalStateException.class));

            assertThat(quotaRowCount())
                    .as("거부됐다면 INSERT 가 나가지 않았어야 한다")
                    .isZero();
        }

        /**
         * ★ 막지 못했을 때 실제로 무슨 일이 나는가.
         *
         * <p>외부 트랜잭션을 롤백해도 quota 변경이 남으면 계약이 깨진 것이다.
         * 이 테스트는 <b>거부가 동작한 결과로</b> 통과한다 — 행이 아예 만들어지지 않는다.
         */
        @Test
        void 외부_롤백_뒤에도_quota_가_남지_않는다() {
            assertThatThrownBy(() -> new TransactionTemplate(otherManager)
                    .executeWithoutResult(status -> {
                        try {
                            quota.ensureRow(CHUSEOK, USER_A);
                            quota.tryAcquire(CHUSEOK, USER_A, 3);
                        } finally {
                            status.setRollbackOnly();
                        }
                    })).isInstanceOf(IllegalStateException.class);

            assertThat(quotaRowCount())
                    .as("★ 별도 커넥션에서 autocommit 됐다면 여기 행이 남는다")
                    .isZero();
        }
    }

    /**
     * ★ §3 바인딩만 된 커넥션 — {@code hasResource} 만으로는 못 잡는다.
     *
     * <p>다른 DataSource 의 트랜잭션이 활성인 동안 quota DataSource 로 <b>먼저 조회</b>하면,
     * Spring 이 그 커넥션을 리소스로 바인딩한다(완료 시점에 반납하려고). 그러나 그 커넥션은
     * <b>여전히 autocommit</b> 이다 — {@code DataSourceTransactionManager.doBegin} 이 부른 적이
     * 없으므로 JDBC 트랜잭션이 시작되지 않았다.
     *
     * <p>그래서 {@code hasResource(key)} 는 참이지만 <b>참여한 것이 아니다.</b>
     */
    @Nested
    @DisplayName("§3 ★ 바인딩만 된 커넥션")
    class 바인딩만_된_커넥션 {

        /** quota DataSource 로 먼저 조회해 커넥션을 리소스로 바인딩시킨다. */
        private void bindQuotaConnectionForSynchronization() {
            new JdbcTemplate(dataSource()).queryForObject("SELECT 1", Integer.class);
        }

        @Test
        void 바인딩됐어도_실제_참여가_아니면_거부된다() {
            new TransactionTemplate(otherManager).executeWithoutResult(status -> {
                bindQuotaConnectionForSynchronization();

                assertThatThrownBy(() -> quota.ensureRow(CHUSEOK, USER_A))
                        .as("★ 바인딩은 됐지만 autocommit 커넥션이다")
                        .isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(() -> quota.tryAcquire(CHUSEOK, USER_A, 1))
                        .isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(() -> quota.lockRow(CHUSEOK, USER_A))
                        .isInstanceOf(IllegalStateException.class);
            });
        }

        @Test
        void 거부된_호출은_SQL_을_보내지_않는다() {
            new TransactionTemplate(otherManager).executeWithoutResult(status -> {
                bindQuotaConnectionForSynchronization();
                assertThatThrownBy(() -> quota.ensureRow(CHUSEOK, USER_A))
                        .isInstanceOf(IllegalStateException.class);
            });

            assertThat(quotaRowCount()).isZero();
        }
    }

    /**
     * ★ §3-B 리뷰가 실제 MySQL 로 재현한 시나리오 — <b>autoCommit=false 인 다른 풀.</b>
     *
     * <p>autoCommit 값으로 참여를 판정하면 이 구성이 <b>그대로 통과한다.</b>
     * 좌석은 커밋되고 quota 는 풀 반납 시 롤백되어 둘이 어긋난다.
     *
     * <p>리뷰가 관측한 결과: {@code acquire=ACQUIRED}, 커밋 후 {@code HELD 좌석 1개},
     * {@code quota 행 0개}.
     */
    @Nested
    @DisplayName("§3-B ★ autoCommit=false 인 다른 풀")
    class autoCommit_false_인_다른_풀 {

        private HikariDataSource quotaPool;
        private JdbcUserHoldQuotaRepository foreignQuota;
        private JdbcMultiSeatHoldRepository holds;
        private DataSourceTransactionManager seatManager;

        @BeforeEach
        void wireForeignPool() {
            // 좌석용 = 기본 풀(autoCommit=true), quota 용 = 별도 풀(autoCommit=false).
            quotaPool = new HikariDataSource(poolConfig(false));
            foreignQuota = new JdbcUserHoldQuotaRepository(quotaPool);
            seatManager = new DataSourceTransactionManager(dataSource());
            holds = new JdbcMultiSeatHoldRepository(
                    dataSource(), seatManager, Duration.ofMinutes(5));
        }

        @AfterEach
        void closeQuotaPool() {
            if (quotaPool != null) {
                quotaPool.close();
            }
        }

        /** quota DataSource 로 먼저 조회해 커넥션을 리소스로 바인딩시킨다. */
        private void bindQuotaConnection() {
            new JdbcTemplate(quotaPool).queryForObject("SELECT 1", Integer.class);
        }

        /** ★ autoCommit=false 여도 실제 참여가 아니면 거부돼야 한다. */
        @Test
        void 바인딩된_autoCommit_false_커넥션도_거부된다() {
            new TransactionTemplate(seatManager).executeWithoutResult(status -> {
                bindQuotaConnection();

                assertThatThrownBy(() -> foreignQuota.ensureRow(CHUSEOK, USER_A))
                        .as("★ autoCommit 값만으로는 참여를 증명하지 못한다")
                        .isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(() -> foreignQuota.tryAcquire(CHUSEOK, USER_A, 1))
                        .isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(() -> foreignQuota.lockRow(CHUSEOK, USER_A))
                        .isInstanceOf(IllegalStateException.class);
            });
        }

        /**
         * ★ 회귀 테스트 — 좌석만 커밋되고 quota 가 사라지는 상황이 차단되는가.
         *
         * <p>가드가 없으면 {@code tryAcquire} 가 {@code ACQUIRED} 를 돌려주고,
         * 좌석은 외부 트랜잭션과 함께 커밋되지만 quota 는 풀 반납 시 롤백된다.
         */
        @Test
        void 좌석만_커밋되고_quota_가_사라지는_상황이_차단된다() {
            SeatId seat = new SeatId(insertAvailableSeat(SCHEDULE, "1A"));

            assertThatThrownBy(() -> new TransactionTemplate(seatManager)
                    .executeWithoutResult(status -> {
                        bindQuotaConnection();
                        foreignQuota.ensureRow(CHUSEOK, USER_A);
                        foreignQuota.tryAcquire(CHUSEOK, USER_A, 1);
                        holds.holdAll(List.of(seat), HoldId.newId(), USER_A);
                    }))
                    .as("★ quota 단계에서 먼저 거부돼야 좌석도 잡히지 않는다")
                    .isInstanceOf(IllegalStateException.class);

            assertThat(jdbc().queryForObject(
                    "SELECT COUNT(*) FROM seat_inventory WHERE status = 'HELD'", Long.class))
                    .as("좌석도 커밋되지 않는다")
                    .isZero();
            assertThat(quotaRowCount()).isZero();
        }

        /**
         * ★ autoCommit=false 풀이라도 <b>그 풀의 관리자가 실제 트랜잭션을 시작했다면</b>
         * 정상 참여한다. 풀 설정이 아니라 참여 여부가 기준이다.
         */
        @Test
        void autoCommit_false_풀의_정상_트랜잭션은_참여한다() {
            DataSourceTransactionManager quotaManager =
                    new DataSourceTransactionManager(quotaPool);

            new TransactionTemplate(quotaManager).executeWithoutResult(status -> {
                foreignQuota.ensureRow(CHUSEOK, USER_A);
                assertThat(foreignQuota.tryAcquire(CHUSEOK, USER_A, 2))
                        .isEqualTo(QuotaAcquireOutcome.ACQUIRED);
            });

            assertThat(heldSeats())
                    .as("풀의 autoCommit 설정과 무관하게 참여했으면 커밋된다")
                    .isEqualTo(2);
        }

        /** autoCommit=false 풀의 트랜잭션도 롤백하면 되돌아간다. */
        @Test
        void autoCommit_false_풀의_롤백도_되돌아간다() {
            DataSourceTransactionManager quotaManager =
                    new DataSourceTransactionManager(quotaPool);

            assertThatThrownBy(() -> new TransactionTemplate(quotaManager)
                    .executeWithoutResult(status -> {
                        foreignQuota.ensureRow(CHUSEOK, USER_A);
                        foreignQuota.tryAcquire(CHUSEOK, USER_A, 2);
                        throw new IllegalStateException("호출자가 롤백시킨다");
                    })).isInstanceOf(IllegalStateException.class);

            assertThat(quotaRowCount()).isZero();
        }
    }

    /**
     * ★ §3-C 거부된 호출이 <b>실제로 SQL 을 보내지 않았는지</b> 직접 관측한다.
     *
     * <p>"quota 행이 0" 은 SQL 미발송의 증거가 아니다 — 나갔는데 롤백돼도 0이고,
     * 조건에 안 맞아 0행이 갱신돼도 0이다. 그래서 나간 문장을 직접 센다.
     */
    @Nested
    @DisplayName("§3-C ★ SQL 미발송 직접 관측")
    class SQL_미발송_관측 {

        private RecordingDataSource recording;
        private JdbcUserHoldQuotaRepository recordedQuota;

        @BeforeEach
        void wireRecording() {
            recording = new RecordingDataSource(dataSource());
            recordedQuota = new JdbcUserHoldQuotaRepository(recording);
        }

        @Test
        void 트랜잭션_없이_호출하면_quota_SQL_이_나가지_않는다() {
            recording.clear();

            assertThatThrownBy(() -> recordedQuota.ensureRow(CHUSEOK, USER_A))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> recordedQuota.tryAcquire(CHUSEOK, USER_A, 1))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> recordedQuota.release(CHUSEOK, USER_A, 1))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> recordedQuota.lockRow(CHUSEOK, USER_A))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> recordedQuota.lockAll(List.of(new QuotaKey(CHUSEOK, USER_A))))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(recording.statementsTouching("user_hold_quota"))
                    .as("거부된 호출은 변경도 잠금도 보내지 않는다: %s", recording.executedSql())
                    .isEmpty();
        }

        /** ★ 사전 바인딩용 SELECT 는 관측 구간에서 제외한다. */
        @Test
        void 바인딩만_된_경우에도_quota_SQL_이_나가지_않는다() {
            new TransactionTemplate(otherManager).executeWithoutResult(status -> {
                // (1) 사전 바인딩 — 이 SELECT 는 관측 대상이 아니다.
                new JdbcTemplate(recording).queryForObject("SELECT 1", Integer.class);
                // (2) 여기서부터가 관측 구간이다.
                recording.clear();

                assertThatThrownBy(() -> recordedQuota.ensureRow(CHUSEOK, USER_A))
                        .isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(() -> recordedQuota.lockRow(CHUSEOK, USER_A))
                        .isInstanceOf(IllegalStateException.class);

                assertThat(recording.executedSql())
                        .as("관측 구간에는 아무 문장도 없어야 한다")
                        .isEmpty();
            });
        }

        /** 반대로 정상 참여이면 실제로 SQL 이 나간다 — 관측 도구가 동작한다는 증거다. */
        @Test
        void 정상_참여이면_quota_SQL_이_나간다() {
            DataSourceTransactionManager recordingManager =
                    new DataSourceTransactionManager(recording);

            new TransactionTemplate(recordingManager).executeWithoutResult(status -> {
                recording.clear();
                recordedQuota.ensureRow(CHUSEOK, USER_A);
                recordedQuota.tryAcquire(CHUSEOK, USER_A, 1);
            });

            assertThat(recording.statementsTouching("user_hold_quota"))
                    .as("관측 도구가 실제로 문장을 잡는다는 확인")
                    .hasSize(2);
            assertThat(heldSeats()).isEqualTo(1);
        }
    }

    /**
     * ★ §5 커밋·롤백 원자성 회귀 — 운영 저장소 두 개를 같은 트랜잭션에서 쓴다.
     */
    @Nested
    @DisplayName("§5 ★ quota 와 좌석의 커밋·롤백 원자성")
    class 커밋_롤백_원자성 {

        private JdbcMultiSeatHoldRepository holds;

        @BeforeEach
        void wireSeatRepository() {
            holds = new JdbcMultiSeatHoldRepository(
                    dataSource(), new DataSourceTransactionManager(dataSource()),
                    Duration.ofMinutes(5));
        }

        private static long heldSeatCount() {
            return jdbc().queryForObject(
                    "SELECT COUNT(*) FROM seat_inventory WHERE status = 'HELD'", Long.class);
        }

        /** A-1 — <b>새 카운터 행</b>을 만든 뒤 롤백하면 행 자체가 사라진다. */
        @Test
        void 새_행_생성과_좌석_선점이_함께_롤백된다() {
            SeatId seat = new SeatId(insertAvailableSeat(SCHEDULE, "1A"));

            assertThatThrownBy(() -> inTransaction(() -> {
                quota.ensureRow(CHUSEOK, USER_A);
                assertThat(quota.tryAcquire(CHUSEOK, USER_A, 1))
                        .as("트랜잭션 안에서는 성공을 돌려준다")
                        .isEqualTo(QuotaAcquireOutcome.ACQUIRED);
                holds.holdAll(List.of(seat), HoldId.newId(), USER_A);
                assertThat(heldSeatCount()).as("트랜잭션 안에서는 좌석도 잡혀 있다").isEqualTo(1);

                throw new IllegalStateException("호출자가 롤백시킨다");
            })).isInstanceOf(IllegalStateException.class);

            assertThat(quotaRowCount()).as("행 확보까지 되돌아간다").isZero();
            assertThat(heldSeatCount()).as("좌석도 함께 되돌아간다").isZero();
        }

        /** A-2 — <b>기존 카운터 증가</b>는 원래 값으로 되돌아간다. 행은 남는다. */
        @Test
        void 기존_카운터_증가와_좌석_선점이_함께_롤백된다() {
            inTransaction(() -> {
                quota.ensureRow(CHUSEOK, USER_A);
                quota.tryAcquire(CHUSEOK, USER_A, 1);
            });
            assertThat(heldSeats()).isEqualTo(1);
            SeatId seat = new SeatId(insertAvailableSeat(SCHEDULE, "2A"));

            assertThatThrownBy(() -> inTransaction(() -> {
                assertThat(quota.tryAcquire(CHUSEOK, USER_A, 2))
                        .isEqualTo(QuotaAcquireOutcome.ACQUIRED);
                holds.holdAll(List.of(seat), HoldId.newId(), USER_A);
                assertThat(heldSeats()).as("트랜잭션 안에서는 3 이다").isEqualTo(3);

                throw new IllegalStateException("호출자가 롤백시킨다");
            })).isInstanceOf(IllegalStateException.class);

            assertThat(heldSeats()).as("★ 원래 값 1 로 되돌아간다").isEqualTo(1);
            assertThat(quotaRowCount()).as("행 자체는 남는다").isEqualTo(1);
            assertThat(heldSeatCount()).isZero();
        }

        /** A-3 — 정상 커밋이면 둘 다 반영된다. */
        @Test
        void 정상_커밋이면_quota_와_좌석이_함께_반영된다() {
            SeatId seat = new SeatId(insertAvailableSeat(SCHEDULE, "1A"));

            inTransaction(() -> {
                quota.ensureRow(CHUSEOK, USER_A);
                assertThat(quota.tryAcquire(CHUSEOK, USER_A, 1))
                        .isEqualTo(QuotaAcquireOutcome.ACQUIRED);
                holds.holdAll(List.of(seat), HoldId.newId(), USER_A);
            });

            assertThat(heldSeats()).isEqualTo(1);
            assertThat(heldSeatCount()).isEqualTo(1);
        }

        /** A-4 — 프록시 구성에서도 롤백이 함께 일어난다. */
        @Test
        void 프록시_구성에서도_함께_롤백된다() {
            DataSource proxied = new TransactionAwareDataSourceProxy(dataSource());
            JdbcUserHoldQuotaRepository proxiedQuota = new JdbcUserHoldQuotaRepository(proxied);
            SeatId seat = new SeatId(insertAvailableSeat(SCHEDULE, "1A"));

            assertThatThrownBy(() -> inTransaction(() -> {
                proxiedQuota.ensureRow(CHUSEOK, USER_A);
                assertThat(proxiedQuota.tryAcquire(CHUSEOK, USER_A, 1))
                        .isEqualTo(QuotaAcquireOutcome.ACQUIRED);
                holds.holdAll(List.of(seat), HoldId.newId(), USER_A);
                throw new IllegalStateException("호출자가 롤백시킨다");
            })).isInstanceOf(IllegalStateException.class);

            assertThat(quotaRowCount()).isZero();
            assertThat(heldSeatCount()).isZero();
        }
    }

    /**
     * ★ §6 rollback-only 상태에서의 참여 확인.
     *
     * <p>가드는 참여를 확인한 뒤 그 상태를 정리한다. 이 절은 <b>정리가 호출자에게
     * 아무 영향도 주지 않는지</b>를 실제 Spring 동작으로 고정한다.
     *
     * <p><b>local 과 global 을 구분한다.</b>
     * <ul>
     *   <li><b>global</b> rollback-only — JDBC 리소스(커넥션 홀더)에 찍힌다.
     *       참여 트랜잭션이 실패하면 {@code globalRollbackOnParticipationFailure}
     *       (기본 {@code true}) 때문에 붙는다. probe 에서 보인다.</li>
     *   <li><b>local</b> rollback-only — 바깥 {@code TransactionStatus} 에만 찍힌다.
     *       {@code status.setRollbackOnly()} 가 그것이다. <b>probe 에서 보이지 않는다.</b></li>
     * </ul>
     *
     * <p>rollback-only 는 전부 실제 Spring 동작으로 만든다. private 필드나 reflection 을
     * 쓰지 않는다.
     */
    @Nested
    @DisplayName("§6 ★ rollback-only 상태에서의 참여 확인")
    class rollback_only_상태 {

        private DataSourceTransactionManager manager;

        @BeforeEach
        void wireManager() {
            manager = new DataSourceTransactionManager(dataSource());
        }

        /** 안쪽 참여 트랜잭션을 실패시켜 바깥을 <b>global</b> rollback-only 로 만든다. */
        private void markGlobalRollbackOnly() {
            try {
                new TransactionTemplate(manager).executeWithoutResult(inner -> {
                    throw new IllegalStateException("안쪽 참여 트랜잭션 실패");
                });
            } catch (IllegalStateException expected) {
                // 바깥 JDBC 리소스가 이제 global rollback-only 다.
            }
        }

        /**
         * ★ global rollback-only 여도 가드는 통과한다.
         *
         * <p>참여 여부와 rollback-only 는 다른 질문이다. 이미 롤백될 트랜잭션이라도
         * <b>참여는 하고 있다.</b> 가드가 여기서 거부하면 진짜 실패 원인이 가려진다.
         */
        @Test
        void global_rollback_only_여도_가드는_통과한다() {
            assertThatThrownBy(() -> inTransaction(() -> {
                markGlobalRollbackOnly();

                assertThatCode(() -> quota.ensureRow(CHUSEOK, USER_A))
                        .as("★ 참여 확인은 통과한다 — rollback-only 는 참여 여부와 별개다")
                        .doesNotThrowAnyException();
                assertThatCode(() -> quota.tryAcquire(CHUSEOK, USER_A, 1))
                        .doesNotThrowAnyException();
            }))
                    .as("★ 예외는 probe 가 아니라 바깥 종료 시점에 난다")
                    .isInstanceOf(UnexpectedRollbackException.class);

            assertThat(quotaRowCount()).as("롤백됐으므로 남지 않는다").isZero();
        }

        /**
         * ★ 가드는 rollback-only 를 <b>지우지 않고</b>, 독립 커밋도 만들지 않는다.
         *
         * <p>호출자가 예외를 던지지 않았는데도 커밋이 실패해야 한다. 가드가 표시를
         * 지웠다면 여기서 정상 커밋되어 quota 가 남았을 것이다.
         */
        @Test
        void 가드는_rollback_only_를_지우지_않는다() {
            assertThatThrownBy(() -> inTransaction(() -> {
                markGlobalRollbackOnly();
                quota.ensureRow(CHUSEOK, USER_A);
                quota.tryAcquire(CHUSEOK, USER_A, 1);
                quota.lockRow(CHUSEOK, USER_A);
                // 호출자는 예외를 던지지 않는다. 그런데도 커밋은 실패해야 한다.
            }))
                    .as("★ 표시가 지워졌다면 정상 커밋됐을 것이다")
                    .isInstanceOf(UnexpectedRollbackException.class);

            assertThat(quotaRowCount()).as("★ 독립 커밋도 없다").isZero();
        }

        /**
         * ★ <b>local</b> rollback-only 는 probe 에 보이지 않는다 — 그래도 무해하다.
         *
         * <p>바깥 {@code status.setRollbackOnly()} 는 바깥 상태 객체에만 찍히므로
         * probe 는 {@code isRollbackOnly() == false} 를 보고 커밋 분기를 탄다.
         * 참여 상태의 커밋은 물리 커넥션을 건드리지 않으므로 바깥 롤백은 그대로 일어난다.
         * 그리고 바깥은 새 트랜잭션이라 <b>예외 없이</b> 조용히 롤백된다 —
         * global 인 경우와 종료 동작이 다르다.
         */
        @Test
        void local_rollback_only_는_조용히_롤백된다() {
            assertThatCode(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                status.setRollbackOnly();

                quota.ensureRow(CHUSEOK, USER_A);
                assertThat(quota.tryAcquire(CHUSEOK, USER_A, 1))
                        .isEqualTo(QuotaAcquireOutcome.ACQUIRED);
            }))
                    .as("★ local 은 UnexpectedRollbackException 을 만들지 않는다")
                    .doesNotThrowAnyException();

            assertThat(quotaRowCount()).as("그래도 롤백된다").isZero();
        }

        /** ★ probe 단계의 예외와 외부 종료 예외를 구분한다. */
        @Test
        void probe_예외와_외부_종료_예외는_다르다() {
            // (1) 참여하지 않은 경우 — probe 단계에서 IllegalStateException.
            assertThatThrownBy(() -> quota.ensureRow(CHUSEOK, USER_A))
                    .isInstanceOf(IllegalStateException.class)
                    .isNotInstanceOf(UnexpectedRollbackException.class);

            // (2) 참여했지만 global rollback-only — 호출은 통과하고 바깥 종료에서 난다.
            assertThatThrownBy(() -> inTransaction(() -> {
                markGlobalRollbackOnly();
                quota.ensureRow(CHUSEOK, USER_A);
            })).isInstanceOf(UnexpectedRollbackException.class);
        }

        /**
         * ★ 롤백 분기가 <b>왜</b> 있는지를 Spring 동작으로 고정한다.
         *
         * <p>운영 저장소의 probe 관리자는 기본 설정({@code failEarly=false})이라
         * 두 분기의 관측 결과가 같다. 관측 결과가 갈리는 조건이 이것이다 —
         * {@code failEarlyOnGlobalRollbackOnly} 가 켜지면 <b>커밋</b>은
         * probe 호출 지점에서 예외를 던지고 <b>롤백</b>은 던지지 않는다.
         * 이 예외는 호출자의 실패이지 quota 확인의 실패가 아니므로,
         * 저장소는 rollback-only 일 때 롤백 분기를 탄다.
         *
         * <p>따라서 이 분기는 <b>기본 설정에서 재현되는 버그를 막는 코드가 아니라</b>,
         * 설정에 의존하지 않기 위한 방어다. 문서와 주석도 그렇게 적는다.
         */
        @Test
        void 롤백_분기가_필요한_조건을_고정한다() {
            DataSourceTransactionManager failEarly = new DataSourceTransactionManager(dataSource());
            failEarly.setFailEarlyOnGlobalRollbackOnly(true);

            DefaultTransactionDefinition probeDefinition = new DefaultTransactionDefinition();
            probeDefinition.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);

            assertThatThrownBy(() -> inTransaction(() -> {
                markGlobalRollbackOnly();

                TransactionStatus commitProbe = failEarly.getTransaction(probeDefinition);
                assertThat(commitProbe.isNewTransaction())
                        .as("참여 상태다 — 새 트랜잭션이 아니다")
                        .isFalse();
                assertThat(commitProbe.isRollbackOnly())
                        .as("★ global 표시는 probe 에 보인다")
                        .isTrue();
                assertThatThrownBy(() -> failEarly.commit(commitProbe))
                        .as("★ 커밋 분기였다면 여기서 예외가 나 원인을 가렸을 것이다")
                        .isInstanceOf(UnexpectedRollbackException.class);

                TransactionStatus rollbackProbe = failEarly.getTransaction(probeDefinition);
                assertThatCode(() -> failEarly.rollback(rollbackProbe))
                        .as("★ 저장소가 실제로 타는 롤백 분기는 예외를 만들지 않는다")
                        .doesNotThrowAnyException();

                assertThatCode(() -> quota.ensureRow(CHUSEOK, USER_A))
                        .as("★ 운영 가드도 조용히 통과한다")
                        .doesNotThrowAnyException();
            })).isInstanceOf(UnexpectedRollbackException.class);

            assertThat(quotaRowCount()).isZero();
        }
    }
    /**
     * §4 정상 참여 — 같은 리소스이면 관리자 인스턴스가 달라도 된다.
     */
    @Nested
    @DisplayName("§4 정상 참여")
    class 정상_참여 {

        @Test
        void 같은_DataSource_의_다른_관리자_인스턴스로도_동작한다() {
            DataSourceTransactionManager another =
                    new DataSourceTransactionManager(dataSource());

            new TransactionTemplate(another).executeWithoutResult(status -> {
                quota.ensureRow(CHUSEOK, USER_A);
                assertThat(quota.tryAcquire(CHUSEOK, USER_A, 2))
                        .isEqualTo(QuotaAcquireOutcome.ACQUIRED);
            });

            assertThat(heldSeats())
                    .as("리소스 동일성이 조건이지 인스턴스 동일성이 아니다")
                    .isEqualTo(2);
        }

        /** {@link TransactionAwareDataSourceProxy} 를 씌운 구성도 참여한다. */
        @Test
        void TransactionAwareDataSourceProxy_구성도_동작한다() {
            DataSource proxied = new TransactionAwareDataSourceProxy(dataSource());
            JdbcUserHoldQuotaRepository proxiedQuota = new JdbcUserHoldQuotaRepository(proxied);

            inTransaction(() -> {
                proxiedQuota.ensureRow(CHUSEOK, USER_A);
                assertThat(proxiedQuota.tryAcquire(CHUSEOK, USER_A, 3))
                        .isEqualTo(QuotaAcquireOutcome.ACQUIRED);
            });

            assertThat(heldSeats()).isEqualTo(3);
        }

        /** ★ 정상 참여이면 외부 롤백이 quota 변경을 되돌린다. */
        @Test
        void 외부_롤백이_quota_변경을_되돌린다() {
            assertThatThrownBy(() -> inTransaction(() -> {
                quota.ensureRow(CHUSEOK, USER_A);
                quota.tryAcquire(CHUSEOK, USER_A, 3);
                throw new IllegalStateException("호출자가 롤백시킨다");
            })).isInstanceOf(IllegalStateException.class);

            assertThat(quotaRowCount())
                    .as("같은 트랜잭션에 참여했으므로 행 확보까지 되돌아간다")
                    .isZero();
        }

        @Test
        void 정상_경로는_예외를_던지지_않는다() {
            assertThatCode(() -> inTransaction(() -> {
                quota.ensureRow(CHUSEOK, USER_A);
                quota.tryAcquire(CHUSEOK, USER_A, 4);
                quota.lockRow(CHUSEOK, USER_A);
                quota.lockAll(List.of(new QuotaKey(CHUSEOK, USER_A)));
                quota.release(CHUSEOK, USER_A, 4);
            })).doesNotThrowAnyException();
        }
    }
}
