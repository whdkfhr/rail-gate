package com.railgate.reservation.application.expiry;

import static org.assertj.core.api.Assertions.assertThat;

import com.railgate.reservation.config.ExpirySchedulerConfig;
import com.railgate.reservation.support.MySqlSpringTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.TaskManagementConfigUtils;

/**
 * 스케줄러 <b>배선</b>을 실제 Spring 컨텍스트로 검증한다.
 *
 * <h2>★ 왜 활성화 배선은 테스트 전용 {@link TaskScheduler} 로 보는가</h2>
 *
 * <p>{@code @Scheduled} 에 {@code initialDelay} 가 없으면 Spring 은 초기 지연을 0 으로 보고
 * <b>등록 시각을 시작 시각으로</b> 넘긴다. 실제 스케줄러는 첫 실행을 즉시 한다.
 * {@code fixed-delay} 는 실행 <b>사이의</b> 간격이라 아무리 길게 둬도 첫 실행은 미뤄지지 않는다 — 이전 판에서는
 * {@code scheduling-1} 스레드가 테스트 중에 실제 만료 서비스를 불렀고, 공유 MySQL 의 초기화·픽스처와
 * 경쟁할 수 있었다.
 *
 * <p>그래서 활성화 배선은 DB 없는 경량 컨텍스트에서 <b>등록을 포착만 하고 실행하지 않는</b>
 * {@link CapturingTaskScheduler} 로 본다. 조건부 구성·{@code @EnableScheduling}·{@code @Scheduled}
 * 등록 경로는 운영과 같고, 바뀌는 것은 등록을 받는 스케줄러뿐이다. 그 스케줄러는 스레드도 타이머도
 * 만들지 않으므로 포착한 작업은 <b>테스트가 손으로 부를 때만</b> 돈다. 운영 코드에 테스트 전용 훅은 없다.
 *
 * <h2>★ 이 테스트가 검증하지 않는 것</h2>
 *
 * <p>포착한 작업을 손으로 부르면 Spring 스케줄러의 오류 처리({@code ErrorHandler})를 거치지 않는다.
 * 여기서 보는 것은 <b>등록 여부·주기·tick 전달</b>이며, 예외가 났을 때 Spring 이 무엇을 하는지는
 * 검증 범위가 아니다.
 */
@DisplayName("만료 스케줄러 배선")
class ExpirySchedulerWiringTest {

    private static final String PREFIX = "railgate.expiry.sweep.";

    /** 실제 앱 컨텍스트(실제 MySQL) — 기본값으로는 스케줄러 빈도 스케줄 등록도 없다. */
    @Nested
    @DisplayName("§1 ★ 기본 비활성 — 실제 앱 컨텍스트")
    class 기본_비활성 extends MySqlSpringTestSupport {

        @Autowired
        private ApplicationContext context;

        @Test
        void 기본_설정에서는_스케줄러_빈도_스케줄_등록도_없다() {
            assertThat(context.getBeanNamesForType(ExpirySweepScheduler.class))
                    .as("★ 켜지 않으면 빈이 생기지 않는다 — 다른 테스트에서 우연히 돌 수 없다")
                    .isEmpty();
            assertThat(context.getBeanNamesForType(ExpirySchedulerConfig.ExpirySweepTrigger.class))
                    .as("스케줄 등록 래퍼도 없다")
                    .isEmpty();
            assertThat(context.containsBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME))
                    .as("★ @EnableScheduling 이 적용되지 않았다 — 스케줄 등록 처리기 자체가 없다")
                    .isFalse();
            assertThat(scheduledTasks(context)).as("등록된 스케줄 작업이 없다").isEmpty();
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

    /**
     * 경량 컨텍스트 — 실제 조건부 구성과 {@code @Scheduled} 등록 경로를 쓰되,
     * 서비스는 가짜 {@link ExpirySweeper}, 스케줄러는 실행하지 않는 {@link CapturingTaskScheduler} 다.
     */
    private static ApplicationContextRunner lightweightContext() {
        // 테스트 빈은 @Configuration 이 아니라 여기서 등록한다. 테스트 소스의 @Configuration 은
        // 같은 패키지를 스캔하는 @SpringBootTest 컨텍스트(§1)에도 섞여 들어간다.
        return new ApplicationContextRunner()
                // 이름을 taskScheduler 로 둬 Spring 의 기본 해석 규칙대로 선택되게 한다.
                .withBean("taskScheduler", CapturingTaskScheduler.class, CapturingTaskScheduler::new)
                .withBean(RecordingSweeper.class, RecordingSweeper::new)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withUserConfiguration(ExpirySchedulerConfig.class);
    }

    /** ★ 명시적으로 켜면 fixedDelay 작업 하나가 등록되지만 스스로 실행되지 않는다. */
    @Nested
    @DisplayName("§2 ★ 활성화 시 배선 — 등록 포착, 자동 실행 없음")
    class 활성화_시_배선 {

        private final ApplicationContextRunner enabled = lightweightContext().withPropertyValues(
                PREFIX + "enabled=true",
                PREFIX + "fixed-delay=45s",
                PREFIX + "page-size=50",
                PREFIX + "max-pages=2");

        @Test
        void 켜면_어댑터와_스케줄_래퍼가_등록된다() {
            enabled.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(ExpirySweepScheduler.class);
                assertThat(context).hasSingleBean(ExpirySchedulerConfig.ExpirySweepTrigger.class);
            });
        }

        @Test
        void 설정값이_실제_바인딩으로_주입된다() {
            enabled.run(context -> {
                ExpirySweepProperties properties = context.getBean(ExpirySweepProperties.class);
                assertThat(properties.isEnabled()).isTrue();
                assertThat(properties.getPageSize()).isEqualTo(50);
                assertThat(properties.getMaxPages()).isEqualTo(2);
                assertThat(properties.getFixedDelay()).isEqualTo(Duration.ofSeconds(45));
            });
        }

        /** ★★ 등록은 정확히 하나, 주기는 설정값, 그리고 서비스 호출은 0회. */
        @Test
        void fixedDelay_작업_하나만_등록되고_스스로_실행되지_않는다() {
            enabled.run(context -> {
                CapturingTaskScheduler capture = context.getBean(CapturingTaskScheduler.class);
                RecordingSweeper sweeper = context.getBean(RecordingSweeper.class);

                assertThat(capture.registrations())
                        .as("★ 등록되는 작업은 하나뿐이다")
                        .singleElement()
                        .satisfies(r -> {
                            assertThat(r.kind()).isEqualTo(Kind.FIXED_DELAY);
                            assertThat(r.period())
                                    .as("주기는 railgate.expiry.sweep.fixed-delay 다")
                                    .isEqualTo(Duration.ofSeconds(45));
                            assertThat(r.startTime())
                                    .as("★ initialDelay 가 없어 시작 시각이 등록 시각이다 — "
                                            + "실제 스케줄러라면 첫 실행이 즉시 일어난다")
                                    .isNotNull()
                                    .isBeforeOrEqualTo(Instant.now());
                        });
                assertThat(scheduledTasks(context))
                        .as("Spring 이 관리하는 작업 수와 포착한 등록 수가 같다 — 다른 스케줄러로 간 작업이 없다")
                        .hasSize(capture.registrations().size());

                assertThat(sweeper.requests)
                        .as("★★ 포착만 했으므로 서비스 호출은 0회다")
                        .isEmpty();
            });
        }

        /** ★ 테스트 전용 스케줄러 외에 실행 수단이 컨텍스트에 없다 — 백그라운드 실행이 구조적으로 불가능하다. */
        @Test
        void 실제로_실행할_스케줄러가_컨텍스트에_없다() {
            enabled.run(context -> {
                assertThat(context.getBeansOfType(TaskScheduler.class))
                        .as("TaskScheduler 는 포착용 하나뿐이다")
                        .containsOnlyKeys("taskScheduler")
                        .allSatisfy((name, s) -> assertThat(s).isInstanceOf(CapturingTaskScheduler.class));
                assertThat(context.getBeansOfType(ScheduledExecutorService.class))
                        .as("Spring 이 대신 쓸 ScheduledExecutorService 도 없다")
                        .isEmpty();
            });
        }

        /** ★ 포착한 작업을 명시적으로 부르면 어댑터의 tick 으로 전달된다. */
        @Test
        void 포착한_작업을_실행하면_tick_으로_전달된다() {
            enabled.run(context -> {
                CapturingTaskScheduler capture = context.getBean(CapturingTaskScheduler.class);
                RecordingSweeper sweeper = context.getBean(RecordingSweeper.class);
                MeterRegistry meters = context.getBean(MeterRegistry.class);

                Registration registration = capture.registrations().getFirst();
                registration.task().run();

                assertThat(sweeper.requests).singleElement().satisfies(request -> {
                    assertThat(request.pageSize()).isEqualTo(50);
                    assertThat(request.maxPages()).isEqualTo(2);
                    assertThat(request.startAfter()).as("첫 tick 은 처음부터").isEmpty();
                });
                assertThat(meters.counter("railgate.expiry.sweep", "outcome", "success").count())
                        .isEqualTo(1.0);

                registration.task().run();
                assertThat(sweeper.requests).as("부를 때마다 tick 하나").hasSize(2);
            });
        }

        /** Spring 이 컨텍스트 종료 시 등록한 작업을 취소한다 — 반환한 future 가 그 수명주기를 받는다. */
        @Test
        void 컨텍스트를_닫으면_등록된_작업이_취소된다() {
            List<Registration> captured = new ArrayList<>();
            enabled.run(context -> {
                captured.addAll(context.getBean(CapturingTaskScheduler.class).registrations());
                assertThat(captured).allSatisfy(r -> assertThat(r.future().isCancelled()).isFalse());
            });

            assertThat(captured).isNotEmpty()
                    .allSatisfy(r -> assertThat(r.future().isCancelled())
                            .as("★ 종료 시 Spring 이 cancel 을 불렀다").isTrue());
        }
    }

    /** ★ 경량 컨텍스트에서도 끄면 스케줄 등록 자체가 없다. */
    @Nested
    @DisplayName("§3 ★ 비활성 — 등록 없음")
    class 비활성_등록_없음 {

        @Test
        void 속성이_없으면_만료_작업을_등록하지_않는다() {
            lightweightContext().run(context -> assertNoExpiryRegistration(context));
        }

        @Test
        void 명시적으로_끄면_만료_작업을_등록하지_않는다() {
            lightweightContext()
                    .withPropertyValues(PREFIX + "enabled=false")
                    .run(context -> assertNoExpiryRegistration(context));
        }

        /** 끄여 있으면 잘못된 값이어도 기동을 막지 않는다 — 끄는 것이 곧 위험이 되면 안 된다. */
        @Test
        void 끄면_잘못된_값이어도_기동한다() {
            lightweightContext()
                    .withPropertyValues(PREFIX + "enabled=false", PREFIX + "page-size=0")
                    .run(context -> assertNoExpiryRegistration(context));
        }

        private void assertNoExpiryRegistration(
                org.springframework.boot.test.context.assertj.AssertableApplicationContext context) {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ExpirySweepScheduler.class);
            assertThat(context).doesNotHaveBean(ExpirySchedulerConfig.ExpirySweepTrigger.class);
            assertThat(context.containsBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME))
                    .as("@EnableScheduling 이 적용되지 않았다").isFalse();
            assertThat(context.getBean(CapturingTaskScheduler.class).registrations())
                    .as("★ 스케줄러가 있어도 등록되는 작업이 없다").isEmpty();
            assertThat(context.getBean(RecordingSweeper.class).requests).isEmpty();
        }
    }

    /** ★ 켠 상태의 잘못된 값은 실제 프로퍼티 바인딩을 거쳐 <b>컨텍스트 시작 실패</b>로 드러난다. */
    @Nested
    @DisplayName("§4 ★ 잘못된 설정 거부 — 컨텍스트 시작 실패")
    class 잘못된_설정_거부 {

        @ParameterizedTest(name = "{0}={1} → {2}")
        @CsvSource({
                "page-size,   0,  pageSize",
                "max-pages,   -1, maxPages",
                "fixed-delay, 0s, fixedDelay"
        })
        void 양수가_아닌_값은_기동을_실패시킨다(String key, String value, String field) {
            lightweightContext()
                    .withPropertyValues(PREFIX + "enabled=true", PREFIX + key + "=" + value)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(rootCause(context.getStartupFailure()))
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessageContaining(field);
                    });
        }

        @Test
        void 해석할_수_없는_간격은_바인딩_단계에서_실패한다() {
            lightweightContext()
                    .withPropertyValues(PREFIX + "enabled=true", PREFIX + "fixed-delay=not-a-duration")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(causes(context.getStartupFailure()))
                                .as("설정 바인딩 실패가 원인 사슬에 있다")
                                .hasAtLeastOneElementOfType(BindException.class);
                    });
        }

        private List<Throwable> causes(Throwable t) {
            List<Throwable> chain = new ArrayList<>();
            for (Throwable c = t; c != null && !chain.contains(c); c = c.getCause()) {
                chain.add(c);
            }
            return chain;
        }

        private Throwable rootCause(Throwable t) {
            Throwable cause = t;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            return cause;
        }
    }

    // ------------------------------------------------------------------ 지원

    private static List<ScheduledTask> scheduledTasks(ApplicationContext context) {
        return context.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                .flatMap(holder -> holder.getScheduledTasks().stream())
                .toList();
    }

    /** 호출을 기록하고 순회 완료 결과를 돌려준다. */
    static final class RecordingSweeper implements ExpirySweeper {
        final List<ExpirySweepRequest> requests = new CopyOnWriteArrayList<>();

        @Override
        public ExpirySweepResult sweep(ExpirySweepRequest request) {
            requests.add(request);
            return new ExpirySweepResult(0, 0, java.util.Map.of(), List.of(), 0, List.of(), 1, 1,
                    Optional.empty());
        }
    }

    enum Kind { TRIGGER, ONE_TIME, FIXED_RATE, FIXED_DELAY }

    /** 포착한 등록 한 건. {@code startTime} 이 {@code null} 이면 시작 시각 없이 등록된 것이다. */
    record Registration(Kind kind, Runnable task, Duration period, Instant startTime, CapturedFuture future) {
    }

    /**
     * ★ 등록을 <b>기록만 하고 실행하지 않는</b> 스케줄러.
     *
     * <p>실행기·스레드·타이머를 갖지 않는다 — 포착한 작업은 테스트가 {@code task().run()} 을
     * 부를 때만 돈다. 반환하는 {@link CapturedFuture} 는 Spring 이 종료 시 부르는 {@code cancel} 과
     * 조회하는 {@code getDelay}·{@code isCancelled} 를 지원한다.
     */
    static final class CapturingTaskScheduler implements TaskScheduler {

        private final List<Registration> registrations = new CopyOnWriteArrayList<>();

        List<Registration> registrations() {
            return List.copyOf(registrations);
        }

        private ScheduledFuture<?> capture(Kind kind, Runnable task, Duration period, Instant startTime) {
            CapturedFuture future = new CapturedFuture(period == null ? Duration.ZERO : period);
            registrations.add(new Registration(kind, task, period, startTime, future));
            return future;
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
            return capture(Kind.TRIGGER, task, null, null);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
            return capture(Kind.ONE_TIME, task, null, startTime);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
            return capture(Kind.FIXED_RATE, task, period, startTime);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
            return capture(Kind.FIXED_RATE, task, period, null);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, Duration delay) {
            return capture(Kind.FIXED_DELAY, task, delay, startTime);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
            return capture(Kind.FIXED_DELAY, task, delay, null);
        }
    }

    /** 스스로 완료되지 않는 반복 작업의 핸들. 취소만 상태를 바꾼다. */
    static final class CapturedFuture implements ScheduledFuture<Object> {

        private final Duration delay;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        CapturedFuture(Duration delay) {
            this.delay = delay;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(delay);
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS));
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return cancelled.compareAndSet(false, true);
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public boolean isDone() {
            return cancelled.get();
        }

        @Override
        public Object get() {
            if (cancelled.get()) {
                throw new CancellationException("취소된 작업");
            }
            throw new IllegalStateException("포착한 반복 작업은 스스로 완료되지 않는다");
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            return get();
        }
    }
}
