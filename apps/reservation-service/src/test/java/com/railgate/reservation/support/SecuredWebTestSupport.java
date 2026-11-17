package com.railgate.reservation.support;

import com.railgate.reservation.application.idempotency.IdempotentHoldService;
import com.railgate.testprobe.AuthProbeController;
import com.railgate.testprobe.HoldProbeController;
import java.time.Instant;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * 실제 내장 서버와 SecurityFilterChain 을 쓰는 웹 보안 테스트의 <b>공통 컨텍스트</b> (Task 2J-B·2J-C).
 *
 * <p>테스트마다 구성이 다르면 Spring 이 컨텍스트를 따로 캐시하고, 컨텍스트마다 커넥션 풀(최대 64)이 MySQL 연결을 쥔다.
 * 실제로 두 번째 웹 컨텍스트를 추가했을 때 {@code Too many connections}(max_connections 151)로 다른 테스트가 실패했다
 * (TASK-002J-C §6). 그래서 확인용 컨트롤러·시계를 한곳에 모아 <b>같은 구성 = 같은 컨텍스트</b>가 되게 한다.
 * 확인용 컨트롤러는 컴포넌트 스캔 밖 패키지에 있어 이 테스트들에만 등록된다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SecuredWebTestSupport.ProbeConfig.class)
public abstract class SecuredWebTestSupport extends MySqlSpringTestSupport {

    protected static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    @TestConfiguration
    static class ProbeConfig {

        @Bean
        @Primary
        MutableClock testAuthClock() {
            return new MutableClock(NOW);
        }

        @Bean
        AuthProbeController authProbeController() {
            return new AuthProbeController();
        }

        @Bean
        HoldProbeController holdProbeController(IdempotentHoldService service) {
            return new HoldProbeController(service);
        }
    }
}
