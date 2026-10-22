package com.railgate.reservation.application.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.railgate.reservation.application.hold.HoldSeatsService;
import com.railgate.reservation.config.IdempotentHoldConfig;
import com.railgate.reservation.hold.SeatHoldPort;
import com.railgate.reservation.idempotency.IdempotencyKeyPort;
import com.railgate.reservation.quota.UserHoldQuotaPort;
import com.railgate.reservation.saleevent.SaleEventScopePort;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * 리스 설정의 <b>실제 프로퍼티 바인딩과 컨텍스트 시작</b>을 DB 없이 검증한다.
 *
 * <p>DB 를 쓰지 않는 경량 컨텍스트다. 포트·선점 서비스는 호출되지 않으므로 빈 자리만 채운다.
 */
@DisplayName("Task 2I-C — 멱등 선점 리스 설정")
class IdempotentHoldConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(IdempotentHoldConfig.class)
            .withBean(IdempotencyKeyPort.class, () -> mock(IdempotencyKeyPort.class))
            .withBean(HoldSeatsService.class, () -> new HoldSeatsService(
                    mock(SaleEventScopePort.class), mock(UserHoldQuotaPort.class), mock(SeatHoldPort.class)))
            .withBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(mock(DataSource.class)))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    @Test
    void 기본_리스는_30초_미검증_가정값이다() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(IdempotentHoldService.class);
            assertThat(context.getBean(IdempotentHoldProperties.class).getLease()).isEqualTo(Duration.ofSeconds(30));
        });
    }

    @Test
    void 밀리초_단위_리스는_바인딩된다() {
        runner.withPropertyValues("railgate.idempotency.hold.lease=1500ms").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(IdempotentHoldProperties.class).getLease()).isEqualTo(Duration.ofMillis(1500));
        });
    }

    /** 저장소의 정밀도 계약(1ms 이상, 밀리초 단위)을 어기면 기동 단계에서 실패한다. */
    @ParameterizedTest
    @ValueSource(strings = {"0s", "-1s", "1500us"})
    void 저장할_수_없는_리스면_컨텍스트가_뜨지_않는다(String lease) {
        runner.withPropertyValues("railgate.idempotency.hold.lease=" + lease).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("리스는 1ms 이상 밀리초 단위");
        });
    }
}
