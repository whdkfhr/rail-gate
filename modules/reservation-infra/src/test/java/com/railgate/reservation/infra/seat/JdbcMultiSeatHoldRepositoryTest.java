package com.railgate.reservation.infra.seat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.infra.MySqlTestSupport;
import com.railgate.reservation.seat.SeatId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * 다좌석 원자 선점 (I-9 전부-또는-전무).
 *
 * <p>동시성 검증은 {@code MultiSeatHoldConcurrencyTest} 가,
 * 개별 UPDATE 반복의 실패 재현은 {@code MultiSeatHoldRaceReproductionTest} 가 담당한다.
 */
@Timeout(60)
@DisplayName("JdbcMultiSeatHoldRepository - 다좌석 원자 선점 (I-9)")
class JdbcMultiSeatHoldRepositoryTest extends MySqlTestSupport {

    private static final long SCHEDULE_ID = 1L;
    private static final Duration HOLD_DURATION = Duration.ofMinutes(5);

    private static final HoldId HOLD = HoldId.of("abcdefab-cdef-4abc-8def-abcdefabcdef");
    private static final HoldId OTHER_HOLD = HoldId.of("11111111-2222-4333-8444-555555555555");
    private static final UserId USER = new UserId(7L);
    private static final UserId OTHER_USER = new UserId(9L);

    private JdbcMultiSeatHoldRepository repository;
    private JdbcSeatHoldRepository singleSeatRepository;
    private List<Long> seatIds;

    @BeforeEach
    void setUp() {
        repository = new JdbcMultiSeatHoldRepository(dataSource(), HOLD_DURATION);
        singleSeatRepository = new JdbcSeatHoldRepository(dataSource(), HOLD_DURATION);
        seatIds = new ArrayList<>();
        for (String seatNo : List.of("1A", "2A", "3A", "4A", "5A")) {
            seatIds.add(insertAvailableSeat(SCHEDULE_ID, seatNo));
        }
    }

    private List<SeatId> seats(int... indexes) {
        List<SeatId> result = new ArrayList<>();
        for (int i : indexes) {
            result.add(new SeatId(seatIds.get(i)));
        }
        return result;
    }

    private long seatIdAt(int index) {
        return seatIds.get(index);
    }

    private long countHeldBy(HoldId holdId) {
        return jdbc().queryForObject(
                "SELECT COUNT(*) FROM seat_inventory WHERE hold_id = ? AND status = 'HELD'",
                Long.class, holdId.asString());
    }

    private long countAvailable() {
        return jdbc().queryForObject(
                "SELECT COUNT(*) FROM seat_inventory WHERE schedule_id = ? AND status = 'AVAILABLE'",
                Long.class, SCHEDULE_ID);
    }

    @Nested
    @DisplayName("전부 성공")
    class 전부_성공 {

        @Test
        void 한_좌석을_선점할_수_있다() {
            MultiSeatHoldOutcome outcome = repository.holdAll(seats(0), HOLD, USER);

            assertThat(outcome).isEqualTo(MultiSeatHoldOutcome.HELD);
            assertThat(countHeldBy(HOLD)).isEqualTo(1);
        }

        @Test
        void 두_좌석을_함께_선점할_수_있다() {
            MultiSeatHoldOutcome outcome = repository.holdAll(seats(0, 1), HOLD, USER);

            assertThat(outcome).isEqualTo(MultiSeatHoldOutcome.HELD);
            assertThat(countHeldBy(HOLD)).isEqualTo(2);
        }

        @Test
        void 네_좌석을_함께_선점할_수_있다() {
            MultiSeatHoldOutcome outcome = repository.holdAll(seats(0, 1, 2, 3), HOLD, USER);

            assertThat(outcome).isEqualTo(MultiSeatHoldOutcome.HELD);
            assertThat(countHeldBy(HOLD)).isEqualTo(4);
        }

        @Test
        void 성공한_모든_좌석에_같은_홀드와_점유자가_기록된다() {
            repository.holdAll(seats(0, 1, 2), HOLD, USER);

            for (int i : new int[] {0, 1, 2}) {
                long id = seatIdAt(i);
                assertThat(statusOf(id)).isEqualTo("HELD");
                assertThat(holdIdOf(id)).isEqualTo(HOLD.asString());
                assertThat(heldByOf(id)).isEqualTo(USER.value());
            }
        }

        @Test
        void 요청하지_않은_좌석은_건드리지_않는다() {
            repository.holdAll(seats(0, 1), HOLD, USER);

            assertThat(statusOf(seatIdAt(2))).isEqualTo("AVAILABLE");
            assertThat(holdIdOf(seatIdAt(2))).isNull();
        }

        @Test
        void 성공하면_만료_시각과_version_이_모든_좌석에_설정된다() {
            repository.holdAll(seats(0, 1), HOLD, USER);

            for (int i : new int[] {0, 1}) {
                assertThat(versionOf(seatIdAt(i))).isEqualTo(1L);
                assertThat(secondsUntilExpiry(seatIdAt(i)))
                        .isBetween(HOLD_DURATION.toSeconds() - 5, HOLD_DURATION.toSeconds());
            }
        }
    }

    @Nested
    @DisplayName("요청 검증 (트랜잭션 진입 전)")
    class 요청_검증 {

        @Test
        void 좌석이_없으면_거부한다() {
            assertThatThrownBy(() -> repository.holdAll(List.of(), HOLD, USER))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void 다섯_좌석_이상은_거부한다() {
            assertThatThrownBy(() -> repository.holdAll(seats(0, 1, 2, 3, 4), HOLD, USER))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void 중복된_좌석은_거부한다() {
            // 중복을 허용하면 요청 좌석 수와 affected_rows 가 구조적으로 어긋나
            // 정상 요청이 항상 실패하게 된다.
            List<SeatId> duplicated = List.of(new SeatId(seatIdAt(0)), new SeatId(seatIdAt(0)));

            assertThatThrownBy(() -> repository.holdAll(duplicated, HOLD, USER))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void 좌석_목록이_null_이면_거부한다() {
            assertThatThrownBy(() -> repository.holdAll(null, HOLD, USER))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void 홀드_식별자가_null_이면_거부한다() {
            assertThatThrownBy(() -> repository.holdAll(seats(0), null, USER))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void 검증에_실패하면_어떤_좌석도_변경되지_않는다() {
            assertThatThrownBy(() -> repository.holdAll(seats(0, 1, 2, 3, 4), HOLD, USER))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(countAvailable()).isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("잠금 순서")
    class 잠금_순서 {

        @Test
        void 입력_순서와_무관하게_같은_결과가_나온다() {
            MultiSeatHoldOutcome descending = repository.holdAll(seats(3, 2, 1, 0), HOLD, USER);

            assertThat(descending).isEqualTo(MultiSeatHoldOutcome.HELD);
            assertThat(countHeldBy(HOLD)).isEqualTo(4);
        }

        @Test
        void 원본_리스트를_변경하지_않는다() {
            // 정렬을 in-place 로 하면 호출자의 리스트가 조용히 바뀐다.
            List<SeatId> requested = new ArrayList<>(seats(3, 1, 2, 0));
            List<SeatId> snapshot = List.copyOf(requested);

            repository.holdAll(requested, HOLD, USER);

            assertThat(requested).containsExactlyElementsOf(snapshot);
        }

        @Test
        void 불변_리스트를_전달해도_동작한다() {
            // List.of(...) 는 정렬을 시도하면 UnsupportedOperationException 이 난다.
            MultiSeatHoldOutcome outcome =
                    repository.holdAll(List.of(new SeatId(seatIdAt(2)), new SeatId(seatIdAt(0))),
                            HOLD, USER);

            assertThat(outcome).isEqualTo(MultiSeatHoldOutcome.HELD);
        }

        @Test
        void 벌크_UPDATE_는_기본_키_인덱스를_쓴다() {
            // 옵티마이저가 다른 인덱스를 고르면 잠금 순서 보장이 사라진다 (CLAUDE.md 규칙 3).
            String ids = seatIdAt(0) + ", " + seatIdAt(1);
            Map<String, Object> plan = jdbc().queryForMap(
                    "EXPLAIN UPDATE seat_inventory FORCE INDEX (PRIMARY) SET status = 'HELD' "
                            + "WHERE id IN (" + ids + ") AND status = 'AVAILABLE'");

            assertThat(String.valueOf(plan.get("key"))).isEqualTo("PRIMARY");
        }
    }

    @Nested
    @DisplayName("★ 전부 실패 — 부분 선점이 남지 않는다")
    class 전부_실패 {

        @Test
        void 한_좌석이라도_선점_불가면_요청_전체가_실패한다() {
            singleSeatRepository.hold(new SeatId(seatIdAt(1)), OTHER_HOLD, OTHER_USER);

            MultiSeatHoldOutcome outcome = repository.holdAll(seats(0, 1, 2), HOLD, USER);

            assertThat(outcome).isEqualTo(MultiSeatHoldOutcome.SEAT_UNAVAILABLE);
        }

        @Test
        void 실패하면_이미_갱신된_행도_모두_되돌린다() {
            // 벌크 UPDATE 는 가능한 좌석 2개를 실제로 HELD 로 바꾼 뒤 affected_rows=2 를 돌려준다.
            // 롤백이 없으면 그 2개가 부분 선점으로 남는다.
            singleSeatRepository.hold(new SeatId(seatIdAt(1)), OTHER_HOLD, OTHER_USER);

            repository.holdAll(seats(0, 1, 2), HOLD, USER);

            assertThat(statusOf(seatIdAt(0))).as("첫 좌석").isEqualTo("AVAILABLE");
            assertThat(statusOf(seatIdAt(2))).as("셋째 좌석").isEqualTo("AVAILABLE");
            assertThat(holdIdOf(seatIdAt(0))).isNull();
            assertThat(holdIdOf(seatIdAt(2))).isNull();
            assertThat(countHeldBy(HOLD)).isZero();
        }

        @Test
        void 실패해도_다른_사용자의_홀드는_그대로다() {
            singleSeatRepository.hold(new SeatId(seatIdAt(1)), OTHER_HOLD, OTHER_USER);

            repository.holdAll(seats(0, 1, 2), HOLD, USER);

            assertThat(statusOf(seatIdAt(1))).isEqualTo("HELD");
            assertThat(holdIdOf(seatIdAt(1))).isEqualTo(OTHER_HOLD.asString());
            assertThat(heldByOf(seatIdAt(1))).isEqualTo(OTHER_USER.value());
        }

        @Test
        void 롤백은_예외가_아니라_결과값으로_보고된다() {
            // 외부 트랜잭션이 없는 독립 호출에서는 rollback-only 처리 후
            // SEAT_UNAVAILABLE 이 정상 반환되는지 확인한다.
            // 외부 트랜잭션 참여 동작은 앱 배선 시 별도 통합 테스트가 필요하다.
            singleSeatRepository.hold(new SeatId(seatIdAt(0)), OTHER_HOLD, OTHER_USER);

            MultiSeatHoldOutcome outcome = repository.holdAll(seats(0, 1), HOLD, USER);

            assertThat(outcome).isEqualTo(MultiSeatHoldOutcome.SEAT_UNAVAILABLE);
        }

        @Test
        void 마지막_좌석이_불가여도_앞_좌석들이_남지_않는다() {
            singleSeatRepository.hold(new SeatId(seatIdAt(3)), OTHER_HOLD, OTHER_USER);

            repository.holdAll(seats(0, 1, 2, 3), HOLD, USER);

            assertThat(countHeldBy(HOLD)).isZero();
            assertThat(countAvailable()).as("5석 중 남의 홀드 1석만 빠진다").isEqualTo(4);
        }

        @Test
        void 존재하지_않는_좌석이_섞이면_전체가_실패한다() {
            List<SeatId> withGhost = List.of(new SeatId(seatIdAt(0)), new SeatId(999_999L));

            MultiSeatHoldOutcome outcome = repository.holdAll(withGhost, HOLD, USER);

            assertThat(outcome).isEqualTo(MultiSeatHoldOutcome.SEAT_UNAVAILABLE);
            assertThat(statusOf(seatIdAt(0))).isEqualTo("AVAILABLE");
        }

        @Test
        void 실패_후_같은_좌석을_다시_요청하면_성공한다() {
            singleSeatRepository.hold(new SeatId(seatIdAt(1)), OTHER_HOLD, OTHER_USER);
            repository.holdAll(seats(0, 1, 2), HOLD, USER);

            MultiSeatHoldOutcome retry = repository.holdAll(seats(0, 2), HOLD, USER);

            assertThat(retry).isEqualTo(MultiSeatHoldOutcome.HELD);
            assertThat(countHeldBy(HOLD)).isEqualTo(2);
        }
    }
}
