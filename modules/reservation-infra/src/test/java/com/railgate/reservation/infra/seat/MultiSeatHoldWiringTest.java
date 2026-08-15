package com.railgate.reservation.infra.seat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.infra.MySqlTestSupport;
import com.railgate.reservation.seat.SeatId;
import com.railgate.reservation.seat.SeatUnavailableException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 생성자 배선 계약 (Task 2F 보완).
 *
 * <h2>무엇을 막는가</h2>
 *
 * <p>저장소의 {@link DataSource} 와 savepoint 를 만드는 트랜잭션 관리자가 <b>서로 다른
 * 트랜잭션 리소스</b>를 가리키면 I-9 가 조용히 깨진다.
 *
 * <ol>
 *   <li>호출자가 좌석 DataSource A 위에 트랜잭션을 연다.</li>
 *   <li>{@code requireEnlistedTransaction()} 는 A 기준이라 통과한다.</li>
 *   <li>savepoint 는 관리자 B 에서 실행되어 A 의 UPDATE 를 보호하지 못한다.</li>
 *   <li>경합 예외를 트랜잭션 안에서 잡고 커밋하면 부분 선점이 확정된다.</li>
 * </ol>
 *
 * <p>이 배선 오류를 <b>생성자에서</b> 차단하는 것이 이 테스트의 대상이다.
 *
 * <h2>조건은 인스턴스 동일성이 아니라 리소스 동일성이다</h2>
 *
 * <p>"같은 관리자 인스턴스를 써야 한다" 는 설명은 틀렸다. 서로 다른
 * {@link DataSourceTransactionManager} 인스턴스라도 같은 {@link DataSource} 를 관리하면
 * 같은 스레드 리소스에 참여한다. §2 가 그것을 실제 MySQL 로 확인한다.
 */
@Timeout(60)
@DisplayName("JdbcMultiSeatHoldRepository - 생성자 배선 계약")
class MultiSeatHoldWiringTest extends MySqlTestSupport {

    private static final long SCHEDULE_ID = 1L;
    private static final Duration HOLD_DURATION = Duration.ofMinutes(5);

    private static final HoldId HOLD = HoldId.of("abcdefab-cdef-4abc-8def-abcdefabcdef");
    private static final HoldId OTHER_HOLD = HoldId.of("11111111-2222-4333-8444-555555555555");
    private static final UserId USER = new UserId(7L);
    private static final UserId OTHER_USER = new UserId(9L);

    private JdbcSeatHoldRepository singleSeatRepository;
    private List<Long> seatIds;

    @BeforeEach
    void setUp() {
        singleSeatRepository = new JdbcSeatHoldRepository(dataSource(), HOLD_DURATION);
        seatIds = new ArrayList<>();
        for (String seatNo : List.of("1A", "2A", "3A")) {
            seatIds.add(insertAvailableSeat(SCHEDULE_ID, seatNo));
        }
    }

    private List<SeatId> allThree() {
        return seatIds.stream().map(SeatId::new).toList();
    }

    private long heldByRequester() {
        return jdbc().queryForObject(
                "SELECT COUNT(*) FROM seat_inventory WHERE hold_id = ? AND status = 'HELD'",
                Long.class, HOLD.asString());
    }

    @Nested
    @DisplayName("1. 같은 DataSource · 같은 관리자 인스턴스")
    class 같은_인스턴스 {

        private JdbcMultiSeatHoldRepository repository;

        @BeforeEach
        void createRepository() {
            repository = new JdbcMultiSeatHoldRepository(
                    dataSource(), transactionManager(), HOLD_DURATION);
        }

        @Test
        void 저장소_생성에_성공한다() {
            assertThat(repository).isNotNull();
        }

        @Test
        void 외부_트랜잭션에서_전체_선점에_성공한다() {
            inTransaction(() -> repository.holdAll(allThree(), HOLD, USER));

            assertThat(heldByRequester()).isEqualTo(3);
        }

        @Test
        void 경합하면_savepoint_롤백이_동작한다() {
            singleSeatRepository.hold(new SeatId(seatIds.get(1)), OTHER_HOLD, OTHER_USER);

            inTransaction(() -> {
                try {
                    repository.holdAll(allThree(), HOLD, USER);
                } catch (SeatUnavailableException expected) {
                    // 경합을 정상 흐름으로 처리하고 커밋한다.
                }
            });

            assertThat(heldByRequester()).isZero();
        }
    }

    @Nested
    @DisplayName("2. ★ 같은 DataSource · 서로 다른 관리자 인스턴스")
    class 다른_인스턴스_같은_리소스 {

        /**
         * 저장소에는 <b>새로 만든</b> 관리자를 주고, 외부 트랜잭션은 공유 관리자로 연다.
         * 두 관리자는 인스턴스가 다르지만 같은 {@link DataSource} 를 관리한다.
         */
        private JdbcMultiSeatHoldRepository repositoryWithOwnManager() {
            return new JdbcMultiSeatHoldRepository(
                    dataSource(), new DataSourceTransactionManager(dataSource()), HOLD_DURATION);
        }

        @Test
        void 저장소_생성이_허용된다() {
            assertThatCode(this::repositoryWithOwnManager).doesNotThrowAnyException();
        }

        @Test
        void 관리자_인스턴스가_달라도_외부_트랜잭션에_참여한다() {
            JdbcMultiSeatHoldRepository repository = repositoryWithOwnManager();

            // 외부 경계는 인스턴스 A(공유 관리자), 저장소는 인스턴스 B.
            TransactionTemplate outer = newTransactionTemplate();
            outer.executeWithoutResult(status -> repository.holdAll(allThree(), HOLD, USER));

            assertThat(heldByRequester()).isEqualTo(3);
        }

        @Test
        void 예외를_안에서_잡고_커밋해도_부분_선점은_0개다() {
            JdbcMultiSeatHoldRepository repository = repositoryWithOwnManager();
            singleSeatRepository.hold(new SeatId(seatIds.get(1)), OTHER_HOLD, OTHER_USER);

            TransactionTemplate outer = newTransactionTemplate();
            outer.executeWithoutResult(status -> {
                try {
                    repository.holdAll(allThree(), HOLD, USER);
                } catch (SeatUnavailableException expected) {
                    // 인스턴스가 달라도 savepoint 보호가 유지되어야 한다.
                }
            });

            assertThat(heldByRequester()).as("요청자 부분 선점").isZero();
            assertThat(statusOf(seatIds.get(0))).isEqualTo("AVAILABLE");
            assertThat(statusOf(seatIds.get(2))).isEqualTo("AVAILABLE");
        }

        @Test
        void UnexpectedRollbackException_이_발생하지_않는다() {
            JdbcMultiSeatHoldRepository repository = repositoryWithOwnManager();
            singleSeatRepository.hold(new SeatId(seatIds.get(1)), OTHER_HOLD, OTHER_USER);

            TransactionTemplate outer = newTransactionTemplate();

            assertThatCode(() -> outer.executeWithoutResult(status -> {
                try {
                    repository.holdAll(allThree(), HOLD, USER);
                } catch (SeatUnavailableException expected) {
                    // 무시
                }
            }))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("3. ★ 서로 다른 DataSource — 생성자에서 거부")
    class 다른_DataSource {

        /**
         * 좌석 DataSource 와 <b>다른</b> DataSource.
         *
         * <p>커넥션을 요구하면 터지도록 만들었다. 생성자 검증이 DB 를 건드리지 않는다는 것이
         * 이 더블 자체로 증명된다 — SQL 이 실행됐다면 {@link UnsupportedOperationException} 이
         * 났을 것이다.
         *
         * <p><b>왜 실제 MySQL 동작 테스트와 분리하는가.</b> 이 테스트의 대상은 배선 검증이지
         * SQL·잠금 동작이 아니다. 실제 InnoDB 동작이 필요한 검증은
         * {@code MultiSeatHoldTransactionBoundaryTest} 가 Testcontainers 로 수행한다
         * (CLAUDE.md 규칙 26 은 후자에 적용된다).
         */
        private DataSource unrelatedDataSource() {
            return new DataSource() {
                @Override
                public Connection getConnection() {
                    throw new UnsupportedOperationException("배선 검증 테스트는 DB 에 접속하지 않는다");
                }

                @Override
                public Connection getConnection(String username, String password) {
                    return getConnection();
                }

                @Override
                public PrintWriter getLogWriter() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void setLogWriter(PrintWriter out) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void setLoginTimeout(int seconds) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int getLoginTimeout() {
                    return 0;
                }

                @Override
                public Logger getParentLogger() throws SQLFeatureNotSupportedException {
                    throw new SQLFeatureNotSupportedException();
                }

                @Override
                public <T> T unwrap(Class<T> iface) throws SQLException {
                    throw new SQLException("unsupported");
                }

                @Override
                public boolean isWrapperFor(Class<?> iface) {
                    return false;
                }

                @Override
                public String toString() {
                    return "UnrelatedDataSource";
                }
            };
        }

        @Test
        void 다른_DataSource_를_관리하는_트랜잭션_관리자는_거부된다() {
            DataSourceTransactionManager unrelated =
                    new DataSourceTransactionManager(unrelatedDataSource());

            assertThatThrownBy(() -> new JdbcMultiSeatHoldRepository(
                    dataSource(), unrelated, HOLD_DURATION))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void 예외_메시지에서_리소스_불일치임을_알_수_있다() {
            DataSourceTransactionManager unrelated =
                    new DataSourceTransactionManager(unrelatedDataSource());

            assertThatThrownBy(() -> new JdbcMultiSeatHoldRepository(
                    dataSource(), unrelated, HOLD_DURATION))
                    .hasMessageContaining("DataSource")
                    .hasMessageContaining("트랜잭션 관리자 리소스")
                    .hasMessageContaining("UnrelatedDataSource");
        }

        @Test
        void 거부되어도_좌석_SQL_은_실행되지_않는다() {
            DataSourceTransactionManager unrelated =
                    new DataSourceTransactionManager(unrelatedDataSource());

            assertThatThrownBy(() -> new JdbcMultiSeatHoldRepository(
                    dataSource(), unrelated, HOLD_DURATION))
                    .isInstanceOf(IllegalArgumentException.class);

            for (long id : seatIds) {
                assertThat(statusOf(id)).as("좌석 상태").isEqualTo("AVAILABLE");
                assertThat(holdIdOf(id)).isNull();
                assertThat(versionOf(id)).as("version").isZero();
            }
        }
    }

    @Nested
    @DisplayName("4. DataSource 프록시 정규화의 한계")
    class 프록시_정규화 {

        /**
         * {@link DataSourceTransactionManager} 는 {@link TransactionAwareDataSourceProxy} 를
         * 받으면 <b>대상 DataSource 로 벗겨서</b> 트랜잭션을 연다. 그래서 저장소도 같은
         * 정규화를 적용해야 키가 맞는다.
         *
         * <p>이 테스트를 처음 썼을 때는 "양쪽에 같은 프록시를 주면 키가 같으니 통과한다" 고
         * 예상했으나 <b>실제로는 거부됐다</b> — 관리자만 벗기고 저장소는 프록시를 그대로
         * 키로 삼았기 때문이다. 그 관측이 정규화를 두 단계로 만든 근거다.
         */
        @Test
        void 저장소와_관리자가_같은_프록시를_쓰면_허용된다() {
            DataSource proxy = new TransactionAwareDataSourceProxy(dataSource());

            assertThatCode(() -> new JdbcMultiSeatHoldRepository(
                    proxy, new DataSourceTransactionManager(proxy), HOLD_DURATION))
                    .doesNotThrowAnyException();
        }

        @Test
        void 저장소만_프록시를_써도_대상이_같으면_허용된다() {
            // 관리자는 원본 DataSource 로 트랜잭션을 열고, 저장소는 프록시를 갖는다.
            // 두 키를 모두 대상 DataSource 로 정규화하므로 일치한다.
            DataSource proxy = new TransactionAwareDataSourceProxy(dataSource());

            assertThatCode(() -> new JdbcMultiSeatHoldRepository(
                    proxy, transactionManager(), HOLD_DURATION))
                    .doesNotThrowAnyException();
        }

        @Test
        void 프록시를_써도_savepoint_보호가_유지된다() {
            DataSource proxy = new TransactionAwareDataSourceProxy(dataSource());
            JdbcMultiSeatHoldRepository repository = new JdbcMultiSeatHoldRepository(
                    proxy, transactionManager(), HOLD_DURATION);
            singleSeatRepository.hold(new SeatId(seatIds.get(1)), OTHER_HOLD, OTHER_USER);

            inTransaction(() -> {
                try {
                    repository.holdAll(allThree(), HOLD, USER);
                } catch (SeatUnavailableException expected) {
                    // 무시하고 커밋
                }
            });

            assertThat(heldByRequester()).as("정규화가 맞으면 프록시여도 보호된다").isZero();
        }
    }
}
