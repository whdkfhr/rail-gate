package com.railgate.reservation.config;

import com.railgate.reservation.application.expiry.RetryBackoff;
import com.railgate.reservation.expiry.SeatExpiryPort;
import com.railgate.reservation.hold.SeatConfirmationPort;
import com.railgate.reservation.hold.SeatHoldPort;
import com.railgate.reservation.hold.SeatPaymentPort;
import com.railgate.reservation.hold.SeatReleasePort;
import com.railgate.reservation.infra.quota.JdbcUserHoldQuotaRepository;
import com.railgate.reservation.infra.saleevent.JdbcSaleEventScopeRepository;
import com.railgate.reservation.infra.seat.JdbcMultiSeatHoldRepository;
import com.railgate.reservation.infra.seat.JdbcSeatExpiryRepository;
import com.railgate.reservation.infra.seat.JdbcSeatPaymentRepository;
import com.railgate.reservation.infra.seat.JdbcSeatReleaseRepository;
import com.railgate.reservation.quota.UserHoldQuotaPort;
import com.railgate.reservation.saleevent.SaleEventScopePort;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * JDBC 어댑터를 도메인 포트에 연결한다.
 *
 * <h2>★ 하나의 DataSource, 하나의 트랜잭션 관리자</h2>
 *
 * <p>quota 저장소와 좌석 저장소가 <b>같은 {@link DataSource}</b> 를 받아야 한다.
 * 다른 DataSource 를 주면 두 변경이 서로 다른 커넥션에서 일어나 한쪽만 커밋된다.
 * 저장소들이 각자 참여 검사로 거부하지만, 그 검사는 <b>배선 실수를 런타임에</b>
 * 발견하는 마지막 방어선이다. 배선 자체를 한 곳에 모아 실수할 자리를 없앤다.
 *
 * <p>{@code Duration.ofMinutes(5)} 는 REQUIREMENTS.md P-4 의 선점 유지 시간이다.
 *
 * <h2>★ {@code innodb_lock_wait_timeout} 은 DataSource 구성에 둔다</h2>
 *
 * <p>CLAUDE.md 규칙 9 는 홀드 트랜잭션의 잠금 대기를 3초로 제한하라고 요구한다.
 * 그것은 <b>커넥션 세션 변수</b>이고, 이 프로젝트는 그 설정 책임을
 * <b>{@code DataSource} 구성에 두기로 했다.</b> {@code application.yml} 의
 * {@code spring.datasource.hikari.connection-init-sql} 이 커넥션 생성 시 한 번 적용한다.
 *
 * <p>다른 방법이 없어서가 아니라 <b>고른 것</b>이다. 트랜잭션마다 {@code SET} 을 보내면
 * 요청당 왕복이 하나 늘고, 저장소가 각자 보내면 저장소마다 같은 코드가 생긴다.
 * 커넥션 생성 시 한 번이면 둘 다 피한다. {@code MySqlTestSupport} 픽스처도 같은 방식이라
 * 테스트와 운영의 세션 상태가 어긋나지 않는다.
 *
 * <p>대신 <b>실제로 적용됐는지는 테스트가 확인한다</b> —
 * {@code ReservationServiceApplicationTest} 가 세션 변수를 직접 읽는다.
 *
 * <h2>어떤 타입을 빈으로 노출하는가</h2>
 *
 * <p><b>저장소는 포트 타입으로 노출한다.</b> 구현체 타입으로 노출하면 서비스가
 * {@code reservation-infra} 를 알게 되고, 모듈 경계가 구성 파일 밖으로 새어 나간다.
 *
 * <p><b>예외 하나 — {@link JdbcSeatPaymentRepository}.</b> 이 어댑터는 결제 시작과 확정 두 포트를
 * 함께 구현한다. 포트마다 {@code new} 로 빈을 하나씩 만들면 같은 클래스의 인스턴스가 둘 생기고,
 * Spring 은 생성 후 실제 타입으로 후보를 고르므로 <b>두 빈 모두 두 포트의 후보</b>가 되어 주입이
 * 모호해진다. 그래서 인스턴스를 하나만 만들고 그 빈이 두 포트를 함께 만족하게 한다.
 * 선언 타입이 구현체여도 <b>서비스는 여전히 포트로만 주입받는다</b> — 구현체를 아는 것은 이 클래스뿐이다.
 *
 * <p><b>트랜잭션 관리자는 구체 타입으로 노출한다.</b>
 * {@link JdbcMultiSeatHoldRepository} 가 savepoint 를 만들기 위해 그 타입을 요구하기
 * 때문이다. 다운캐스팅으로 미루는 대신 선언 타입에서 맞춘다.
 * <b>애플리케이션 서비스는 이 빈을 주입받지 않는다</b> — 경계는
 * {@code @Transactional} 이 열고, 서비스는 포트만 안다.
 *
 * <p>구현체와 구체 관리자 타입을 아는 것은 <b>이 클래스뿐</b>이다.
 */
@Configuration
public class ReservationPersistenceConfig {

    /** 선점 유지 시간 (REQUIREMENTS.md P-4). */
    private static final Duration HOLD_DURATION = Duration.ofMinutes(5);

    /** 결제 유효 시간 (REQUIREMENTS.md P-4). 결제 시작의 {@code GREATEST(기존 만료, NOW(3) + 이 값)} 에 쓰인다. */
    private static final Duration PAYMENT_DURATION = Duration.ofMinutes(5);

    /**
     * 좌석 트랜잭션 관리자.
     *
     * <p>{@link JdbcMultiSeatHoldRepository} 가 savepoint 를 만들 때 쓴다.
     * 저장소 생성자가 <b>이 관리자와 자신의 DataSource 가 같은 트랜잭션 리소스인지</b>
     * 검증하므로, 어긋나면 애플리케이션이 뜨지 않는다.
     */
    @Bean
    public DataSourceTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    public SaleEventScopePort saleEventScopePort(DataSource dataSource) {
        return new JdbcSaleEventScopeRepository(dataSource);
    }

    @Bean
    public UserHoldQuotaPort userHoldQuotaPort(DataSource dataSource) {
        return new JdbcUserHoldQuotaRepository(dataSource);
    }

    @Bean
    public SeatHoldPort seatHoldPort(
            DataSource dataSource, DataSourceTransactionManager transactionManager) {
        // 저장소는 savepoint 를 만들기 위해 JDBC 관리자를 요구한다. 위 빈의 선언 타입이
        // 이미 그것이므로 다운캐스팅하지 않는다 — 캐스팅은 컴파일러가 확인해 줄 수 있는
        // 조건을 런타임으로 미루는 일이다.
        return new JdbcMultiSeatHoldRepository(dataSource, transactionManager, HOLD_DURATION);
    }

    /**
     * 자발적 해제 (Task 2H-B). 같은 {@code dataSource} 를 받으므로
     * {@code ReleaseHoldService} 의 트랜잭션에서 quota 감소와 같은 커넥션을 쓴다.
     */
    @Bean
    public SeatReleasePort seatReleasePort(DataSource dataSource) {
        return new JdbcSeatReleaseRepository(dataSource);
    }

    /**
     * 단일 좌석 결제 시작(Task 2H-F)과 확정(Task 2H-C). <b>하나의 인스턴스</b>가
     * {@link SeatPaymentPort} 와 {@link SeatConfirmationPort} 를 함께 만족한다 (클래스 주석 참고).
     * {@code StartPaymentService} 는 앞의 포트만, {@code ConfirmSeatService} 는 뒤의 포트만 주입받는다.
     */
    @Bean
    public JdbcSeatPaymentRepository seatPaymentRepository(DataSource dataSource) {
        return new JdbcSeatPaymentRepository(dataSource, PAYMENT_DURATION);
    }

    /** 만료 후보 조회와 회수 (Task 2H-D). 그룹 트랜잭션은 {@code ExpireHoldsService} 가 소유한다. */
    @Bean
    public SeatExpiryPort seatExpiryPort(DataSource dataSource) {
        return new JdbcSeatExpiryRepository(dataSource);
    }

    /**
     * 재시도 백오프 (Task 2H-D). 측정으로 정한 값이 아니다 — {@link RetryBackoff#boundedExponential} 참고.
     * 테스트는 대기하지 않는 구현을 직접 주입한다.
     */
    @Bean
    public RetryBackoff retryBackoff() {
        return RetryBackoff.boundedExponential();
    }
}
