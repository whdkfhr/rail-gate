package com.railgate.reservation.infra.saleevent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.railgate.reservation.infra.MySqlTestSupport;
import com.railgate.reservation.saleevent.SaleEvent;
import com.railgate.reservation.saleevent.SaleEventId;
import com.railgate.reservation.saleevent.SaleEventStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * 판매 회차 저장소 — 조회·복원과 조건부 상태 전이 (Task 2G-E-B2).
 *
 * <h2>도메인 전이와 저장소 전이의 관계</h2>
 *
 * <p>{@code SaleEvent.open(now)} 은 <b>단일 스레드 객체의 규칙</b>이고
 * (2G-E-A §3: "동시 전이를 막지 않는다"), 실제 방어선은 여기의 조건부 UPDATE 다.
 * 두 요청이 동시에 열어도 {@code affected_rows = 1} 인 쪽은 하나뿐이어야 한다.
 *
 * <h2>시각</h2>
 *
 * <p>오픈 판정은 <b>DB 의 {@code NOW(3)}</b> 이 한다 (CLAUDE.md 규칙 7).
 * 애플리케이션 시각을 넘기면 인스턴스 간 클럭 드리프트가 판정에 섞인다.
 * 그래서 {@code open()} 은 시각 파라미터를 받지 않는다 — 도메인의
 * {@code open(Instant now)} 와 서명이 다른 이유다.
 */
@Timeout(180)
@DisplayName("JdbcSaleEventRepository — 조회와 조건부 전이 (Task 2G-E-B2)")
class JdbcSaleEventRepositoryTest extends MySqlTestSupport {

    private static final SaleEventId CHUSEOK = new SaleEventId(9001L);
    private static final String NAME = "2026년 추석 승차권 판매";

    private JdbcSaleEventRepository repository;

    @BeforeEach
    void setUp() {
        repository = new JdbcSaleEventRepository(dataSource());
    }

    /** 오픈 시각이 이미 지난 회차. */
    private static void insertScheduledOpenable(SaleEventId id) {
        jdbc().update("""
                INSERT INTO sale_event (id, name, opens_at, closes_at, status)
                VALUES (?, ?, DATE_SUB(NOW(3), INTERVAL 1 MINUTE), NULL, 'SCHEDULED')
                """, id.value(), NAME);
    }

    /** 아직 오픈 시각이 오지 않은 회차. */
    private static void insertScheduledFuture(SaleEventId id) {
        jdbc().update("""
                INSERT INTO sale_event (id, name, opens_at, closes_at, status)
                VALUES (?, ?, DATE_ADD(NOW(3), INTERVAL 1 HOUR), NULL, 'SCHEDULED')
                """, id.value(), NAME);
    }

    private static String statusOf(SaleEventId id) {
        return jdbc().queryForObject(
                "SELECT status FROM sale_event WHERE id = ?", String.class, id.value());
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("조회와 복원")
    class 조회 {

        @Test
        void 저장된_회차를_도메인_객체로_복원한다() {
            insertScheduledOpenable(CHUSEOK);

            Optional<SaleEvent> found = repository.findById(CHUSEOK);

            assertThat(found).isPresent();
            SaleEvent event = found.orElseThrow();
            assertThat(event.id()).isEqualTo(CHUSEOK);
            assertThat(event.name()).isEqualTo(NAME);
            assertThat(event.status()).isEqualTo(SaleEventStatus.SCHEDULED);
            assertThat(event.opensAt()).isNotNull();
            assertThat(event.closesAt()).isNull();
        }

        @Test
        void 마감_시각이_있는_회차도_복원한다() {
            jdbc().update("""
                    INSERT INTO sale_event (id, name, opens_at, closes_at, status)
                    VALUES (?, ?, '2026-09-01 09:00:00.000', '2026-09-10 09:00:00.000', 'OPEN')
                    """, CHUSEOK.value(), NAME);

            SaleEvent event = repository.findById(CHUSEOK).orElseThrow();

            assertThat(event.status()).isEqualTo(SaleEventStatus.OPEN);
            assertThat(event.closesAt()).isEqualTo(Instant.parse("2026-09-10T09:00:00Z"));
            assertThat(event.isOpen()).isTrue();
        }

        @Test
        void 없는_회차는_비어_있다() {
            assertThat(repository.findById(new SaleEventId(404L))).isEmpty();
        }

        /** 복원은 전이를 재실행하지 않는다 (2G-E-A §5). 미래 오픈 시각의 OPEN 행도 읽힌다. */
        @Test
        void 오픈_시각이_미래인_OPEN_행도_그대로_복원한다() {
            jdbc().update("""
                    INSERT INTO sale_event (id, name, opens_at, status)
                    VALUES (?, ?, '2099-01-01 00:00:00.000', 'OPEN')
                    """, CHUSEOK.value(), NAME);

            SaleEvent event = repository.findById(CHUSEOK).orElseThrow();

            assertThat(event.status()).isEqualTo(SaleEventStatus.OPEN);
        }
    }

    @Nested
    @DisplayName("오픈 — 조건부 UPDATE")
    class 오픈 {

        @Test
        void 오픈_시각이_지난_SCHEDULED_회차를_연다() {
            insertScheduledOpenable(CHUSEOK);

            assertThat(repository.open(CHUSEOK)).isEqualTo(SaleEventTransitionOutcome.APPLIED);
            assertThat(statusOf(CHUSEOK)).isEqualTo("OPEN");
        }

        /** ★ 오픈 시각 판정은 DB 시각이 한다. 애플리케이션이 우길 수 없다. */
        @Test
        void 오픈_시각_이전에는_열리지_않는다() {
            insertScheduledFuture(CHUSEOK);

            assertThat(repository.open(CHUSEOK)).isEqualTo(SaleEventTransitionOutcome.NOT_APPLIED);
            assertThat(statusOf(CHUSEOK)).isEqualTo("SCHEDULED");
        }

        @Test
        void 이미_OPEN_인_회차를_다시_열_수_없다() {
            insertScheduledOpenable(CHUSEOK);
            repository.open(CHUSEOK);

            assertThat(repository.open(CHUSEOK)).isEqualTo(SaleEventTransitionOutcome.NOT_APPLIED);
            assertThat(statusOf(CHUSEOK)).isEqualTo("OPEN");
        }

        @Test
        void CLOSED_회차는_다시_열리지_않는다() {
            insertScheduledOpenable(CHUSEOK);
            repository.open(CHUSEOK);
            repository.close(CHUSEOK);

            assertThat(repository.open(CHUSEOK)).isEqualTo(SaleEventTransitionOutcome.NOT_APPLIED);
            assertThat(statusOf(CHUSEOK)).isEqualTo("CLOSED");
        }

        @Test
        void 없는_회차를_열면_적용되지_않는다() {
            assertThat(repository.open(new SaleEventId(404L)))
                    .isEqualTo(SaleEventTransitionOutcome.NOT_APPLIED);
        }
    }

    @Nested
    @DisplayName("마감 — 조건부 UPDATE")
    class 마감 {

        @Test
        void OPEN_회차를_마감한다() {
            insertScheduledOpenable(CHUSEOK);
            repository.open(CHUSEOK);

            assertThat(repository.close(CHUSEOK)).isEqualTo(SaleEventTransitionOutcome.APPLIED);
            assertThat(statusOf(CHUSEOK)).isEqualTo("CLOSED");
        }

        /** 2G-E-A §3: SCHEDULED → CLOSED 는 전이표에 없다. SQL 도 같은 계약을 지킨다. */
        @Test
        void SCHEDULED_회차는_마감할_수_없다() {
            insertScheduledOpenable(CHUSEOK);

            assertThat(repository.close(CHUSEOK)).isEqualTo(SaleEventTransitionOutcome.NOT_APPLIED);
            assertThat(statusOf(CHUSEOK)).isEqualTo("SCHEDULED");
        }

        @Test
        void 이미_CLOSED_인_회차를_다시_닫을_수_없다() {
            insertScheduledOpenable(CHUSEOK);
            repository.open(CHUSEOK);
            repository.close(CHUSEOK);

            assertThat(repository.close(CHUSEOK)).isEqualTo(SaleEventTransitionOutcome.NOT_APPLIED);
        }

        /** 마감에는 시각 조건이 없다 (2G-E-A §3). 조기 마감은 정상 운영 행위다. */
        @Test
        void 마감_예정_시각_전에도_마감할_수_있다() {
            jdbc().update("""
                    INSERT INTO sale_event (id, name, opens_at, closes_at, status)
                    VALUES (?, ?, DATE_SUB(NOW(3), INTERVAL 1 MINUTE),
                            DATE_ADD(NOW(3), INTERVAL 30 DAY), 'OPEN')
                    """, CHUSEOK.value(), NAME);

            assertThat(repository.close(CHUSEOK)).isEqualTo(SaleEventTransitionOutcome.APPLIED);
        }
    }

    /**
     * ★ 동시 전이 — 도메인이 막지 못하는 것을 여기서 막는다.
     *
     * <p>{@code CountDownLatch} 로 동시 출발시킨다. {@code Thread.sleep} 은 쓰지 않는다
     * (CLAUDE.md 규칙 25).
     */
    @Nested
    @DisplayName("★ 동시 전이")
    class 동시_전이 {

        private static final int WORKERS = 16;

        /**
         * 경합이 끝나기를 기다리는 상한.
         *
         * <p>정상 상황에서는 1초 안에 끝난다. 넉넉히 잡되, <b>망가졌을 때 빨리 실패하도록</b>
         * 짧게 둔다. 한 행을 두고 16개 UPDATE 가 직렬화될 뿐이고
         * {@code innodb_lock_wait_timeout} 도 3초다.
         */
        private static final int TIMEOUT_SECONDS = 15;

        @Test
        void 동시에_열어도_정확히_하나만_적용된다() throws Exception {
            insertScheduledOpenable(CHUSEOK);

            List<SaleEventTransitionOutcome> outcomes = race(worker -> repository.open(CHUSEOK));

            assertThat(appliedCount(outcomes))
                    .as("affected_rows = 1 인 요청은 하나뿐이어야 한다")
                    .isEqualTo(1);
            assertThat(statusOf(CHUSEOK)).isEqualTo("OPEN");
        }

        @Test
        void 동시에_마감해도_정확히_하나만_적용된다() throws Exception {
            insertScheduledOpenable(CHUSEOK);
            repository.open(CHUSEOK);

            List<SaleEventTransitionOutcome> outcomes = race(worker -> repository.close(CHUSEOK));

            assertThat(appliedCount(outcomes)).isEqualTo(1);
            assertThat(statusOf(CHUSEOK)).isEqualTo("CLOSED");
        }

        /**
         * 오픈과 마감이 동시에 와도 중간 상태가 남지 않는다.
         *
         * <p><b>{@code SCHEDULED} 로 끝나는 경우는 없다.</b> 마감은 {@code OPEN} 에서만 걸리므로
         * {@code SCHEDULED} 를 바꾸지 못하고, 오픈 요청 8개 중 하나는 반드시 성공한다.
         * 따라서 최종 상태와 적용 횟수가 정확히 대응한다.
         *
         * <pre>
         *   최종 OPEN    → 적용 1회 (open)
         *   최종 CLOSED  → 적용 2회 (open 1 + close 1)
         * </pre>
         *
         * <p>오픈이 두 번 적용될 수는 없다. 그러려면 상태가 {@code SCHEDULED} 로 돌아와야 하는데
         * 그런 전이가 없다. 마감도 같은 이유로 한 번뿐이다.
         */
        @Test
        void 오픈과_마감이_경합해도_최종_상태는_둘_중_하나다() throws Exception {
            insertScheduledOpenable(CHUSEOK);

            List<SaleEventTransitionOutcome> outcomes = race(worker ->
                    worker % 2 == 0 ? repository.open(CHUSEOK) : repository.close(CHUSEOK));

            String finalStatus = statusOf(CHUSEOK);
            assertThat(finalStatus)
                    .as("마감은 SCHEDULED 를 바꾸지 못하므로 오픈 하나는 반드시 성공한다")
                    .isIn("OPEN", "CLOSED");
            assertThat(appliedCount(outcomes))
                    .as("최종 %s 상태에 대응하는 적용 횟수여야 한다", finalStatus)
                    .isEqualTo("OPEN".equals(finalStatus) ? 1 : 2);
            assertThat(repository.findById(CHUSEOK)).isPresent();
        }

        private static long appliedCount(List<SaleEventTransitionOutcome> outcomes) {
            return outcomes.stream().filter(o -> o == SaleEventTransitionOutcome.APPLIED).count();
        }

        /**
         * {@link #WORKERS} 개의 요청을 동시 출발시키고 <b>모든 결과를 모은다.</b>
         *
         * <p><b>{@code submit} 이 돌려준 {@link Future} 를 반드시 확인한다.</b> 확인하지 않으면
         * 작업 안에서 난 예외가 그 스레드에서 삼켜지고, 테스트는 "결과가 없다" 를
         * "경합에서 졌다" 와 구분하지 못한 채 통과한다. {@code Future.get()} 이 그 예외를
         * {@code ExecutionException} 으로 테스트 스레드에 올린다.
         *
         * <p>종료 순서도 지킨다 — {@code shutdown()} 을 부르지 않고 {@code awaitTermination} 을
         * 기다리면 풀이 끝날 리 없어 상한까지 그대로 소진한다.
         *
         * @param action 작업자 번호를 받아 전이를 수행한다
         */
        private List<SaleEventTransitionOutcome> race(
                IntFunction<SaleEventTransitionOutcome> action) throws Exception {

            CountDownLatch ready = new CountDownLatch(WORKERS);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<SaleEventTransitionOutcome>> futures = new ArrayList<>();

            ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
            try {
                for (int i = 0; i < WORKERS; i++) {
                    int worker = i;
                    futures.add(pool.submit(() -> {
                        ready.countDown();
                        start.await();
                        return action.apply(worker);
                    }));
                }

                assertThat(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        .as("작업자 %d개가 모두 출발선에 서야 한다", WORKERS)
                        .isTrue();
                start.countDown();

                pool.shutdown();
                assertThat(pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        .as("경합이 %d초 안에 끝나야 한다", TIMEOUT_SECONDS)
                        .isTrue();
            } finally {
                pool.shutdownNow();
            }

            List<SaleEventTransitionOutcome> outcomes = new ArrayList<>();
            for (Future<SaleEventTransitionOutcome> future : futures) {
                // 작업 안의 예외는 여기서 ExecutionException 으로 터진다.
                outcomes.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
            assertThat(outcomes)
                    .as("모든 작업자가 결과를 돌려줘야 한다")
                    .hasSize(WORKERS)
                    .doesNotContainNull();
            return outcomes;
        }
    }

    @Test
    void 인자를_검증한다() {
        assertThatThrownBy(() -> repository.findById(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> repository.open(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> repository.close(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new JdbcSaleEventRepository(null))
                .isInstanceOf(NullPointerException.class);
    }
}
