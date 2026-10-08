package com.railgate.reservation.application.expiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.railgate.reservation.expiry.ExpiryCursor;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 스케줄러 어댑터의 <b>호출자 계약</b>을 DB·실제 시간 없이 고정한다.
 *
 * <p>검증 대상은 "서비스를 어떤 요청으로 몇 번 부르는가" 다. 실제 회수 동작은
 * {@code ExpireHoldsService} 쪽 테스트가 담당한다.
 *
 * <p><b>{@code Thread.sleep} 으로 주기를 기다리지 않는다.</b> {@code tick()} 을 손으로 부른다 —
 * 스케줄 등록은 Spring 이 하고, 어댑터가 보장해야 하는 것은 tick 하나의 계약이다.
 */
@DisplayName("ExpirySweepScheduler — 호출자 계약")
class ExpirySweepSchedulerTest {

    private RecordingSweeper sweeper;
    private MeterRegistry meters;

    @BeforeEach
    void setUp() {
        sweeper = new RecordingSweeper();
        meters = new SimpleMeterRegistry();
    }

    private ExpirySweepProperties props() {
        ExpirySweepProperties p = new ExpirySweepProperties();
        p.setEnabled(true);
        p.setFixedDelay(Duration.ofSeconds(10));
        p.setPageSize(2);
        p.setMaxPages(3);
        return p;
    }

    private ExpirySweepScheduler scheduler() {
        return new ExpirySweepScheduler(sweeper, props(), meters);
    }

    private static ExpiryCursor cursor(long seatId) {
        return new ExpiryCursor(LocalDateTime.of(2026, 1, 1, 0, 0), seatId);
    }

    private static ExpirySweepResult result(int reclaimed, Optional<ExpiryCursor> next) {
        return new ExpirySweepResult(reclaimed, reclaimed, Map.of(), List.of(), 0, List.of(),
                1, 1, next);
    }

    @Nested
    @DisplayName("§1 ★ 커서 전달과 순회 초기화")
    class 커서_전달 {

        /** ★ 첫 tick 은 처음부터 — 커서 없이 시작한다. */
        @Test
        void 첫_tick_은_처음부터_조회한다() {
            sweeper.queue.add(result(2, Optional.empty()));

            scheduler().tick();

            assertThat(sweeper.requests).singleElement().satisfies(r -> {
                assertThat(r.startAfter()).isEmpty();
                assertThat(r.pageSize()).isEqualTo(2);
                assertThat(r.maxPages()).isEqualTo(3);
            });
        }

        /** ★★ 예산 소진 → nextCursor 를 <b>다음 tick 에 이어 전달</b>한다. */
        @Test
        void nextCursor_가_있으면_다음_tick_이_이어받는다() {
            sweeper.queue.add(result(2, Optional.of(cursor(50))));
            sweeper.queue.add(result(1, Optional.of(cursor(80))));
            sweeper.queue.add(result(0, Optional.empty()));
            ExpirySweepScheduler scheduler = scheduler();

            scheduler.tick();
            scheduler.tick();
            scheduler.tick();

            assertThat(sweeper.requests).hasSize(3);
            assertThat(sweeper.requests.get(0).startAfter()).isEmpty();
            assertThat(sweeper.requests.get(1).startAfter()).contains(cursor(50));
            assertThat(sweeper.requests.get(2).startAfter()).contains(cursor(80));
        }

        /** ★★ 순회 완료 → 다음 tick 은 <b>처음부터</b>. 실패 후보를 재확인한다. */
        @Test
        void 순회가_끝나면_다음_tick_은_처음부터_시작한다() {
            sweeper.queue.add(result(2, Optional.of(cursor(50))));
            sweeper.queue.add(result(1, Optional.empty()));   // 순회 완료
            sweeper.queue.add(result(0, Optional.empty()));
            ExpirySweepScheduler scheduler = scheduler();

            scheduler.tick();
            scheduler.tick();
            scheduler.tick();

            assertThat(sweeper.requests.get(1).startAfter()).contains(cursor(50));
            assertThat(sweeper.requests.get(2).startAfter())
                    .as("★ 순회가 끝났으므로 커서를 버린다").isEmpty();
        }

        /** ★ 한 tick 은 sweep 을 <b>정확히 한 번</b> 부른다. backlog 를 비우는 루프가 없다. */
        @Test
        void 한_tick_은_sweep_을_한_번만_부른다() {
            sweeper.queue.add(result(2, Optional.of(cursor(50))));

            scheduler().tick();

            assertThat(sweeper.requests).hasSize(1);
        }

        /** ★ 그룹 실패·손상 후보가 있어도 정상 반환된 커서는 이어간다. */
        @Test
        void 실패_그룹과_손상_후보가_있어도_커서를_이어받는다() {
            ExpirySweepResult withFailures = new ExpirySweepResult(
                    5, 1,
                    Map.of(),
                    List.of(new ExpirySweepResult.GroupFailure(
                            new QuotaGroupKey(new com.railgate.reservation.saleevent.SaleEventId(1L),
                                    new com.railgate.reservation.UserId(2L)),
                            GroupFailureKind.QUOTA_ROW_MISSING, "행 없음", 1)),
                    0,
                    List.of(new ExpirySweepResult.CorruptCandidate(
                            new com.railgate.reservation.seat.SeatId(9L), "held_by 없음")),
                    1, 1, Optional.of(cursor(70)));
            sweeper.queue.add(withFailures);
            sweeper.queue.add(result(0, Optional.empty()));
            ExpirySweepScheduler scheduler = scheduler();

            scheduler.tick();
            scheduler.tick();

            assertThat(sweeper.requests.get(1).startAfter())
                    .as("★ 실패가 있어도 진행은 멈추지 않는다").contains(cursor(70));
        }
    }

    @Nested
    @DisplayName("§2 ★ 예외 처리")
    class 예외_처리 {

        /** ★★ 호출이 실패하면 커서를 <b>전진시키지 않는다.</b> 다음 tick 이 같은 위치를 다시 본다. */
        @Test
        void 예외가_나면_커서를_전진시키지_않는다() {
            sweeper.queue.add(result(2, Optional.of(cursor(50))));
            sweeper.failures.put(2, new IllegalStateException("배치 실패"));
            sweeper.queue.add(result(1, Optional.empty()));
            ExpirySweepScheduler scheduler = scheduler();

            scheduler.tick();                 // 커서 50 저장
            scheduler.tick();                 // 실패
            scheduler.tick();

            assertThat(sweeper.requests.get(1).startAfter()).contains(cursor(50));
            assertThat(sweeper.requests.get(2).startAfter())
                    .as("★★ 실패했으므로 같은 커서로 재개한다").contains(cursor(50));
        }

        /**
         * ★ 예외를 tick 밖으로 던지지 않고 실패를 계측한다.
         *
         * <p>tick 을 손으로 부르므로 Spring 스케줄러의 오류 처리({@code ErrorHandler})는 거치지 않는다 —
         * 검증 대상은 어댑터가 실패를 직접 처리한다는 계약이지 Spring 의 동작이 아니다.
         */
        @Test
        void 예외를_삼키지_않고_로깅_후_다음_tick_을_살린다() {
            sweeper.failures.put(1, new IllegalStateException("배치 실패"));
            sweeper.queue.add(result(1, Optional.empty()));
            ExpirySweepScheduler scheduler = scheduler();

            scheduler.tick();   // 예외가 밖으로 나오면 안 된다
            scheduler.tick();

            assertThat(sweeper.requests).hasSize(2);
            assertThat(meters.counter("railgate.expiry.sweep", "outcome", "failure").count())
                    .isEqualTo(1.0);
        }

        /** ★ 인터럽트는 삼키지 않는다 — 플래그를 복원하고 종료 요청을 존중한다. */
        @Test
        void 인터럽트는_플래그를_복원한다() {
            sweeper.failures.put(1, new IllegalStateException("중단", new InterruptedException()));
            ExpirySweepScheduler scheduler = scheduler();

            try {
                scheduler.tick();
                assertThat(Thread.currentThread().isInterrupted()).as("★ 플래그 복원").isTrue();
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Nested
    @DisplayName("§3 ★ 실행 중첩 방지")
    class 실행_중첩_방지 {

        /** ★★ 한 인스턴스 안에서 tick 이 겹치면 늦은 쪽은 건너뛴다. */
        @Test
        void 실행이_겹치면_늦은_tick_은_건너뛴다() throws Exception {
            CountDownLatch inside = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            BlockingSweeper blocking = new BlockingSweeper(inside, release);
            ExpirySweepScheduler scheduler = new ExpirySweepScheduler(blocking, props(), meters);

            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<?> first = pool.submit(scheduler::tick);
                assertThat(inside.await(5, TimeUnit.SECONDS)).as("첫 tick 이 안에 들어갔다").isTrue();

                scheduler.tick();   // 겹친 호출 — 건너뛰어야 한다

                release.countDown();
                first.get(5, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }

            assertThat(blocking.calls.get()).as("★★ sweep 은 한 번만 불렸다").isEqualTo(1);
            assertThat(meters.counter("railgate.expiry.sweep", "outcome", "skipped").count())
                    .isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("§4 ★ 설정 검증")
    class 설정_검증 {

        @Test
        void 비활성이면_tick_이_아무것도_하지_않는다() {
            ExpirySweepProperties disabled = props();
            disabled.setEnabled(false);

            new ExpirySweepScheduler(sweeper, disabled, meters).tick();

            assertThat(sweeper.requests).isEmpty();
        }

        @Test
        void 활성화_시_잘못된_값은_생성_단계에서_거부한다() {
            ExpirySweepProperties zeroPage = props();
            zeroPage.setPageSize(0);
            assertThatThrownBy(() -> new ExpirySweepScheduler(sweeper, zeroPage, meters))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("pageSize");

            ExpirySweepProperties zeroPages = props();
            zeroPages.setMaxPages(0);
            assertThatThrownBy(() -> new ExpirySweepScheduler(sweeper, zeroPages, meters))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxPages");

            ExpirySweepProperties zeroDelay = props();
            zeroDelay.setFixedDelay(Duration.ZERO);
            assertThatThrownBy(() -> new ExpirySweepScheduler(sweeper, zeroDelay, meters))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("fixedDelay");
        }

        /** ★ 비활성이면 잘못된 값이어도 기동을 막지 않는다 — 켜지 않은 설정은 검증 대상이 아니다. */
        @Test
        void 비활성이면_잘못된_값이어도_기동을_막지_않는다() {
            ExpirySweepProperties disabled = new ExpirySweepProperties();
            disabled.setEnabled(false);
            disabled.setPageSize(0);

            assertThat(new ExpirySweepScheduler(sweeper, disabled, meters)).isNotNull();
        }
    }

    @Nested
    @DisplayName("§5 ★ 관측")
    class 관측 {

        /** ★ 회수 0건과 stale 은 오류가 아니다. */
        @Test
        void 회수_0건은_성공으로_집계한다() {
            sweeper.queue.add(new ExpirySweepResult(3, 0, Map.of(), List.of(), 3, List.of(), 1, 1,
                    Optional.empty()));

            scheduler().tick();

            assertThat(meters.counter("railgate.expiry.sweep", "outcome", "success").count())
                    .isEqualTo(1.0);
            assertThat(meters.counter("railgate.expiry.sweep", "outcome", "failure").count())
                    .isZero();
            assertThat(meters.counter("railgate.expiry.seats.reclaimed").count()).isZero();
        }

        @Test
        void 회수_수와_실패_그룹_손상_후보를_각각_센다() {
            ExpirySweepResult r = new ExpirySweepResult(
                    4, 2, Map.of(),
                    List.of(new ExpirySweepResult.GroupFailure(
                            new QuotaGroupKey(new com.railgate.reservation.saleevent.SaleEventId(1L),
                                    new com.railgate.reservation.UserId(2L)),
                            GroupFailureKind.QUOTA_DECREASE_REJECTED, "거절", 1)),
                    0,
                    List.of(new ExpirySweepResult.CorruptCandidate(
                            new com.railgate.reservation.seat.SeatId(9L), "held_by 없음")),
                    1, 1, Optional.empty());
            sweeper.queue.add(r);

            scheduler().tick();

            assertThat(meters.counter("railgate.expiry.seats.reclaimed").count()).isEqualTo(2.0);
            assertThat(meters.counter("railgate.expiry.groups.failed",
                    "kind", "QUOTA_DECREASE_REJECTED").count()).isEqualTo(1.0);
            assertThat(meters.counter("railgate.expiry.candidates.corrupt").count()).isEqualTo(1.0);
        }

        /** ★ 메트릭 태그에 사용자·좌석·홀드 ID 나 예외 메시지를 쓰지 않는다. */
        @Test
        void 메트릭_태그에_식별자나_예외_메시지를_쓰지_않는다() {
            sweeper.failures.put(1, new IllegalStateException("사용자 42 의 좌석 9001 실패"));

            scheduler().tick();

            List<String> tagValues = new ArrayList<>();
            meters.getMeters().forEach(m -> m.getId().getTags()
                    .forEach(t -> tagValues.add(t.getValue())));
            assertThat(tagValues)
                    .allSatisfy(v -> assertThat(v).doesNotContain("42").doesNotContain("9001"));
            assertThat(tagValues).noneMatch(v -> v.contains("사용자"));
        }
    }

    // ------------------------------------------------------------------ 스텁

    /** 요청을 기록하고 미리 넣어 둔 결과를 순서대로 돌려준다. */
    private static final class RecordingSweeper implements ExpirySweeper {
        private final List<ExpirySweepRequest> requests = new ArrayList<>();
        private final List<ExpirySweepResult> queue = new ArrayList<>();
        private final Map<Integer, RuntimeException> failures = new java.util.HashMap<>();

        @Override
        public ExpirySweepResult sweep(ExpirySweepRequest request) {
            requests.add(request);
            RuntimeException failure = failures.get(requests.size());
            if (failure != null) {
                throw failure;
            }
            return queue.isEmpty()
                    ? result(0, Optional.empty())
                    : queue.remove(0);
        }
    }

    /** 첫 호출에서 멈춰 중첩 상황을 결정적으로 만든다. */
    private static final class BlockingSweeper implements ExpirySweeper {
        private final CountDownLatch inside;
        private final CountDownLatch release;
        private final AtomicInteger calls = new AtomicInteger();

        private BlockingSweeper(CountDownLatch inside, CountDownLatch release) {
            this.inside = inside;
            this.release = release;
        }

        @Override
        public ExpirySweepResult sweep(ExpirySweepRequest request) {
            calls.incrementAndGet();
            inside.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return result(0, Optional.empty());
        }
    }
}
