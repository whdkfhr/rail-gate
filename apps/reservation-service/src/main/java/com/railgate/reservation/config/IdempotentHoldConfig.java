package com.railgate.reservation.config;

import com.railgate.reservation.application.hold.HoldSeatsService;
import com.railgate.reservation.application.idempotency.IdempotentHoldProperties;
import com.railgate.reservation.application.idempotency.IdempotentHoldService;
import com.railgate.reservation.idempotency.IdempotencyKeyPort;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * 선점 멱등성 조정자 배선 (Task 2I-C).
 *
 * <p>조정자는 <b>트랜잭션 프록시가 붙은 {@link HoldSeatsService} 빈</b>을 받는다. TX1 은 조정자가 열고 선점 서비스는
 * {@code REQUIRED} 로 참여하므로, 선점과 성공 기록이 같은 트랜잭션·같은 커넥션에 묶인다.
 *
 * <p>리스 길이는 {@link IdempotentHoldProperties} 에서 온다. 저장소의 정밀도 계약을 어기면 조정자 생성자가 거부해
 * <b>기동 단계에서</b> 실패한다. 기본값 30초는 미검증 가정이다 (ADR-009 C4 미결정).
 *
 * <p><b>이 빈을 부르는 HTTP 진입점은 아직 없다.</b> 서비스 수준 연결일 뿐 요청 경로의 I-18 완성이 아니다.
 */
@Configuration
@EnableConfigurationProperties(IdempotentHoldProperties.class)
public class IdempotentHoldConfig {

    @Bean
    public IdempotentHoldService idempotentHoldService(
            IdempotencyKeyPort idempotencyKeyPort,
            HoldSeatsService holdSeatsService,
            DataSourceTransactionManager transactionManager,
            IdempotentHoldProperties properties,
            MeterRegistry meters) {
        return new IdempotentHoldService(
                idempotencyKeyPort, holdSeatsService, transactionManager, properties.getLease(), meters);
    }
}
