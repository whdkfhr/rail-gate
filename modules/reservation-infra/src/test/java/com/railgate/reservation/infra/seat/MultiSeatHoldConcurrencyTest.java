package com.railgate.reservation.infra.seat;

import static org.assertj.core.api.Assertions.assertThat;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.infra.MySqlTestSupport;
import com.railgate.reservation.seat.SeatId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * ★ 다좌석 선점 동시성 — I-9 전부-또는-전무.
 *
 * <p>단일 좌석 경합과 다른 점은 <b>부분 성공</b>이라는 실패 양식이 존재한다는 것이다.
 * 좌석 하나짜리 요청은 성공 아니면 실패지만, 여러 좌석을 묶은 요청은
 * "3석 중 2석만 잡힌 채로 남는" 상태가 가능하다. 그런 상태가 하나라도 생기면 I-9 위반이다.
 *
 * <p>또한 여러 행을 잠그므로 <b>데드락</b>이 새로운 실패 양식으로 등장한다.
 * 서로 겹치는 좌석 집합을 정순과 역순으로 요청해도 교착이 나지 않아야 한다 (CLAUDE.md 규칙 2).
 */
@Timeout(120)
@DisplayName("다좌석 선점 동시성 - I-9")
class MultiSeatHoldConcurrencyTest extends MySqlTestSupport {

    private static final long SCHEDULE_ID = 1L;
    private static final Duration HOLD_DURATION = Duration.ofMinutes(5);
    private static final int SEAT_POOL = 8;

    private JdbcMultiSeatHoldRepository repository;
    private List<Long> seatIds;

    @BeforeEach
    void setUp() {
        repository = new JdbcMultiSeatHoldRepository(dataSource(), HOLD_DURATION);
        seatIds = new ArrayList<>();
        for (int i = 0; i < SEAT_POOL; i++) {
            seatIds.add(insertAvailableSeat(SCHEDULE_ID, (i + 1) + "A"));
        }
    }

    private List<SeatId> seats(int... indexes) {
        List<SeatId> result = new ArrayList<>();
        for (int i : indexes) {
            result.add(new SeatId(seatIds.get(i)));
        }
        return result;
    }

    @Test
    void 겹치는_좌석_요청을_동시에_실행해도_부분_선점이_남지_않는다() throws Exception {
        // 200개 요청이 8석 풀에서 2~4석씩 무작위로 겹치게 잡는다.
        int requests = 200;
        List<List<SeatId>> plans = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            int start = i % (SEAT_POOL - 3);
            int size = 2 + (i % 3);
            int[] idx = new int[size];
            for (int k = 0; k < size; k++) {
                idx[k] = start + k;
            }
            plans.add(seats(idx));
        }

        Result result = race(plans);

        assertThat(result.errors()).as("시스템 오류 / deadlock / lock timeout").isEmpty();
        assertNoPartialHold(result);
    }

    @Test
    void 같은_좌석_집합을_정순과_역순으로_동시_요청해도_교착이_없다() throws Exception {
        // 잠금 순서가 일관되지 않으면 교착 위험이 있는 입력이다. 그 위험을 겨냥한 시나리오다.
        //
        // 실제 행 접근 순서는 IN 파라미터 배열 순서만으로 정해지지 않고
        // 실행 계획과 선택된 인덱스의 영향을 받는다. 그래서 구현은 정렬된 ID 전달과
        // FORCE INDEX (PRIMARY) 를 함께 쓴다.
        //
        // 이 테스트가 통과한다고 데드락 부재가 증명되는 것은 아니다.
        // 이 환경과 실행 횟수에서 관측되지 않았다는 보조 근거다 (CLAUDE.md 규칙 30).
        int requests = 200;
        List<List<SeatId>> plans = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            plans.add(i % 2 == 0 ? seats(0, 1, 2, 3) : seats(3, 2, 1, 0));
        }

        Result result = race(plans);

        assertThat(result.errors())
                .as("데드락(1213)이나 잠금 대기 초과(1205)가 나오면 실패다")
                .isEmpty();
        assertThat(result.succeeded()).as("정확히 한 요청만 4석을 가져간다").isEqualTo(1);
    }

    @Test
    void 성공한_요청의_홀드와_점유자가_DB와_일치한다() throws Exception {
        int requests = 100;
        List<List<SeatId>> plans = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            plans.add(seats(0, 1));
        }

        Result result = race(plans);

        assertThat(result.errors()).as("시스템 오류 / deadlock / lock timeout").isEmpty();
        assertThat(result.succeeded()).isEqualTo(1);
        String winnerHold = result.winners().stream().findFirst().orElseThrow();
        for (int i : new int[] {0, 1}) {
            assertThat(holdIdOf(seatIds.get(i))).isEqualTo(winnerHold);
            assertThat(heldByOf(seatIds.get(i))).isEqualTo(result.winnerUserId());
        }
    }

    @Test
    void 최종_HELD_좌석_수가_성공한_요청의_좌석_수와_정확히_일치한다() throws Exception {
        int requests = 200;
        List<List<SeatId>> plans = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            int start = i % (SEAT_POOL - 2);
            plans.add(seats(start, start + 1, start + 2));
        }

        Result result = race(plans);

        assertThat(result.errors()).isEmpty();
        long held = jdbc().queryForObject(
                "SELECT COUNT(*) FROM seat_inventory WHERE schedule_id = ? AND status = 'HELD'",
                Long.class, SCHEDULE_ID);

        // 각 요청은 3석짜리다. 성공한 요청 수 × 3 이 정확히 HELD 좌석 수여야 한다.
        assertThat(held).isEqualTo(result.succeeded() * 3L);
    }

    // ------------------------------------------------------------------

    /** 홀드별 좌석 수가 요청 크기와 다르면 부분 선점이 남은 것이다. */
    private void assertNoPartialHold(Result result) {
        List<Map<String, Object>> byHold = jdbc().queryForList("""
                SELECT hold_id, COUNT(*) AS seat_count
                  FROM seat_inventory
                 WHERE schedule_id = ? AND status = 'HELD' AND hold_id IS NOT NULL
                 GROUP BY hold_id
                """, SCHEDULE_ID);

        for (Map<String, Object> row : byHold) {
            String holdId = (String) row.get("hold_id");
            long seatCount = ((Number) row.get("seat_count")).longValue();
            Integer requested = result.requestedSize().get(holdId);

            assertThat(requested).as("DB 에 있는 홀드 %s 는 성공한 요청이어야 한다", holdId).isNotNull();
            assertThat(seatCount)
                    .as("홀드 %s 는 %d석을 요청했다. 부분 선점이 남으면 안 된다", holdId, requested)
                    .isEqualTo(requested.longValue());
        }
    }

    private Result race(List<List<SeatId>> plans) throws InterruptedException {
        int n = plans.size();
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        List<String> errors = new CopyOnWriteArrayList<>();
        var winners = ConcurrentHashMap.<String>newKeySet();
        Map<String, Integer> requestedSize = new ConcurrentHashMap<>();
        AtomicInteger winnerUser = new AtomicInteger();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < n; i++) {
                List<SeatId> plan = plans.get(i);
                HoldId holdId = HoldId.newId();
                UserId userId = new UserId(i + 1L);

                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        MultiSeatHoldOutcome outcome = repository.holdAll(plan, holdId, userId);
                        if (outcome == MultiSeatHoldOutcome.HELD) {
                            succeeded.incrementAndGet();
                            winners.add(holdId.asString());
                            requestedSize.put(holdId.asString(), plan.size());
                            winnerUser.set((int) userId.value());
                        } else {
                            failed.incrementAndGet();
                        }
                    } catch (Exception e) {
                        errors.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertThat(ready.await(60, TimeUnit.SECONDS)).as("전원 준비 완료").isTrue();
            start.countDown();
            assertThat(done.await(90, TimeUnit.SECONDS)).as("전원 종료").isTrue();
        }

        return new Result(succeeded.get(), failed.get(), errors, winners, requestedSize,
                winnerUser.get());
    }

    private record Result(
            int succeeded,
            int failed,
            List<String> errors,
            java.util.Set<String> winners,
            Map<String, Integer> requestedSize,
            long winnerUserId) {
    }
}
