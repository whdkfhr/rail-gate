package com.railgate.reservation.infra.seat;

import static org.assertj.core.api.Assertions.assertThat;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.ReservationId;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 홀드 단위 자발적 해제 (FR-2.5).
 *
 * <p>해제의 단위는 <b>좌석이 아니라 홀드</b>다. 사용자는 "12A를 놓겠다" 가 아니라
 * "내가 잡은 것을 놓겠다" 고 요청하며, 그 홀드에 묶인 1~4석이 함께 풀린다.
 *
 * <p><b>자연 멱등이어야 한다</b> (CLAUDE.md 규칙 18). 응답이 유실돼 재요청이 와도
 * 두 번째 호출은 0건을 돌려주고 아무것도 바꾸지 않는다.
 * 0건은 "현재 요청 조건에 맞는 해제 가능한 활성 좌석이 없다" 는 뜻이며 성공 no-op 으로 처리한다.
 * 좌석이 지금 AVAILABLE 임을 증명하거나 요청자가 정당한 소유자임을 뜻하지는 않는다.
 *
 * <p>동시성은 {@code SeatReleaseRaceTest}, 순진한 구현의 실패 재현은
 * {@code SeatReleaseStaleCandidateTest} 가 담당한다.
 */
@Timeout(60)
@DisplayName("JdbcSeatReleaseRepository - 홀드 단위 자발적 해제 (FR-2.5)")
class JdbcSeatReleaseRepositoryTest extends MySqlTestSupport {

    private static final long SCHEDULE_ID = 1L;
    private static final Duration HOLD_DURATION = Duration.ofMinutes(5);
    private static final Duration PAYMENT_DURATION = Duration.ofMinutes(5);

    private static final UserId OWNER = new UserId(7L);
    private static final UserId OTHER_USER = new UserId(9L);
    private static final ReservationId RESERVATION = new ReservationId(100L);

    private JdbcMultiSeatHoldRepository multiHoldRepository;
    private JdbcSeatHoldRepository holdRepository;
    private JdbcSeatPaymentRepository paymentRepository;
    private JdbcSeatReleaseRepository repository;

    @BeforeEach
    void setUp() {
        multiHoldRepository = new JdbcMultiSeatHoldRepository(dataSource(), HOLD_DURATION);
        holdRepository = new JdbcSeatHoldRepository(dataSource(), HOLD_DURATION);
        paymentRepository = new JdbcSeatPaymentRepository(dataSource(), PAYMENT_DURATION);
        repository = new JdbcSeatReleaseRepository(dataSource());
    }

    private HoldId holdOf(long seed) {
        return HoldId.of("%08d-0000-4000-8000-000000000000".formatted(seed));
    }

    /** 한 홀드로 여러 좌석을 잡는다. 실제 선점 경로를 그대로 쓴다. */
    private List<Long> givenHeldSeats(HoldId hold, UserId user, String prefix, int count) {
        List<SeatId> seatIds = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long id = insertAvailableSeat(SCHEDULE_ID, prefix + i);
            ids.add(id);
            seatIds.add(new SeatId(id));
        }
        assertThat(multiHoldRepository.holdAll(seatIds, hold, user))
                .isEqualTo(MultiSeatHoldOutcome.HELD);
        return ids;
    }

    @Nested
    @DisplayName("★ 홀드 단위 해제")
    class 해제 {

        @Test
        void 같은_홀드의_여러_좌석이_한_번에_해제된다() {
            HoldId hold = holdOf(1);
            List<Long> ids = givenHeldSeats(hold, OWNER, "A", 3);

            int released = repository.releaseAll(hold, OWNER);

            assertThat(released).isEqualTo(3);
            for (long id : ids) {
                assertThat(statusOf(id)).isEqualTo("AVAILABLE");
            }
        }

        @Test
        void 해제하면_홀드_관련_필드가_모두_비워진다() {
            HoldId hold = holdOf(1);
            List<Long> ids = givenHeldSeats(hold, OWNER, "A", 2);

            repository.releaseAll(hold, OWNER);

            for (long id : ids) {
                assertThat(holdIdOf(id)).isNull();
                assertThat(heldByOf(id)).isNull();
                assertThat(heldAtOf(id)).isNull();
                assertThat(expiresAtOf(id)).isNull();
                assertThat(reservationIdOf(id)).isNull();
            }
        }

        @Test
        void 결제_중인_좌석도_해제된다() {
            HoldId hold = holdOf(1);
            long seatId = insertAvailableSeat(SCHEDULE_ID, "1A");
            holdRepository.hold(new SeatId(seatId), hold, OWNER);
            paymentRepository.startPayment(new SeatId(seatId), hold);
            assertThat(statusOf(seatId)).isEqualTo("PAYING");

            int released = repository.releaseAll(hold, OWNER);

            assertThat(released).isEqualTo(1);
            assertThat(statusOf(seatId)).isEqualTo("AVAILABLE");
        }

        @Test
        void 해제된_좌석만_version_이_1_증가한다() {
            HoldId hold = holdOf(1);
            List<Long> ids = givenHeldSeats(hold, OWNER, "A", 2);
            long untouched = insertAvailableSeat(SCHEDULE_ID, "Z");

            repository.releaseAll(hold, OWNER);

            for (long id : ids) {
                assertThat(versionOf(id)).as("선점 1 + 해제 1").isEqualTo(2L);
            }
            assertThat(versionOf(untouched)).isZero();
        }

        @Test
        void 한_좌석짜리_홀드도_해제된다() {
            HoldId hold = holdOf(1);
            List<Long> ids = givenHeldSeats(hold, OWNER, "A", 1);

            assertThat(repository.releaseAll(hold, OWNER)).isEqualTo(1);
            assertThat(statusOf(ids.get(0))).isEqualTo("AVAILABLE");
        }

        @Test
        void 네_좌석짜리_홀드도_해제된다() {
            HoldId hold = holdOf(1);
            List<Long> ids = givenHeldSeats(hold, OWNER, "A", 4);

            assertThat(repository.releaseAll(hold, OWNER)).isEqualTo(4);
            assertThat(ids).allSatisfy(id -> assertThat(statusOf(id)).isEqualTo("AVAILABLE"));
        }
    }

    @Nested
    @DisplayName("★ 자연 멱등 (CLAUDE.md 규칙 18)")
    class 자연_멱등 {

        @Test
        void 같은_요청을_반복해도_0건이며_예외가_없다() {
            HoldId hold = holdOf(1);
            List<Long> ids = givenHeldSeats(hold, OWNER, "A", 2);

            int first = repository.releaseAll(hold, OWNER);
            int second = repository.releaseAll(hold, OWNER);
            int third = repository.releaseAll(hold, OWNER);

            assertThat(first).isEqualTo(2);
            assertThat(second).as("조건에 맞는 활성 좌석이 없다 = 성공 no-op").isZero();
            assertThat(third).isZero();
            assertThat(ids).allSatisfy(id -> assertThat(statusOf(id)).isEqualTo("AVAILABLE"));
        }

        @Test
        void 반복_호출은_version_을_더_올리지_않는다() {
            HoldId hold = holdOf(1);
            List<Long> ids = givenHeldSeats(hold, OWNER, "A", 2);
            repository.releaseAll(hold, OWNER);
            Long after = versionOf(ids.get(0));

            repository.releaseAll(hold, OWNER);

            assertThat(versionOf(ids.get(0))).isEqualTo(after);
        }

        @Test
        void 존재하지_않는_홀드를_해제해도_0건이다() {
            assertThat(repository.releaseAll(holdOf(999), OWNER)).isZero();
        }
    }

    @Nested
    @DisplayName("★ 소유권과 상태 방어")
    class 방어 {

        @Test
        void 다른_홀드로는_해제할_수_없다() {
            HoldId hold = holdOf(1);
            List<Long> ids = givenHeldSeats(hold, OWNER, "A", 2);

            int released = repository.releaseAll(holdOf(2), OWNER);

            assertThat(released).isZero();
            assertThat(ids).allSatisfy(id -> assertThat(statusOf(id)).isEqualTo("HELD"));
        }

        @Test
        void 다른_사용자는_남의_홀드를_해제할_수_없다() {
            HoldId hold = holdOf(1);
            List<Long> ids = givenHeldSeats(hold, OWNER, "A", 2);

            int released = repository.releaseAll(hold, OTHER_USER);

            assertThat(released).as("hold_id 가 맞아도 held_by 가 다르면 거부").isZero();
            assertThat(ids).allSatisfy(id -> {
                assertThat(statusOf(id)).isEqualTo("HELD");
                assertThat(heldByOf(id)).isEqualTo(OWNER.value());
            });
        }

        @Test
        void 확정된_좌석은_해제되지_않는다() {
            HoldId hold = holdOf(1);
            long seatId = insertAvailableSeat(SCHEDULE_ID, "1A");
            holdRepository.hold(new SeatId(seatId), hold, OWNER);
            paymentRepository.startPayment(new SeatId(seatId), hold);
            paymentRepository.confirm(new SeatId(seatId), hold, RESERVATION);

            int released = repository.releaseAll(hold, OWNER);

            assertThat(released).isZero();
            assertThat(statusOf(seatId)).isEqualTo("SOLD");
            assertThat(reservationIdOf(seatId)).isEqualTo(RESERVATION.value());
        }

        @Test
        void 판매_가능한_좌석은_건드리지_않는다() {
            long free = insertAvailableSeat(SCHEDULE_ID, "1A");
            HoldId hold = holdOf(1);
            givenHeldSeats(hold, OWNER, "B", 1);

            repository.releaseAll(hold, OWNER);

            assertThat(statusOf(free)).isEqualTo("AVAILABLE");
            assertThat(versionOf(free)).isZero();
        }

        @Test
        void 다른_홀드의_좌석은_영향을_받지_않는다() {
            HoldId mine = holdOf(1);
            HoldId theirs = holdOf(2);
            List<Long> myIds = givenHeldSeats(mine, OWNER, "A", 2);
            List<Long> theirIds = givenHeldSeats(theirs, OTHER_USER, "B", 2);

            int released = repository.releaseAll(mine, OWNER);

            assertThat(released).isEqualTo(2);
            assertThat(myIds).allSatisfy(id -> assertThat(statusOf(id)).isEqualTo("AVAILABLE"));
            assertThat(theirIds).allSatisfy(id -> {
                assertThat(statusOf(id)).isEqualTo("HELD");
                assertThat(holdIdOf(id)).isEqualTo(theirs.asString());
            });
        }
    }

    @Nested
    @DisplayName("stale 후보 보호")
    class stale_후보 {

        @Test
        void 조회_후_재선점된_좌석은_해제되지_않는다() {
            HoldId oldHold = holdOf(1);
            List<Long> ids = givenHeldSeats(oldHold, OWNER, "A", 2);
            List<SeatId> staleCandidates = repository.findReleasableSeats(oldHold, OWNER);
            assertThat(staleCandidates).hasSize(2);

            // 그 사이 만료되어 회수되고 다른 사용자가 다시 잡았다고 가정한다.
            repository.releaseAll(oldHold, OWNER);
            HoldId newHold = holdOf(2);
            multiHoldRepository.holdAll(
                    ids.stream().map(SeatId::new).toList(), newHold, OTHER_USER);

            int released = repository.release(oldHold, OWNER, staleCandidates);

            assertThat(released).as("hold_id 가 다르므로 거부").isZero();
            assertThat(ids).allSatisfy(id -> {
                assertThat(statusOf(id)).isEqualTo("HELD");
                assertThat(holdIdOf(id)).isEqualTo(newHold.asString());
            });
        }

        @Test
        void stale_후보가_섞여도_유효한_좌석만_해제한다() {
            // 스위퍼와 마찬가지로 전부-또는-전무가 아니다.
            HoldId hold = holdOf(1);
            List<Long> ids = givenHeldSeats(hold, OWNER, "A", 3);
            List<SeatId> candidates = repository.findReleasableSeats(hold, OWNER);

            // 후보 하나가 그 사이 확정됐다고 가정한다.
            long confirmed = ids.get(0);
            paymentRepository.startPayment(new SeatId(confirmed), hold);
            paymentRepository.confirm(new SeatId(confirmed), hold, RESERVATION);

            int released = repository.release(hold, OWNER, candidates);

            assertThat(released).as("유효한 2건만 해제").isEqualTo(2);
            assertThat(statusOf(confirmed)).isEqualTo("SOLD");
            assertThat(statusOf(ids.get(1))).isEqualTo("AVAILABLE");
            assertThat(statusOf(ids.get(2))).isEqualTo("AVAILABLE");
        }

        @Test
        void 빈_후보_목록이면_UPDATE_없이_0건이다() {
            assertThat(repository.release(holdOf(1), OWNER, List.of())).isZero();
        }
    }

    @Nested
    @DisplayName("컬렉션과 실행 계획")
    class 컬렉션_실행계획 {

        @Test
        void 호출자의_컬렉션을_변경하지_않는다() {
            HoldId hold = holdOf(1);
            List<Long> ids = givenHeldSeats(hold, OWNER, "A", 3);
            List<SeatId> requested = new ArrayList<>(List.of(
                    new SeatId(ids.get(2)), new SeatId(ids.get(0)), new SeatId(ids.get(1))));
            List<SeatId> snapshot = List.copyOf(requested);

            repository.release(hold, OWNER, requested);

            assertThat(requested).containsExactlyElementsOf(snapshot);
        }

        @Test
        void 불변_컬렉션도_정상_처리한다() {
            HoldId hold = holdOf(1);
            List<Long> ids = givenHeldSeats(hold, OWNER, "A", 2);

            int released = repository.release(hold, OWNER,
                    List.of(new SeatId(ids.get(1)), new SeatId(ids.get(0))));

            assertThat(released).isEqualTo(2);
        }

        @Test
        void 해제_UPDATE_는_기본_키_인덱스를_쓴다() {
            HoldId hold = holdOf(1);
            List<Long> ids = givenHeldSeats(hold, OWNER, "A", 2);

            // 운영 SQL 을 그대로 EXPLAIN 한다.
            Map<String, Object> plan = jdbc().queryForMap(
                    "EXPLAIN " + JdbcSeatReleaseRepository.releaseSql(2),
                    hold.asString(), OWNER.value(), ids.get(0), ids.get(1));

            assertThat(String.valueOf(plan.get("key"))).isEqualTo("PRIMARY");
        }

        @Test
        void 후보_조회는_홀드_인덱스를_쓰고_filesort_하지_않는다() {
            // (held_by, status) 는 대상 사용자의 활성 좌석을 전부 스캔한 뒤 hold_id 로 거른다.
            // V3 의 (hold_id) 는 홀드 좌석 수에만 비례한다
            // (JdbcSeatReleaseRepository.HOLD_INDEX 주석에 실측 근거).
            HoldId hold = holdOf(1);
            givenHeldSeats(hold, OWNER, "A", 2);

            Map<String, Object> plan = jdbc().queryForMap(
                    "EXPLAIN " + JdbcSeatReleaseRepository.FIND_SEATS_SQL,
                    hold.asString(), OWNER.value());

            assertThat(String.valueOf(plan.get("key")))
                    .isEqualTo(JdbcSeatReleaseRepository.HOLD_INDEX);
            assertThat(String.valueOf(plan.get("Extra"))).doesNotContain("filesort");
        }
    }

    @Nested
    @DisplayName("★ 외부 트랜잭션 참여")
    class 외부_트랜잭션 {

        // Task 2D 에서 문서로만 기록했던 "JdbcTemplate 은 외부 트랜잭션에 참여한다" 를
        // 여기서 실제로 검증한다.

        private TransactionTemplate transactionTemplate() {
            return new TransactionTemplate(new DataSourceTransactionManager(dataSource()));
        }

        @Test
        void 외부_트랜잭션이_롤백되면_해제도_롤백된다() {
            HoldId hold = holdOf(1);
            List<Long> ids = givenHeldSeats(hold, OWNER, "A", 2);

            Integer released = transactionTemplate().execute(status -> {
                int n = repository.releaseAll(hold, OWNER);
                status.setRollbackOnly();
                return n;
            });

            assertThat(released).as("UPDATE 자체는 성공했다").isEqualTo(2);
            assertThat(ids).allSatisfy(id -> {
                assertThat(statusOf(id)).as("그러나 롤백되어 좌석은 그대로다").isEqualTo("HELD");
                assertThat(holdIdOf(id)).isEqualTo(hold.asString());
            });
        }

        @Test
        void 외부_트랜잭션이_커밋되면_해제가_반영된다() {
            HoldId hold = holdOf(1);
            List<Long> ids = givenHeldSeats(hold, OWNER, "A", 2);

            Integer released = transactionTemplate().execute(status ->
                    repository.releaseAll(hold, OWNER));

            assertThat(released).isEqualTo(2);
            assertThat(ids).allSatisfy(id -> assertThat(statusOf(id)).isEqualTo("AVAILABLE"));
        }
    }
}
