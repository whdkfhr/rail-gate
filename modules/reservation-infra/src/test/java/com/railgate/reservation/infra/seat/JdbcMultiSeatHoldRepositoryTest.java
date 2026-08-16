package com.railgate.reservation.infra.seat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.infra.MySqlTestSupport;
import com.railgate.reservation.seat.SeatId;
import com.railgate.reservation.seat.SeatUnavailableException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.transaction.UnexpectedRollbackException;

/**
 * 다좌석 원자 선점 (I-9 전부-또는-전무).
 *
 * <p>동시성 검증은 {@code MultiSeatHoldConcurrencyTest} 가,
 * 개별 UPDATE 반복의 실패 재현은 {@code MultiSeatHoldPartialFailureTest} 가,
 * 트랜잭션 경계 계약은 {@code MultiSeatHoldTransactionBoundaryTest} 가 담당한다.
 *
 * <p>Task 2F 부터 저장소는 <b>최상위</b> 트랜잭션을 만들지 않고 호출자가 연 트랜잭션에
 * 참여한다(그 안에 자신의 UPDATE 를 위한 NESTED savepoint 는 만든다).
 * 그래서 모든 성공 경로는 {@code inTransaction(...)} 으로 호출자 트랜잭션을 열고 실행한다.
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
        repository = new JdbcMultiSeatHoldRepository(
                dataSource(), transactionManager(), HOLD_DURATION);
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
            inTransaction(() -> repository.holdAll(seats(0), HOLD, USER));

            assertThat(countHeldBy(HOLD)).isEqualTo(1);
        }

        @Test
        void 두_좌석을_함께_선점할_수_있다() {
            inTransaction(() -> repository.holdAll(seats(0, 1), HOLD, USER));

            assertThat(countHeldBy(HOLD)).isEqualTo(2);
        }

        @Test
        void 네_좌석을_함께_선점할_수_있다() {
            inTransaction(() -> repository.holdAll(seats(0, 1, 2, 3), HOLD, USER));

            assertThat(countHeldBy(HOLD)).isEqualTo(4);
        }

        @Test
        void 성공한_모든_좌석에_같은_홀드와_점유자가_기록된다() {
            inTransaction(() -> repository.holdAll(seats(0, 1, 2), HOLD, USER));

            for (int i : new int[] {0, 1, 2}) {
                long id = seatIdAt(i);
                assertThat(statusOf(id)).isEqualTo("HELD");
                assertThat(holdIdOf(id)).isEqualTo(HOLD.asString());
                assertThat(heldByOf(id)).isEqualTo(USER.value());
            }
        }

        @Test
        void 요청하지_않은_좌석은_건드리지_않는다() {
            inTransaction(() -> repository.holdAll(seats(0, 1), HOLD, USER));

            assertThat(statusOf(seatIdAt(2))).isEqualTo("AVAILABLE");
            assertThat(holdIdOf(seatIdAt(2))).isNull();
        }

        @Test
        void 성공하면_만료_시각과_version_이_모든_좌석에_설정된다() {
            inTransaction(() -> repository.holdAll(seats(0, 1), HOLD, USER));

            for (int i : new int[] {0, 1}) {
                assertThat(versionOf(seatIdAt(i))).isEqualTo(1L);
                assertThat(secondsUntilExpiry(seatIdAt(i)))
                        .isBetween(HOLD_DURATION.toSeconds() - 5, HOLD_DURATION.toSeconds());
            }
        }
    }

    @Nested
    @DisplayName("요청 검증 (DB 접근 전)")
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
            inTransaction(() -> repository.holdAll(seats(3, 2, 1, 0), HOLD, USER));

            assertThat(countHeldBy(HOLD)).isEqualTo(4);
        }

        @Test
        void 원본_리스트를_변경하지_않는다() {
            // 정렬을 in-place 로 하면 호출자의 리스트가 조용히 바뀐다.
            List<SeatId> requested = new ArrayList<>(seats(3, 1, 2, 0));
            List<SeatId> snapshot = List.copyOf(requested);

            inTransaction(() -> repository.holdAll(requested, HOLD, USER));

            assertThat(requested).containsExactlyElementsOf(snapshot);
        }

        @Test
        void 불변_리스트를_전달해도_동작한다() {
            // List.of(...) 는 정렬을 시도하면 UnsupportedOperationException 이 난다.
            inTransaction(() -> repository.holdAll(
                    List.of(new SeatId(seatIdAt(2)), new SeatId(seatIdAt(0))), HOLD, USER));

            assertThat(countHeldBy(HOLD)).isEqualTo(2);
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

        /** 경합 시 예외가 전파되므로 호출부를 감싸 검증한다. */
        private void expectContention(List<SeatId> requested) {
            assertThatThrownBy(() -> inTransaction(() -> repository.holdAll(requested, HOLD, USER)))
                    .as("좌석 경합은 예상 가능한 정상 실패다 (규칙 21)")
                    .isInstanceOf(SeatUnavailableException.class);
        }

        @Test
        void 한_좌석이라도_선점_불가면_요청_전체가_실패한다() {
            singleSeatRepository.hold(new SeatId(seatIdAt(1)), OTHER_HOLD, OTHER_USER);

            expectContention(seats(0, 1, 2));
        }

        @Test
        void 실패하면_이미_갱신된_행도_모두_되돌린다() {
            // 벌크 UPDATE 는 가능한 좌석 2개를 실제로 HELD 로 바꾼 뒤 affected_rows=2 를 돌려준다.
            // 예외가 트랜잭션 경계 밖으로 나가면서 그 2개가 롤백된다.
            singleSeatRepository.hold(new SeatId(seatIdAt(1)), OTHER_HOLD, OTHER_USER);

            expectContention(seats(0, 1, 2));

            assertThat(statusOf(seatIdAt(0))).as("첫 좌석").isEqualTo("AVAILABLE");
            assertThat(statusOf(seatIdAt(2))).as("셋째 좌석").isEqualTo("AVAILABLE");
            assertThat(holdIdOf(seatIdAt(0))).isNull();
            assertThat(holdIdOf(seatIdAt(2))).isNull();
            assertThat(countHeldBy(HOLD)).isZero();
        }

        @Test
        void 실패해도_다른_사용자의_홀드는_그대로다() {
            singleSeatRepository.hold(new SeatId(seatIdAt(1)), OTHER_HOLD, OTHER_USER);

            expectContention(seats(0, 1, 2));

            assertThat(statusOf(seatIdAt(1))).isEqualTo("HELD");
            assertThat(holdIdOf(seatIdAt(1))).isEqualTo(OTHER_HOLD.asString());
            assertThat(heldByOf(seatIdAt(1))).isEqualTo(OTHER_USER.value());
        }

        @Test
        void 경합은_기술_예외가_아니라_전용_예외로_보고된다() {
            // Task 2F 이전에는 결과값으로 보고하면서 트랜잭션을 rollback-only 로 만들어,
            // 외부 트랜잭션 참여 시 UnexpectedRollbackException 이 났다.
            singleSeatRepository.hold(new SeatId(seatIdAt(0)), OTHER_HOLD, OTHER_USER);

            assertThatThrownBy(() -> inTransaction(
                    () -> repository.holdAll(seats(0, 1), HOLD, USER)))
                    .isInstanceOf(SeatUnavailableException.class)
                    .isNotInstanceOf(UnexpectedRollbackException.class);
        }

        @Test
        void 마지막_좌석이_불가여도_앞_좌석들이_남지_않는다() {
            singleSeatRepository.hold(new SeatId(seatIdAt(3)), OTHER_HOLD, OTHER_USER);

            expectContention(seats(0, 1, 2, 3));

            assertThat(countHeldBy(HOLD)).isZero();
            assertThat(countAvailable()).as("5석 중 남의 홀드 1석만 빠진다").isEqualTo(4);
        }

        @Test
        void 존재하지_않는_좌석이_섞이면_전체가_실패한다() {
            // 존재하지 않는 좌석과 이미 남이 잡은 좌석을 구분해서 알려주지 않는다.
            // 구분해 노출하면 좌석 점유 여부를 탐색하는 수단이 된다.
            expectContention(List.of(new SeatId(seatIdAt(0)), new SeatId(999_999L)));

            assertThat(statusOf(seatIdAt(0))).isEqualTo("AVAILABLE");
        }

        @Test
        void 실패_후_같은_좌석을_다시_요청하면_성공한다() {
            singleSeatRepository.hold(new SeatId(seatIdAt(1)), OTHER_HOLD, OTHER_USER);
            expectContention(seats(0, 1, 2));

            inTransaction(() -> repository.holdAll(seats(0, 2), HOLD, USER));

            assertThat(countHeldBy(HOLD)).isEqualTo(2);
        }
    }
}
