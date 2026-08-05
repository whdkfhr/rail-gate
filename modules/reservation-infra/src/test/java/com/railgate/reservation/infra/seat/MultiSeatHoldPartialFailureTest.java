package com.railgate.reservation.infra.seat;

import static org.assertj.core.api.Assertions.assertThat;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.infra.MySqlTestSupport;
import com.railgate.reservation.seat.SeatId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * ★ 벌크 UPDATE + 롤백이 실제로 무언가를 막고 있음을 A/B 로 보인다 (CLAUDE.md 규칙 27).
 *
 * <p><b>이 재현에는 동시성이 필요 없다.</b> 개별 UPDATE 반복의 결함은 경쟁 조건이 아니라
 * <b>트랜잭션 부재</b>이기 때문이다. 요청이 하나뿐이어도 중간에 한 좌석이 불가능하면
 * 앞서 잡은 좌석이 그대로 남는다. 따라서 배리어도 지연 주입도 쓰지 않으며,
 * 실행할 때마다 항상 같은 결과가 나온다.
 *
 * <p>각 개별 UPDATE 자체는 Task 2A 에서 검증한 올바른 조건부 UPDATE 다.
 * 즉 <b>단일 좌석 수준의 정확성만으로는 I-9 를 얻을 수 없다</b>는 것이 이 실험의 요지다.
 */
@Timeout(60)
@DisplayName("다좌석 선점 부분 실패 재현 - 개별 UPDATE 반복 vs 벌크 UPDATE + 롤백")
class MultiSeatHoldPartialFailureTest extends MySqlTestSupport {

    private static final long SCHEDULE_ID = 1L;
    private static final Duration HOLD_DURATION = Duration.ofMinutes(5);

    private static final HoldId REQUESTER = HoldId.of("abcdefab-cdef-4abc-8def-abcdefabcdef");
    private static final HoldId OCCUPANT = HoldId.of("11111111-2222-4333-8444-555555555555");
    private static final UserId REQUESTER_USER = new UserId(7L);
    private static final UserId OCCUPANT_USER = new UserId(9L);

    private JdbcMultiSeatHoldRepository repository;
    private JdbcSeatHoldRepository singleSeatRepository;
    private List<Long> seatIds;

    @BeforeEach
    void setUp() {
        repository = new JdbcMultiSeatHoldRepository(dataSource(), HOLD_DURATION);
        singleSeatRepository = new JdbcSeatHoldRepository(dataSource(), HOLD_DURATION);
        seatIds = new ArrayList<>();
        for (String seatNo : List.of("1A", "2A", "3A")) {
            seatIds.add(insertAvailableSeat(SCHEDULE_ID, seatNo));
        }
        // 가운데 좌석을 다른 사용자가 먼저 잡는다.
        singleSeatRepository.hold(new SeatId(seatIds.get(1)), OCCUPANT, OCCUPANT_USER);
    }

    private List<SeatId> allThree() {
        return List.of(
                new SeatId(seatIds.get(0)), new SeatId(seatIds.get(1)), new SeatId(seatIds.get(2)));
    }

    private long heldByRequester() {
        return jdbc().queryForObject(
                "SELECT COUNT(*) FROM seat_inventory WHERE hold_id = ? AND status = 'HELD'",
                Long.class, REQUESTER.asString());
    }

    @Test
    @DisplayName("A: 개별 UPDATE 를 반복하면 부분 선점이 남는다")
    void 개별_UPDATE_반복은_중간_실패_후_앞_좌석을_남긴다() {
        NaiveMultiSeatHoldRepository naive =
                new NaiveMultiSeatHoldRepository(dataSource(), HOLD_DURATION.toSeconds());

        MultiSeatHoldOutcome outcome = naive.holdAll(allThree(), REQUESTER, REQUESTER_USER);

        assertThat(outcome)
                .as("요청자는 실패를 응답받는다")
                .isEqualTo(MultiSeatHoldOutcome.SEAT_UNAVAILABLE);

        // 그런데 첫 좌석은 이미 잡힌 채로 남아 있다.
        assertThat(statusOf(seatIds.get(0)))
                .as("실패한 요청의 좌석이 HELD 로 남는다 = 부분 선점")
                .isEqualTo("HELD");
        assertThat(holdIdOf(seatIds.get(0))).isEqualTo(REQUESTER.asString());
        assertThat(heldByRequester()).as("실패했는데 1석을 점유하고 있다").isEqualTo(1);

        // 세 번째 좌석은 도달조차 못 했다. 요청자는 3석 중 1석만 가진 모순된 상태다.
        assertThat(statusOf(seatIds.get(2))).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("B: 벌크 UPDATE + 롤백은 같은 조건에서 아무것도 남기지 않는다")
    void 벌크_UPDATE_는_같은_조건에서_부분_선점을_남기지_않는다() {
        MultiSeatHoldOutcome outcome = repository.holdAll(allThree(), REQUESTER, REQUESTER_USER);

        assertThat(outcome).isEqualTo(MultiSeatHoldOutcome.SEAT_UNAVAILABLE);

        assertThat(statusOf(seatIds.get(0))).isEqualTo("AVAILABLE");
        assertThat(statusOf(seatIds.get(2))).isEqualTo("AVAILABLE");
        assertThat(heldByRequester()).as("실패한 요청은 한 석도 점유하지 않는다").isZero();
    }

    @Test
    @DisplayName("C: 두 구현 모두 남의 홀드는 건드리지 않는다")
    void 두_구현_모두_기존_점유자의_홀드는_유지한다() {
        NaiveMultiSeatHoldRepository naive =
                new NaiveMultiSeatHoldRepository(dataSource(), HOLD_DURATION.toSeconds());
        naive.holdAll(allThree(), REQUESTER, REQUESTER_USER);

        assertThat(holdIdOf(seatIds.get(1))).isEqualTo(OCCUPANT.asString());
        assertThat(heldByOf(seatIds.get(1))).isEqualTo(OCCUPANT_USER.value());

        // 차이는 "남의 것을 건드리는가" 가 아니라 "내 실패를 되돌리는가" 다.
    }

    @Test
    @DisplayName("D: 차이는 실패한 요청이 점유한 좌석 수다")
    void 실패한_요청이_점유한_좌석_수가_두_구현의_차이다() {
        NaiveMultiSeatHoldRepository naive =
                new NaiveMultiSeatHoldRepository(dataSource(), HOLD_DURATION.toSeconds());
        naive.holdAll(allThree(), REQUESTER, REQUESTER_USER);
        long naiveLeftover = heldByRequester();

        // 원상 복구 후 올바른 구현으로 같은 요청을 반복한다.
        jdbc().update("""
                UPDATE seat_inventory
                   SET status='AVAILABLE', hold_id=NULL, held_by=NULL, held_at=NULL, expires_at=NULL
                 WHERE hold_id = ?
                """, REQUESTER.asString());

        repository.holdAll(allThree(), REQUESTER, REQUESTER_USER);
        long bulkLeftover = heldByRequester();

        assertThat(naiveLeftover).as("개별 UPDATE 반복").isEqualTo(1);
        assertThat(bulkLeftover).as("벌크 UPDATE + 롤백").isZero();
    }
}
