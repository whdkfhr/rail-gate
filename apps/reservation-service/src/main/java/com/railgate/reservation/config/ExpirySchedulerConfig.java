package com.railgate.reservation.config;

import com.railgate.reservation.application.expiry.ExpirySweepProperties;
import com.railgate.reservation.application.expiry.ExpirySweepScheduler;
import com.railgate.reservation.application.expiry.ExpirySweeper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 만료 배치 스케줄 등록. <b>{@code railgate.expiry.sweep.enabled=true} 일 때만 켜진다.</b>
 *
 * <h2>★ 기본 비활성인 이유</h2>
 *
 * <p>만료 회수는 좌석을 되돌리고 quota 를 줄이는 <b>쓰기 작업</b>이다. 기존 데이터가 있는 배포에서
 * 카운터가 비어 있거나 어긋나 있으면 그룹이 실패로 남거나 drift 가 조용히 쌓인다.
 * <b>backfill(TASK-002G-G §4)과 drift 점검(V-7a·b·c)을 마치기 전에는 켜면 안 된다.</b>
 * 이 Task 는 배선만 하고 <b>운영 활성화를 하지 않는다.</b>
 *
 * <h2>★ {@code @EnableScheduling} 을 여기에 두는 이유</h2>
 *
 * <p>{@code @SpringBootApplication} 에 붙이면 끄여 있어도 스케줄러 인프라가 항상 올라간다.
 * 이 구성 전체가 {@link ConditionalOnProperty} 로 묶여 있으므로, 켜지 않으면
 * <b>스케줄 등록도 스레드 풀도 생기지 않는다.</b> 도메인과 애플리케이션 서비스에는
 * Spring 스케줄링 의존성이 없다 — 어댑터와 이 구성만 안다.
 *
 * <h2>왜 {@code @Scheduled} 를 어댑터가 아니라 이 래퍼에 붙이는가</h2>
 *
 * <p>어댑터에 직접 붙이면 그 클래스가 빈으로 존재하는 것만으로 등록돼 조건부 제어가 어렵고,
 * 단위 테스트가 손으로 {@code tick()} 을 부를 때도 스케줄 애너테이션을 끌고 다닌다.
 * 등록은 여기, 계약은 어댑터에 둔다.
 */
@Configuration
@EnableConfigurationProperties(ExpirySweepProperties.class)
@ConditionalOnProperty(prefix = "railgate.expiry.sweep", name = "enabled", havingValue = "true")
@EnableScheduling
public class ExpirySchedulerConfig {

    /**
     * 어댑터. 생성자가 설정을 검증하므로 <b>잘못된 값이면 기동 단계에서 실패</b>한다 —
     * 켠 뒤에야 런타임에 드러나는 것보다 낫다.
     */
    @Bean
    public ExpirySweepScheduler expirySweepScheduler(
            ExpirySweeper sweeper, ExpirySweepProperties properties, MeterRegistry meters) {
        return new ExpirySweepScheduler(sweeper, properties, meters);
    }

    /**
     * 주기 실행 래퍼.
     *
     * <p>{@code fixedRate} 가 아니라 {@code fixedDelayString} 이다 — 직전 실행이 <b>끝난 뒤</b>
     * 간격을 센다. 배치가 느려졌을 때 실행이 밀려 쌓이는 것을 피한다.
     * (겹침 자체는 어댑터가 한 번 더 막는다.)
     */
    @Bean
    public ExpirySweepTrigger expirySweepTrigger(ExpirySweepScheduler scheduler) {
        return new ExpirySweepTrigger(scheduler);
    }

    /** {@code @Scheduled} 를 담는 최소 래퍼. 하는 일은 tick 전달뿐이다. */
    public static class ExpirySweepTrigger {

        private final ExpirySweepScheduler scheduler;

        ExpirySweepTrigger(ExpirySweepScheduler scheduler) {
            this.scheduler = scheduler;
        }

        @Scheduled(fixedDelayString = "${railgate.expiry.sweep.fixed-delay}")
        public void run() {
            scheduler.tick();
        }
    }
}
