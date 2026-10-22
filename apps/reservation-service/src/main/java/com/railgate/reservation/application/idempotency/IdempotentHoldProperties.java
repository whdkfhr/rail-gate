package com.railgate.reservation.application.idempotency;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 멱등 선점 설정 (`railgate.idempotency.hold.*`).
 *
 * <h2>★ 리스 기본값 30초는 미검증 가정이다</h2>
 *
 * <p>리스는 TX1(선점 + 성공 기록)의 최대 소요보다 길어야 한다. 짧으면 살아 있는 작업자가 리스를 빼앗기고
 * ({@link IdempotentHoldResult.OwnershipLost}), 길면 장애 뒤 인수까지 오래 기다린다.
 * 30초는 잠금 대기 상한 3초(규칙 9) × 문장 수보다 넉넉하다는 것 외에 <b>측정 근거가 없다.</b>
 * ADR-009 C4 는 미결정이다 — 이 값으로 확정된 것이 아니다.
 *
 * <p>저장소의 정밀도 계약(1ms 이상, 밀리초 단위)은 {@link IdempotentHoldService} 생성자가 검증하므로
 * 잘못된 값이면 <b>기동 단계에서</b> 실패한다.
 */
@ConfigurationProperties(prefix = "railgate.idempotency.hold")
public class IdempotentHoldProperties {

    /** 미검증 가정값 (ADR-009 C4 미결정). */
    private Duration lease = Duration.ofSeconds(30);

    public Duration getLease() {
        return lease;
    }

    public void setLease(Duration lease) {
        this.lease = lease;
    }
}
