package com.railgate.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.railgate.reservation.application.hold.HoldSeatsService;
import com.railgate.reservation.hold.SeatHoldPort;
import com.railgate.reservation.quota.UserHoldQuotaPort;
import com.railgate.reservation.saleevent.SaleEventScopePort;
import com.railgate.reservation.support.MySqlSpringTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 애플리케이션 컨텍스트가 정상적으로 뜨는지 확인한다.
 * 빈 설정 오류를 가장 이른 시점에 잡기 위한 최소 안전망이다.
 *
 * <p><b>Task 2H-A 부터 실제 MySQL 이 필요하다.</b> 앱이 {@code DataSource} 와 Flyway 를
 * 구성하므로 DB 없이는 컨텍스트가 뜨지 않는다. 임베디드 DB 를 끼우지 않는다 (규칙 26) —
 * 여기서 뜨는 컨텍스트가 통합 테스트가 쓰는 것과 달라지면 안 된다.
 */
@DisplayName("애플리케이션 컨텍스트")
class ReservationServiceApplicationTest extends MySqlSpringTestSupport {

    @Autowired
    private HoldSeatsService holdSeats;

    @Autowired
    private SaleEventScopePort saleEventScope;

    @Autowired
    private UserHoldQuotaPort quota;

    @Autowired
    private SeatHoldPort seats;

    @Test
    void contextLoads() {
        assertThat(holdSeats).isNotNull();
    }

    /**
     * ★ 서비스가 <b>포트</b>를 통해 배선됐는지 확인한다.
     *
     * <p>구현체 타입으로 주입받으면 서비스가 {@code reservation-infra} 를 알게 되고
     * 모듈 경계가 구성 파일 밖으로 샌다. 구현체를 아는 것은 구성 클래스뿐이어야 한다.
     */
    @Test
    void 포트가_JDBC_구현체로_배선된다() {
        assertThat(saleEventScope).isInstanceOf(SaleEventScopePort.class);
        assertThat(quota).isInstanceOf(UserHoldQuotaPort.class);
        assertThat(seats).isInstanceOf(SeatHoldPort.class);

        assertThat(saleEventScope.getClass().getName())
                .as("구현체는 infra 모듈에 있다")
                .startsWith("com.railgate.reservation.infra.");
    }

    /** ★ 규칙 9. 커넥션 세션에 잠금 대기 상한이 실제로 적용됐는지 확인한다. */
    @Test
    void 커넥션_세션에_lock_wait_timeout_3초가_적용된다() {
        Integer timeout = jdbc().queryForObject(
                "SELECT @@SESSION.innodb_lock_wait_timeout", Integer.class);

        assertThat(timeout)
                .as("★ 기본값 50초면 경합 시 커넥션 풀이 고갈된다")
                .isEqualTo(3);
    }

    /** Flyway 가 앱 컨텍스트에서 실제로 돌아 V6 까지 적용됐는지 확인한다. */
    @Test
    void 마이그레이션이_V6_까지_적용된다() {
        assertThat(jdbc().queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = 1", String.class))
                .contains("1", "4", "5", "6");
    }
}
