package com.railgate.reservation.application.expiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.railgate.reservation.config.ExpirySchedulerConfig;
import com.railgate.reservation.support.MySqlSpringTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

/**
 * 스케줄러 <b>배선</b>을 실제 Spring 컨텍스트로 검증한다.
 *
 * <p>기본 비활성이므로 <b>다른 테스트에서 스케줄러가 우연히 돌 수 없다</b> — 그 사실 자체가
 * 첫 번째 검증 항목이다. 운영 코드에 테스트 전용 훅을 넣지 않았다.
 */
@DisplayName("만료 스케줄러 배선")
class ExpirySchedulerWiringTest {

    /** ★ 기본값으로는 스케줄러 빈도 스케줄 등록도 없다. */
    @Nested
    @DisplayName("§1 ★ 기본 비활성")
    class 기본_비활성 extends MySqlSpringTestSupport {

        @Autowired
        private ApplicationContext context;

        @Test
        void 기본_설정에서는_스케줄러_빈이_없다() {
            assertThat(context.getBeanNamesForType(ExpirySweepScheduler.class))
                    .as("★ 켜지 않으면 빈이 생기지 않는다 — 다른 테스트에서 우연히 돌 수 없다")
                    .isEmpty();
            assertThat(context.getBeanNamesForType(ExpirySchedulerConfig.ExpirySweepTrigger.class))
                    .as("스케줄 등록 래퍼도 없다")
                    .isEmpty();
        }

        @Test
        void 만료_배치_서비스는_배선되어_있다() {
            assertThat(context.getBeanNamesForType(ExpireHoldsService.class))
                    .as("서비스는 있다 — 부르는 주체만 없다").isNotEmpty();
            assertThat(context.getBean(ExpirySweeper.class))
                    .as("서비스가 ExpirySweeper 포트를 만족한다")
                    .isInstanceOf(ExpireHoldsService.class);
        }
    }

    /** ★ 명시적으로 켜면 어댑터와 스케줄 래퍼가 등록된다. */
    @Nested
    @DisplayName("§2 ★ 활성화 시 배선")
    @SpringBootTest
    @TestPropertySource(properties = {
            "railgate.expiry.sweep.enabled=true",
            // 테스트 동안 실제로 돌지 않도록 간격을 아주 길게 둔다 — 등록 여부만 본다.
            "railgate.expiry.sweep.fixed-delay=1h",
            "railgate.expiry.sweep.page-size=50",
            "railgate.expiry.sweep.max-pages=2"
    })
    class 활성화_시_배선 extends MySqlSpringTestSupport {

        @Autowired
        private ApplicationContext context;

        @Autowired
        private ExpirySweepProperties properties;

        @Test
        void 켜면_어댑터와_스케줄_래퍼가_등록된다() {
            assertThat(context.getBeanNamesForType(ExpirySweepScheduler.class)).isNotEmpty();
            assertThat(context.getBeanNamesForType(ExpirySchedulerConfig.ExpirySweepTrigger.class))
                    .isNotEmpty();
        }

        @Test
        void 설정값이_그대로_주입된다() {
            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.getPageSize()).isEqualTo(50);
            assertThat(properties.getMaxPages()).isEqualTo(2);
            assertThat(properties.getFixedDelay().toHours()).isEqualTo(1);
        }
    }

    /** ★ 켠 상태로 잘못된 값을 주면 <b>기동 단계</b>에서 실패한다. */
    @Nested
    @DisplayName("§3 ★ 잘못된 설정 거부")
    class 잘못된_설정_거부 {

        private ExpirySweepProperties enabled(int pageSize, int maxPages, String delay) {
            ExpirySweepProperties p = new ExpirySweepProperties();
            p.setEnabled(true);
            p.setPageSize(pageSize);
            p.setMaxPages(maxPages);
            p.setFixedDelay(delay == null ? null : java.time.Duration.parse(delay));
            return p;
        }

        private ExpirySweepScheduler build(ExpirySweepProperties p) {
            return new ExpirySweepScheduler(
                    request -> {
                        throw new AssertionError("생성 단계에서 거부돼야 한다");
                    },
                    p, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        }

        @Test
        void 양수가_아닌_값은_전부_거부한다() {
            assertThatThrownBy(() -> build(enabled(0, 4, "PT30S")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pageSize");
            assertThatThrownBy(() -> build(enabled(100, -1, "PT30S")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxPages");
            assertThatThrownBy(() -> build(enabled(100, 4, "PT0S")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("fixedDelay");
            assertThatThrownBy(() -> build(enabled(100, 4, null)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("fixedDelay");
        }
    }
}
