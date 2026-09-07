package com.railgate.reservation.infra.quota;

import static org.assertj.core.api.Assertions.assertThat;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.infra.MySqlTestSupport;
import com.railgate.reservation.infra.quota.Task2gfDriftDetection.Drift;
import com.railgate.reservation.infra.seat.JdbcMultiSeatHoldRepository;
import com.railgate.reservation.seat.SeatId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * ★ 실험 G — quota drift 탐지 (Task 2G-F).
 *
 * <h2>결정해야 하는 것</h2>
 *
 * <p>2G-C §10-9 — <b>drift 탐지 쿼리와 보정 절차.</b>
 * 2G-C 는 "{@code held_seats} 와 실제 활성 좌석을 대조하는 쿼리가 없다" 를 남은 위험으로 적었다.
 *
 * <h2>탐지만 한다</h2>
 *
 * <p><b>보정 SQL 을 실행하지 않는다.</b> 좌석과 카운터 중 무엇이 진실의 원천인지
 * 근거 없이 정할 수 없다. drift 의 원인에 따라 답이 다르고, 그 원인은 아직 조사되지 않았다.
 * 이번 Task 는 읽기 전용 탐지와 절차만 결정한다.
 */
@Timeout(240)
@DisplayName("실험 G — quota drift 탐지 (Task 2G-F)")
class ExpiryQuotaDriftDetectionTest extends MySqlTestSupport {

    private static final Duration HOLD_DURATION = Duration.ofMinutes(5);

    private static final long CHUSEOK = 9001L;
    private static final long SEOLLAL = 9002L;
    private static final long SCHEDULE_CHUSEOK = 101L;
    private static final long SCHEDULE_SEOLLAL = 201L;

    private static final UserId USER_A = new UserId(11L);
    private static final UserId USER_B = new UserId(22L);

    private JdbcMultiSeatHoldRepository holdRepository;
    private Task2gQuotaCounter quota;

    @BeforeEach
    void setUp() {
        Task2gQuotaCounter.createTable(jdbc());
        Task2gQuotaCounter.truncate(jdbc());

        holdRepository = new JdbcMultiSeatHoldRepository(
                dataSource(), transactionManager(), HOLD_DURATION);
        quota = new Task2gQuotaCounter(dataSource());

        insertSaleEvent(CHUSEOK, "추석");
        insertSaleEvent(SEOLLAL, "설");
        jdbc().update("INSERT INTO train_schedule (id, sale_event_id) VALUES (?, ?), (?, ?)",
                SCHEDULE_CHUSEOK, CHUSEOK, SCHEDULE_SEOLLAL, SEOLLAL);
    }

    private static void insertSaleEvent(long id, String name) {
        jdbc().update("""
                INSERT INTO sale_event (id, name, opens_at, status)
                VALUES (?, ?, DATE_SUB(NOW(3), INTERVAL 1 DAY), 'OPEN')
                """, id, name);
    }

    private List<SeatId> holdSeats(UserId user, long scheduleId, long saleEventId,
            int count, String prefix) {
        quota.ensureRow(user.value(), saleEventId);
        assertThat(quota.tryAcquire(user.value(), saleEventId, count)).isTrue();
        List<SeatId> seats = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            seats.add(new SeatId(insertAvailableSeat(scheduleId, prefix + i)));
        }
        inTransaction(() -> holdRepository.holdAll(seats, HoldId.newId(), user));
        return seats;
    }

    private static List<Drift> detect() {
        return Task2gfDriftDetection.detect(jdbc());
    }

    // ------------------------------------------------------------------

    /** §1 정합한 상태에서는 아무것도 탐지되지 않는다. 그래야 탐지가 신호가 된다. */
    @Nested
    @DisplayName("§1 정합한 상태")
    class 정합한_상태 {

        @Test
        void 카운터와_활성_좌석이_같으면_탐지되지_않는다() {
            holdSeats(USER_A, SCHEDULE_CHUSEOK, CHUSEOK, 3, "A");
            holdSeats(USER_B, SCHEDULE_CHUSEOK, CHUSEOK, 2, "B");

            assertThat(detect()).isEmpty();
            assertThat(Task2gfDriftDetection.seatsWithoutHolder(jdbc())).isEmpty();
        }

        /** 회차가 다르면 별개 카운터다. 합산하면 없는 drift 를 만들어낸다. */
        @Test
        void 서로_다른_회차는_별개로_대조한다() {
            holdSeats(USER_A, SCHEDULE_CHUSEOK, CHUSEOK, 3, "C");
            holdSeats(USER_A, SCHEDULE_SEOLLAL, SEOLLAL, 2, "S");

            assertThat(detect()).isEmpty();
        }

        /** SOLD 와 AVAILABLE 은 활성 선점이 아니다 (2G-D §4). */
        @Test
        void SOLD_와_AVAILABLE_은_활성으로_세지_않는다() {
            List<SeatId> seats = holdSeats(USER_A, SCHEDULE_CHUSEOK, CHUSEOK, 3, "A");
            // 한 석을 SOLD 로 만들고 카운터도 그만큼 줄인다 = 정합한 상태.
            jdbc().update("""
                    UPDATE seat_inventory
                       SET status = 'SOLD', reservation_id = 777,
                           hold_id = NULL, held_by = NULL, held_at = NULL, expires_at = NULL
                     WHERE id = ?
                    """, seats.get(0).value());
            assertThat(quota.release(USER_A.value(), CHUSEOK, 1)).isTrue();

            assertThat(detect()).isEmpty();
        }
    }

    /**
     * §2 ★ 여섯 가지 drift 를 각각 탐지한다.
     */
    @Nested
    @DisplayName("§2 ★ drift 방향별 탐지")
    class drift_탐지 {

        @Test
        void 카운터는_있는데_활성_좌석이_없다() {
            quota.ensureRow(USER_A.value(), CHUSEOK);
            assertThat(quota.tryAcquire(USER_A.value(), CHUSEOK, 2)).isTrue();

            assertThat(detect())
                    .singleElement()
                    .satisfies(drift -> {
                        assertThat(drift.kind()).isEqualTo(Drift.Kind.QUOTA_WITHOUT_SEATS);
                        assertThat(drift.quotaHeldSeats()).isEqualTo(2);
                        assertThat(drift.activeSeats()).isZero();
                    });
        }

        /** ★ 가장 위험한 방향 — 상한 검사가 완전히 무력해진다. */
        @Test
        void 활성_좌석은_있는데_카운터_행이_없다() {
            holdSeats(USER_A, SCHEDULE_CHUSEOK, CHUSEOK, 3, "A");
            jdbc().update("DELETE FROM task2g_user_hold_quota WHERE user_id = ?", USER_A.value());

            assertThat(detect())
                    .singleElement()
                    .satisfies(drift -> {
                        assertThat(drift.kind()).isEqualTo(Drift.Kind.SEATS_WITHOUT_QUOTA);
                        assertThat(drift.quotaHeldSeats()).isNull();
                        assertThat(drift.activeSeats()).isEqualTo(3);
                    });
        }

        @Test
        void 카운터가_실제보다_크다() {
            holdSeats(USER_A, SCHEDULE_CHUSEOK, CHUSEOK, 1, "A");
            jdbc().update(
                    "UPDATE task2g_user_hold_quota SET held_seats = 3 WHERE user_id = ?",
                    USER_A.value());

            assertThat(detect())
                    .singleElement()
                    .satisfies(drift -> {
                        assertThat(drift.kind()).isEqualTo(Drift.Kind.QUOTA_GREATER);
                        assertThat(drift.quotaHeldSeats()).isEqualTo(3);
                        assertThat(drift.activeSeats()).isEqualTo(1);
                    });
        }

        @Test
        void 카운터가_실제보다_작다() {
            holdSeats(USER_A, SCHEDULE_CHUSEOK, CHUSEOK, 3, "A");
            jdbc().update(
                    "UPDATE task2g_user_hold_quota SET held_seats = 1 WHERE user_id = ?",
                    USER_A.value());

            assertThat(detect())
                    .singleElement()
                    .satisfies(drift -> {
                        assertThat(drift.kind()).isEqualTo(Drift.Kind.QUOTA_LESS);
                        assertThat(drift.activeSeats()).isEqualTo(3);
                    });
        }

        /** ★ 대조 쿼리로는 보이지 않는 drift. 따로 세야 한다. */
        @Test
        void held_by_가_NULL_인_활성_좌석은_따로_탐지한다() {
            List<SeatId> seats = holdSeats(USER_A, SCHEDULE_CHUSEOK, CHUSEOK, 3, "A");
            jdbc().update("UPDATE seat_inventory SET held_by = NULL WHERE id = ?",
                    seats.get(0).value());
            // 소유자를 잃은 좌석은 어느 카운터에도 집계되지 않는다.
            assertThat(quota.release(USER_A.value(), CHUSEOK, 1)).isTrue();

            assertThat(detect())
                    .as("대조 쿼리는 이 좌석을 보지 못한다 — 카운터 2, 집계 2 로 맞아 보인다")
                    .isEmpty();
            assertThat(Task2gfDriftDetection.seatsWithoutHolder(jdbc()))
                    .as("별도 쿼리가 잡아야 한다")
                    .containsExactly(seats.get(0).value());
        }

        /** 음수·상한 초과는 DB CHECK 가 애초에 막는다. 그 사실을 고정한다. */
        @Test
        void 음수와_상한_초과는_CHECK_가_막는다() {
            quota.ensureRow(USER_A.value(), CHUSEOK);

            assertThat(quota.release(USER_A.value(), CHUSEOK, 1))
                    .as("0 에서 1 을 빼려 하면 WHERE 조건에서 거부된다")
                    .isFalse();
            assertThat(quota.tryAcquire(USER_A.value(), CHUSEOK, 5))
                    .as("상한 4 를 넘는 확보는 조건부 UPDATE 가 거부한다")
                    .isFalse();
            assertThat(quota.heldSeats(USER_A.value(), CHUSEOK)).isZero();
        }

        @Test
        void 여러_사용자의_drift_를_함께_보고한다() {
            holdSeats(USER_A, SCHEDULE_CHUSEOK, CHUSEOK, 3, "A");
            holdSeats(USER_B, SCHEDULE_CHUSEOK, CHUSEOK, 2, "B");
            jdbc().update("DELETE FROM task2g_user_hold_quota WHERE user_id = ?", USER_A.value());
            jdbc().update(
                    "UPDATE task2g_user_hold_quota SET held_seats = 1 WHERE user_id = ?",
                    USER_B.value());

            List<Drift> drifts = detect();

            assertThat(drifts).hasSize(2);
            assertThat(drifts).extracting(Drift::kind)
                    .containsExactlyInAnyOrder(
                            Drift.Kind.SEATS_WITHOUT_QUOTA, Drift.Kind.QUOTA_LESS);
        }
    }

    /**
     * §3 탐지는 읽기 전용이다.
     *
     * <p>보정 정책을 이번 Task 에서 실행하지 않는다는 계약을 테스트로 고정한다.
     */
    @Nested
    @DisplayName("§3 탐지는 아무것도 고치지 않는다")
    class 읽기_전용 {

        @Test
        void 탐지_후에도_상태가_그대로다() {
            holdSeats(USER_A, SCHEDULE_CHUSEOK, CHUSEOK, 3, "A");
            jdbc().update(
                    "UPDATE task2g_user_hold_quota SET held_seats = 1 WHERE user_id = ?",
                    USER_A.value());

            assertThat(detect()).hasSize(1);

            assertThat(quota.heldSeats(USER_A.value(), CHUSEOK))
                    .as("자동 보정하지 않는다")
                    .isEqualTo(1);
            assertThat(jdbc().queryForObject("""
                    SELECT COUNT(*) FROM seat_inventory
                     WHERE held_by = ? AND status IN ('HELD','PAYING')
                    """, Long.class, USER_A.value()))
                    .as("좌석도 건드리지 않는다")
                    .isEqualTo(3);
        }

        @Test
        void 반복_실행해도_결과가_같다() {
            holdSeats(USER_A, SCHEDULE_CHUSEOK, CHUSEOK, 2, "A");
            jdbc().update("DELETE FROM task2g_user_hold_quota WHERE user_id = ?", USER_A.value());

            assertThat(detect()).isEqualTo(detect());
        }
    }
}
