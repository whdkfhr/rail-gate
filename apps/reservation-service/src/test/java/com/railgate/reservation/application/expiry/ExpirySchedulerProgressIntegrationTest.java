package com.railgate.reservation.application.expiry;

import static org.assertj.core.api.Assertions.assertThat;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.application.hold.HoldSeatsCommand;
import com.railgate.reservation.application.hold.HoldSeatsService;
import com.railgate.reservation.seat.SeatId;
import com.railgate.reservation.support.MySqlSpringTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * ★★ 스케줄러가 <b>여러 tick 을 거쳐</b> 실패 후보 블록 뒤의 정상 좌석에 도달하는가 (실제 MySQL).
 *
 * <p>한 tick 은 {@code sweep} 을 한 번만 부르므로 실패 후보가 예산을 채우면 <b>한 tick 으로는
 * 통과하지 못한다.</b> 어댑터가 {@code nextCursor} 를 다음 tick 에 이어 전달해야 비로소 도달한다.
 *
 * <p><b>실제 시간을 기다리지 않는다.</b> 스케줄 등록은 Spring 의 몫이고, 여기서는 어댑터를
 * 직접 만들어 {@code tick()} 을 손으로 부른다 — 검증 대상은 tick 사이의 커서 이어가기다.
 * 운영 코드에 테스트 전용 훅을 넣지 않았다.
 */
@DisplayName("만료 스케줄러 — 여러 tick 으로 실패 블록 통과 (실제 MySQL)")
class ExpirySchedulerProgressIntegrationTest extends MySqlSpringTestSupport {

    private static final long SCHEDULE = 7_800L;
    private static final UserId OWNER = new UserId(71L);
    private static final UserId ORPHAN = new UserId(72L);

    /** 후보 SELECT 한 번이 1행만 — 실패 블록을 페이지 단위로 통과하게 만든다. */
    private static final int PAGE_SIZE = 1;
    /** 한 tick 의 예산. 실패 후보 수보다 작게 둬 한 tick 으로는 못 가게 한다. */
    private static final int MAX_PAGES = 2;
    /** 예산(1×2=2)보다 많은 실패 후보. */
    private static final int FAILING_SEATS = 5;

    @Autowired
    private HoldSeatsService holdSeats;

    @Autowired
    private ExpirySweeper sweeper;

    private MeterRegistry meters;
    private ExpirySweepScheduler scheduler;

    @BeforeEach
    void wireScheduler() {
        meters = new SimpleMeterRegistry();
        ExpirySweepProperties props = new ExpirySweepProperties();
        props.setEnabled(true);
        props.setFixedDelay(Duration.ofSeconds(30));   // 쓰이지 않는다 — tick 을 손으로 부른다
        props.setPageSize(PAGE_SIZE);
        props.setMaxPages(MAX_PAGES);
        scheduler = new ExpirySweepScheduler(sweeper, props, meters);
    }

    private void expireSecondsAgo(long seatId, int seconds) {
        jdbc().update(
                "UPDATE seat_inventory SET expires_at = DATE_SUB(NOW(3), INTERVAL ? SECOND) WHERE id = ?",
                seconds, seatId);
    }

    /** 선점 경로를 거치지 않은 만료 HELD — quota 행이 없어 그룹이 실패로 남는다. */
    private void expiredHeldWithoutQuota(UserId user, long seatId, int secondsAgo) {
        jdbc().update("""
                UPDATE seat_inventory
                   SET status = 'HELD', hold_id = ?, held_by = ?, held_at = NOW(3),
                       expires_at = DATE_SUB(NOW(3), INTERVAL ? SECOND)
                 WHERE id = ?
                """, HoldId.newId().asString(), user.value(), secondsAgo, seatId);
    }

    private double counter(String name, String... tags) {
        return meters.counter(name, tags).count();
    }

    /**
     * ★★ 실패 후보 {@value #FAILING_SEATS} 석(예산 1×2=2 보다 많다) + 그 뒤 정상 좌석 1석.
     *
     * <p>tick 을 반복하면 커서가 이어져 유한한 횟수 안에 정상 좌석이 회수된다.
     * 실패 후보의 데이터는 끝까지 보존되고 quota 행도 만들어지지 않는다.
     */
    @Test
    void 실패_후보가_예산을_채워도_여러_tick_을_거쳐_뒤의_정상_좌석이_회수된다() {
        List<Long> failing = new ArrayList<>();
        for (int i = 0; i < FAILING_SEATS; i++) {
            long id = insertAvailableSeat(SCHEDULE, "B" + i);
            expiredHeldWithoutQuota(ORPHAN, id, 100 - i);   // 100·99·… 초 전 — 가장 오래됨
            failing.add(id);
        }
        long normal = insertAvailableSeat(SCHEDULE, "1A");
        holdSeats.hold(new HoldSeatsCommand(OWNER, List.of(new SeatId(normal))));
        expireSecondsAgo(normal, 10);                        // 실패 후보들보다 나중 → 항상 뒤

        // 한 tick 으로는 예산이 실패 후보로 가득 차 도달하지 못한다.
        scheduler.tick();
        assertThat(statusOf(normal))
                .as("★ 한 tick 으로는 못 간다 — 예산이 실패 후보로 채워졌다")
                .isEqualTo("HELD");

        // tick 을 반복하면 커서가 이어져 도달한다. 실제 시간을 기다리지 않는다.
        int ticks = 1;
        while ("HELD".equals(statusOf(normal)) && ticks < 10) {
            scheduler.tick();
            ticks++;
        }

        assertThat(ticks).as("유한한 tick 안에 도달한다").isLessThan(10);
        assertThat(statusOf(normal))
                .as("★★ 여러 tick 을 거쳐 실패 블록 뒤의 정상 좌석이 회수됐다")
                .isEqualTo("AVAILABLE");
        assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, OWNER.value()))
                .as("회수된 1석만큼 줄었다").isZero();

        failing.forEach(id -> assertThat(statusOf(id))
                .as("★ 실패 후보의 데이터는 보존된다").isEqualTo("HELD"));
        assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, ORPHAN.value()))
                .as("★ quota 행을 만들지 않는다").isEqualTo(-1);

        assertThat(counter("railgate.expiry.seats.reclaimed")).isEqualTo(1.0);
        assertThat(counter("railgate.expiry.groups.failed",
                "kind", GroupFailureKind.QUOTA_ROW_MISSING.name()))
                .as("실패 그룹이 tick 마다 집계된다").isPositive();
        assertThat(counter("railgate.expiry.sweep", "outcome", "failure"))
                .as("★ 그룹 실패는 배치 실패가 아니다").isZero();
    }

    /** ★ 순회가 끝난 뒤 다음 tick 은 처음부터 — 고쳐진 실패 후보를 재확인해 회수한다. */
    @Test
    void 순회_종료_후_다음_tick_은_처음부터_보며_고쳐진_실패_후보를_회수한다() {
        long orphan = insertAvailableSeat(SCHEDULE, "1A");
        expiredHeldWithoutQuota(ORPHAN, orphan, 60);
        long normal = insertAvailableSeat(SCHEDULE, "1B");
        holdSeats.hold(new HoldSeatsCommand(OWNER, List.of(new SeatId(normal))));
        expireSecondsAgo(normal, 10);

        // 순회를 끝까지 돌려 정상 좌석까지 회수한다.
        int ticks = 0;
        while ("HELD".equals(statusOf(normal)) && ticks < 10) {
            scheduler.tick();
            ticks++;
        }
        assertThat(statusOf(normal)).isEqualTo("AVAILABLE");
        assertThat(statusOf(orphan)).as("실패 후보는 남아 있다").isEqualTo("HELD");

        // 운영자가 카운터를 고쳤다고 가정한다 (backfill 은 이 Task 에서 수행하지 않는다).
        jdbc().update("""
                INSERT INTO user_hold_quota (sale_event_id, user_id, held_seats) VALUES (?, ?, 1)
                """, FIXTURE_SALE_EVENT_ID, ORPHAN.value());

        // 순회가 끝났으므로 다음 tick 은 처음부터 본다 → 고쳐진 후보를 회수한다.
        for (int i = 0; i < 3 && "HELD".equals(statusOf(orphan)); i++) {
            scheduler.tick();
        }

        assertThat(statusOf(orphan))
                .as("★ 처음부터 재확인해 회수했다").isEqualTo("AVAILABLE");
        assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, ORPHAN.value())).isZero();
    }
}
