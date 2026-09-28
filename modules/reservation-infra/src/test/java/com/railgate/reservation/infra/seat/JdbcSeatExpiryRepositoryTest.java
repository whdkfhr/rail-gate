package com.railgate.reservation.infra.seat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.ReservationId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.expiry.ExpiredSeat;
import com.railgate.reservation.expiry.ExpiryCandidate;
import com.railgate.reservation.expiry.ExpiryCursor;
import com.railgate.reservation.infra.MySqlTestSupport;
import com.railgate.reservation.saleevent.SaleEventId;
import com.railgate.reservation.seat.SeatId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * 만료 스위퍼의 후보 조회와 조건부 회수 (I-10, I-11).
 *
 * <p>확정과의 경쟁은 {@code SeatExpiryConfirmRaceTest} 가,
 * stale candidate 실패 재현은 {@code SeatExpiryStaleCandidateTest} 가 담당한다.
 */
@Timeout(60)
@DisplayName("JdbcSeatExpiryRepository - 만료 후보 조회와 조건부 회수")
class JdbcSeatExpiryRepositoryTest extends MySqlTestSupport {

    private static final long SCHEDULE_ID = 1L;
    private static final Duration HOLD_DURATION = Duration.ofMinutes(5);
    private static final Duration PAYMENT_DURATION = Duration.ofMinutes(5);

    private static final UserId USER = new UserId(7L);
    private static final ReservationId RESERVATION = new ReservationId(100L);

    private JdbcSeatHoldRepository holdRepository;
    private JdbcSeatPaymentRepository paymentRepository;
    private JdbcSeatExpiryRepository repository;

    @BeforeEach
    void setUp() {
        holdRepository = new JdbcSeatHoldRepository(dataSource(), HOLD_DURATION);
        paymentRepository = new JdbcSeatPaymentRepository(dataSource(), PAYMENT_DURATION);
        repository = new JdbcSeatExpiryRepository(dataSource());
    }

    // ------------------------------------------------------------------
    // 픽스처는 실제 경로로 만든다. 상태 컬럼만 직접 바꾸면
    // 도메인이 만들 수 없는 조합(예: hold_id 없는 PAYING)이 생긴다.
    //
    // 만료 시각은 SQL 로 명확히 과거/현재/미래에 배치한다.
    // Java 시각과 DB 시각의 우연한 일치를 기대하지 않는다.
    // ------------------------------------------------------------------

    private long newSeat(String seatNo) {
        return insertAvailableSeat(SCHEDULE_ID, seatNo);
    }

    private HoldId holdOf(long seed) {
        return HoldId.of("%08d-0000-4000-8000-000000000000".formatted(seed));
    }

    private long heldSeat(String seatNo, HoldId hold, String expiresAtExpr) {
        long id = newSeat(seatNo);
        holdRepository.hold(new SeatId(id), hold, USER);
        setExpiresAt(id, expiresAtExpr);
        return id;
    }

    private long payingSeat(String seatNo, HoldId hold, String expiresAtExpr) {
        long id = newSeat(seatNo);
        holdRepository.hold(new SeatId(id), hold, USER);
        paymentRepository.startPayment(new SeatId(id), hold);
        setExpiresAt(id, expiresAtExpr);
        return id;
    }

    private long soldSeat(String seatNo, HoldId hold) {
        long id = newSeat(seatNo);
        holdRepository.hold(new SeatId(id), hold, USER);
        paymentRepository.startPayment(new SeatId(id), hold);
        paymentRepository.confirm(new SeatId(id), hold, RESERVATION);
        return id;
    }

    private void setExpiresAt(long seatId, String sqlExpression) {
        jdbc().update(
                "UPDATE seat_inventory SET expires_at = " + sqlExpression + " WHERE id = ?", seatId);
    }

    private static final String PAST = "DATE_SUB(NOW(3), INTERVAL 10 SECOND)";
    private static final String NOW = "NOW(3)";
    private static final String FUTURE = "DATE_ADD(NOW(3), INTERVAL 1 HOUR)";

    @Nested
    @DisplayName("★ I-10 만료된 좌석은 다시 판매 가능해진다")
    class 만료_회수 {

        @Test
        void 만료된_선점_좌석이_판매_가능해진다() {
            long seatId = heldSeat("1A", holdOf(1), PAST);

            int recovered = repository.sweep(100);

            assertThat(recovered).isEqualTo(1);
            assertThat(statusOf(seatId)).isEqualTo("AVAILABLE");
        }

        @Test
        void 만료된_결제_중_좌석이_판매_가능해진다() {
            long seatId = payingSeat("1A", holdOf(1), PAST);

            int recovered = repository.sweep(100);

            assertThat(recovered).isEqualTo(1);
            assertThat(statusOf(seatId)).isEqualTo("AVAILABLE");
        }

        @Test
        void 회수하면_활성_홀드_필드가_모두_비워진다() {
            long seatId = heldSeat("1A", holdOf(1), PAST);

            repository.sweep(100);

            assertThat(holdIdOf(seatId)).isNull();
            assertThat(heldByOf(seatId)).isNull();
            assertThat(heldAtOf(seatId)).isNull();
            assertThat(expiresAtOf(seatId)).isNull();
            assertThat(reservationIdOf(seatId)).isNull();
        }

        @Test
        void 회수하면_version_이_정확히_1_증가한다() {
            long seatId = heldSeat("1A", holdOf(1), PAST);
            Long before = versionOf(seatId);

            repository.sweep(100);

            assertThat(versionOf(seatId)).isEqualTo(before + 1);
        }

        @Test
        void 만료_시각이_현재와_같은_경계는_회수_대상이다() {
            // expires_at <= NOW(3) 이므로 경계는 포함이다.
            // NOW(3) 은 계속 흐르므로 "정확히 같은 순간" 을 SQL 로 고정할 수는 없다.
            // 여기서 확인하는 것은 경계가 배타적(<)이 아니라 포함(<=)이라는 것이다.
            long seatId = heldSeat("1A", holdOf(1), NOW);

            int recovered = repository.sweep(100);

            assertThat(recovered).isEqualTo(1);
            assertThat(statusOf(seatId)).isEqualTo("AVAILABLE");
        }

        @Test
        void 여러_좌석을_한_번에_회수한다() {
            long a = heldSeat("1A", holdOf(1), PAST);
            long b = payingSeat("2A", holdOf(2), PAST);
            long c = heldSeat("3A", holdOf(3), PAST);

            int recovered = repository.sweep(100);

            assertThat(recovered).isEqualTo(3);
            for (long id : List.of(a, b, c)) {
                assertThat(statusOf(id)).isEqualTo("AVAILABLE");
            }
        }
    }

    @Nested
    @DisplayName("★ I-11 회수하면 안 되는 좌석")
    class 회수_제외 {

        @Test
        void 판매된_좌석은_어떤_경우에도_회수하지_않는다() {
            long seatId = soldSeat("1A", holdOf(1));

            int recovered = repository.sweep(100);

            assertThat(recovered).isZero();
            assertThat(statusOf(seatId)).isEqualTo("SOLD");
            assertThat(reservationIdOf(seatId)).isEqualTo(RESERVATION.value());
        }

        @Test
        void 판매된_좌석은_후보로도_조회되지_않는다() {
            soldSeat("1A", holdOf(1));

            assertThat(repository.findExpiredCandidates(100)).isEmpty();
        }

        @Test
        void 만료되지_않은_선점_좌석은_건드리지_않는다() {
            long seatId = heldSeat("1A", holdOf(1), FUTURE);

            int recovered = repository.sweep(100);

            assertThat(recovered).isZero();
            assertThat(statusOf(seatId)).isEqualTo("HELD");
            assertThat(holdIdOf(seatId)).isEqualTo(holdOf(1).asString());
        }

        @Test
        void 만료되지_않은_결제_중_좌석은_건드리지_않는다() {
            long seatId = payingSeat("1A", holdOf(1), FUTURE);

            int recovered = repository.sweep(100);

            assertThat(recovered).isZero();
            assertThat(statusOf(seatId)).isEqualTo("PAYING");
        }

        @Test
        void 판매_가능한_좌석은_건드리지_않는다() {
            long seatId = newSeat("1A");

            int recovered = repository.sweep(100);

            assertThat(recovered).isZero();
            assertThat(statusOf(seatId)).isEqualTo("AVAILABLE");
            assertThat(versionOf(seatId)).isZero();
        }

        @Test
        void 만료_대상과_비대상이_섞이면_대상만_회수한다() {
            long expired = heldSeat("1A", holdOf(1), PAST);
            long notExpired = heldSeat("2A", holdOf(2), FUTURE);
            long sold = soldSeat("3A", holdOf(3));

            int recovered = repository.sweep(100);

            assertThat(recovered).isEqualTo(1);
            assertThat(statusOf(expired)).isEqualTo("AVAILABLE");
            assertThat(statusOf(notExpired)).isEqualTo("HELD");
            assertThat(statusOf(sold)).isEqualTo("SOLD");
        }
    }

    @Nested
    @DisplayName("stale candidate 보호")
    class stale_후보 {

        @Test
        void 조회_후_확정된_좌석은_회수하지_않는다() {
            long seatId = payingSeat("1A", holdOf(1), PAST);
            List<ExpiryCandidate> candidates = repository.findExpiredCandidates(100);
            paymentRepository.confirm(new SeatId(seatId), holdOf(1), RESERVATION);

            int recovered = repository.expire(candidates);

            assertThat(recovered).isZero();
            assertThat(statusOf(seatId)).isEqualTo("SOLD");
        }

        @Test
        void 조회_당시_홀드와_현재_홀드가_다르면_회수하지_않는다() {
            long seatId = heldSeat("1A", holdOf(1), PAST);
            List<ExpiryCandidate> candidates = repository.findExpiredCandidates(100);

            // 다른 스위퍼가 먼저 회수했고 새 사용자가 잡았다고 가정한다.
            jdbc().update("""
                    UPDATE seat_inventory
                       SET status='AVAILABLE', hold_id=NULL, held_by=NULL, held_at=NULL, expires_at=NULL
                     WHERE id = ?
                    """, seatId);
            HoldId newHold = holdOf(99);
            holdRepository.hold(new SeatId(seatId), newHold, new UserId(9L));

            int recovered = repository.expire(candidates);

            assertThat(recovered).as("hold_id 가 다르므로 거부된다").isZero();
            assertThat(statusOf(seatId)).isEqualTo("HELD");
            assertThat(holdIdOf(seatId)).isEqualTo(newHold.asString());
        }

        @Test
        void 조회_후_만료_시각이_연장되면_회수하지_않는다() {
            long seatId = heldSeat("1A", holdOf(1), PAST);
            List<ExpiryCandidate> candidates = repository.findExpiredCandidates(100);
            setExpiresAt(seatId, FUTURE);

            int recovered = repository.expire(candidates);

            assertThat(recovered).as("expires_at 을 다시 확인한다").isZero();
            assertThat(statusOf(seatId)).isEqualTo("HELD");
        }

        @Test
        void stale_후보가_섞여도_유효한_후보는_회수한다() {
            // 스위퍼는 예약 요청과 달리 전부-또는-전무가 아니다.
            // 후보 하나가 stale 하다고 나머지를 포기하면 만료 좌석이 계속 쌓인다.
            long stale = payingSeat("1A", holdOf(1), PAST);
            long valid = heldSeat("2A", holdOf(2), PAST);
            List<ExpiryCandidate> candidates = repository.findExpiredCandidates(100);
            assertThat(candidates).hasSize(2);

            paymentRepository.confirm(new SeatId(stale), holdOf(1), RESERVATION);

            int recovered = repository.expire(candidates);

            assertThat(recovered).as("유효한 후보 1건만 회수").isEqualTo(1);
            assertThat(statusOf(stale)).isEqualTo("SOLD");
            assertThat(statusOf(valid)).isEqualTo("AVAILABLE");
        }

        @Test
        void 좌석과_홀드의_쌍이_어긋나면_회수하지_않는다() {
            // id IN (...) AND hold_id IN (...) 로 두 집합만 비교하면
            // 서로 다른 후보의 hold_id 와 교차 매칭되어 잘못 회수된다.
            long a = heldSeat("1A", holdOf(1), PAST);
            long b = heldSeat("2A", holdOf(2), PAST);

            // 두 좌석의 hold_id 를 맞바꾼 후보를 만든다.
            List<ExpiryCandidate> crossed = List.of(
                    new ExpiryCandidate(new SeatId(a), holdOf(2)),
                    new ExpiryCandidate(new SeatId(b), holdOf(1)));

            int recovered = repository.expire(crossed);

            assertThat(recovered).as("쌍이 어긋나면 한 건도 회수되지 않는다").isZero();
            assertThat(statusOf(a)).isEqualTo("HELD");
            assertThat(statusOf(b)).isEqualTo("HELD");
        }
    }

    @Nested
    @DisplayName("후보 조회")
    class 후보_조회 {

        @Test
        void 후보에는_좌석_식별자와_조회_당시_홀드가_담긴다() {
            long seatId = heldSeat("1A", holdOf(1), PAST);

            List<ExpiryCandidate> candidates = repository.findExpiredCandidates(100);

            assertThat(candidates).hasSize(1);
            assertThat(candidates.get(0).seatId()).isEqualTo(new SeatId(seatId));
            assertThat(candidates.get(0).holdId()).isEqualTo(holdOf(1));
        }

        @Test
        void 만료_후보가_없으면_빈_목록을_돌려준다() {
            heldSeat("1A", holdOf(1), FUTURE);

            assertThat(repository.findExpiredCandidates(100)).isEmpty();
        }

        @Test
        void 빈_후보_목록이면_UPDATE_없이_0건을_반환한다() {
            assertThat(repository.expire(List.of())).isZero();
        }

        @Test
        void batch_size_가_적용된다() {
            heldSeat("1A", holdOf(1), PAST);
            heldSeat("2A", holdOf(2), PAST);
            heldSeat("3A", holdOf(3), PAST);

            assertThat(repository.findExpiredCandidates(2)).hasSize(2);
            assertThat(repository.sweep(2)).isEqualTo(2);
        }

        @Test
        void batch_size_가_0_이하면_거부한다() {
            assertThatThrownBy(() -> repository.findExpiredCandidates(0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> repository.sweep(-1))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void 후보_조회는_아무_행도_바꾸지_않는다() {
            long seatId = heldSeat("1A", holdOf(1), PAST);
            Long before = versionOf(seatId);

            repository.findExpiredCandidates(100);

            assertThat(versionOf(seatId)).isEqualTo(before);
            assertThat(statusOf(seatId)).isEqualTo("HELD");
        }
    }

    @Nested
    @DisplayName("잠금 순서와 실행 계획")
    class 잠금_순서 {

        @Test
        void 역순_후보를_전달해도_정상_회수한다() {
            long a = heldSeat("1A", holdOf(1), PAST);
            long b = heldSeat("2A", holdOf(2), PAST);
            long c = heldSeat("3A", holdOf(3), PAST);

            List<ExpiryCandidate> reversed = List.of(
                    new ExpiryCandidate(new SeatId(c), holdOf(3)),
                    new ExpiryCandidate(new SeatId(b), holdOf(2)),
                    new ExpiryCandidate(new SeatId(a), holdOf(1)));

            assertThat(repository.expire(reversed)).isEqualTo(3);
        }

        @Test
        void 원본_후보_목록을_변경하지_않는다() {
            long a = heldSeat("1A", holdOf(1), PAST);
            long b = heldSeat("2A", holdOf(2), PAST);
            List<ExpiryCandidate> requested = new ArrayList<>(List.of(
                    new ExpiryCandidate(new SeatId(b), holdOf(2)),
                    new ExpiryCandidate(new SeatId(a), holdOf(1))));
            List<ExpiryCandidate> snapshot = List.copyOf(requested);

            repository.expire(requested);

            assertThat(requested).containsExactlyElementsOf(snapshot);
        }

        @Test
        void 불변_후보_목록을_전달해도_동작한다() {
            long a = heldSeat("1A", holdOf(1), PAST);

            int recovered = repository.expire(
                    List.of(new ExpiryCandidate(new SeatId(a), holdOf(1))));

            assertThat(recovered).isEqualTo(1);
        }

        @Test
        void 만료_UPDATE_는_기본_키_인덱스를_쓴다() {
            long a = heldSeat("1A", holdOf(1), PAST);
            long b = heldSeat("2A", holdOf(2), PAST);

            // 운영 SQL 을 그대로 EXPLAIN 한다. 복사본이 조용히 어긋나는 것을 막는다.
            Map<String, Object> plan = jdbc().queryForMap(
                    "EXPLAIN " + JdbcSeatExpiryRepository.expireSql(2),
                    a, holdOf(1).asString(), b, holdOf(2).asString());

            assertThat(String.valueOf(plan.get("key"))).isEqualTo("PRIMARY");
        }

        @Test
        void 후보_조회는_만료_후보_인덱스를_쓴다() {
            heldSeat("1A", holdOf(1), PAST);

            Map<String, Object> plan = jdbc().queryForMap(
                    "EXPLAIN " + JdbcSeatExpiryRepository.FIND_CANDIDATES_SQL, 100);

            assertThat(String.valueOf(plan.get("key")))
                    .isEqualTo(JdbcSeatExpiryRepository.CANDIDATE_INDEX);
        }
    }

    /**
     * ★ Task 2H-D — quota 귀속을 함께 읽는 후보 조회와 키셋 커서 페이징.
     *
     * <p>기존 {@code findExpiredCandidates} 계약은 그대로 두고, 정산에 필요한 소유자·회차와
     * 정렬 키를 더 읽으며 손상 행을 숨기지 않는 별도 조회다.
     */
    @Nested
    @DisplayName("★ 귀속 포함 후보 조회와 키셋 커서 (Task 2H-D)")
    class 귀속_포함_후보_조회 {

        private final SaleEventId fixtureEvent = new SaleEventId(FIXTURE_SALE_EVENT_ID);

        @Test
        void 만료_후보의_홀드_소유자_회차를_함께_돌려준다() {
            HoldId hold = holdOf(1);
            long id = heldSeat("1A", hold, PAST);

            List<ExpiredSeat> seats = repository.findExpiredSeats(10, Optional.empty());

            assertThat(seats).hasSize(1);
            ExpiredSeat seat = seats.get(0);
            assertThat(seat.seatId().value()).isEqualTo(id);
            assertThat(seat.holdId()).contains(hold);
            assertThat(seat.heldBy()).contains(USER);
            assertThat(seat.saleEventId()).isEqualTo(fixtureEvent);
            assertThat(seat.isSettleable()).isTrue();
            assertThat(seat.expiresAt()).as("정렬 키도 함께 — 커서를 만들 수 있다").isNotNull();
        }

        @Test
        void 미만료_AVAILABLE_SOLD_는_후보가_아니다() {
            heldSeat("1A", holdOf(1), FUTURE);
            newSeat("2A");
            long sold = payingSeat("3A", holdOf(3), PAST);
            jdbc().update("UPDATE seat_inventory SET status = 'SOLD', expires_at = NULL WHERE id = ?", sold);

            assertThat(repository.findExpiredSeats(10, Optional.empty())).isEmpty();
        }

        /** ★ 손상 후보를 숨기지 않는다 — hold_id 나 held_by 가 NULL 이어도 후보로 읽는다. */
        @Test
        void 손상_후보를_숨기지_않고_사유를_구분한다() {
            long noHold = heldSeat("1A", holdOf(1), PAST);
            jdbc().update("UPDATE seat_inventory SET hold_id = NULL WHERE id = ?", noHold);
            long noOwner = heldSeat("2A", holdOf(2), PAST);
            jdbc().update("UPDATE seat_inventory SET held_by = NULL WHERE id = ?", noOwner);
            long normal = heldSeat("3A", holdOf(3), PAST);

            List<ExpiredSeat> seats = repository.findExpiredSeats(10, Optional.empty());

            assertThat(seats).extracting(e -> e.seatId().value())
                    .containsExactlyInAnyOrder(noHold, noOwner, normal);
            assertThat(seats).filteredOn(e -> e.seatId().value() == noHold).singleElement()
                    .satisfies(e -> {
                        assertThat(e.isSettleable()).isFalse();
                        assertThat(e.corruptionReason()).get().asString().contains("hold_id");
                    });
            assertThat(seats).filteredOn(e -> e.seatId().value() == noOwner).singleElement()
                    .satisfies(e -> {
                        assertThat(e.isSettleable()).isFalse();
                        assertThat(e.corruptionReason()).get().asString().contains("held_by");
                    });
            assertThat(repository.findExpiredCandidates(10))
                    .as("기존 조회는 hold_id 없는 행을 계속 제외한다 — 계약 유지")
                    .extracting(c -> c.seatId().value())
                    .containsExactlyInAnyOrder(noOwner, normal);
        }

        @Test
        void expires_at_id_순서와_LIMIT_을_지킨다() {
            long later = heldSeat("1A", holdOf(1), "DATE_SUB(NOW(3), INTERVAL 1 SECOND)");
            long earlier = heldSeat("2A", holdOf(2), "DATE_SUB(NOW(3), INTERVAL 10 SECOND)");
            long earliest = heldSeat("3A", holdOf(3), "DATE_SUB(NOW(3), INTERVAL 20 SECOND)");

            assertThat(repository.findExpiredSeats(2, Optional.empty()))
                    .extracting(e -> e.seatId().value())
                    .containsExactly(earliest, earlier);
            assertThat(later).isPositive();
        }

        @Test
        void pageSize_가_0_이하면_거부한다() {
            assertThatThrownBy(() -> repository.findExpiredSeats(0, Optional.empty()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        /** ★ 키셋 커서 — 커서 뒤부터 읽고, 같은 expires_at 은 id 로 갈린다. */
        @Test
        void 커서_뒤부터_읽고_같은_만료_시각은_id_로_갈린다() {
            long first = heldSeat("1A", holdOf(1), "DATE_SUB(NOW(3), INTERVAL 20 SECOND)");
            long tieA = heldSeat("2A", holdOf(2), "DATE_SUB(NOW(3), INTERVAL 10 SECOND)");
            long tieB = heldSeat("2B", holdOf(3), "DATE_SUB(NOW(3), INTERVAL 10 SECOND)");
            // 두 좌석의 만료 시각을 완전히 같게 만든다 — 페이지 경계의 tie 를 재현한다.
            jdbc().update("UPDATE seat_inventory SET expires_at = "
                    + "(SELECT e FROM (SELECT expires_at e FROM seat_inventory WHERE id = ?) x) WHERE id = ?",
                    tieA, tieB);

            List<ExpiredSeat> page1 = repository.findExpiredSeats(1, Optional.empty());
            assertThat(page1).extracting(e -> e.seatId().value()).containsExactly(first);

            List<ExpiredSeat> page2 = repository.findExpiredSeats(1, Optional.of(page1.get(0).cursor()));
            List<ExpiredSeat> page3 = repository.findExpiredSeats(1, Optional.of(page2.get(0).cursor()));
            List<ExpiredSeat> page4 = repository.findExpiredSeats(1, Optional.of(page3.get(0).cursor()));

            assertThat(List.of(page2.get(0).seatId().value(), page3.get(0).seatId().value()))
                    .as("★ 만료 시각이 같아도 id 오름차순으로 둘 다 나온다")
                    .containsExactly(Math.min(tieA, tieB), Math.max(tieA, tieB));
            assertThat(page4).as("뒤에 후보가 없다").isEmpty();
        }

        /** ★ 앞 후보가 회수돼 사라져도 커서는 위치를 잃지 않는다 (OFFSET 이면 건너뛴다). */
        @Test
        void 앞_후보가_사라져도_커서는_다음_후보를_건너뛰지_않는다() {
            long first = heldSeat("1A", holdOf(1), "DATE_SUB(NOW(3), INTERVAL 30 SECOND)");
            long second = heldSeat("2A", holdOf(2), "DATE_SUB(NOW(3), INTERVAL 20 SECOND)");
            long third = heldSeat("3A", holdOf(3), "DATE_SUB(NOW(3), INTERVAL 10 SECOND)");

            List<ExpiredSeat> page1 = repository.findExpiredSeats(1, Optional.empty());
            ExpiryCursor cursor = page1.get(0).cursor();
            assertThat(repository.expire(List.of(page1.get(0).toCandidate()))).isEqualTo(1);

            assertThat(repository.findExpiredSeats(2, Optional.of(cursor)))
                    .extracting(e -> e.seatId().value())
                    .as("★ 사라진 first 때문에 second 를 건너뛰지 않는다")
                    .containsExactly(second, third);
            assertThat(first).isPositive();
        }

        /** ★ 실행 계획 — 두 SQL 모두 만료 후보 인덱스, filesort 없음, 운행편은 PK. 새 인덱스 없음. */
        @Test
        void 두_조회_모두_후보_인덱스를_쓰고_filesort_하지_않는다() {
            for (int i = 0; i < 30; i++) {
                heldSeat(i + "Z", holdOf(200 + i), PAST);
            }

            List<Map<String, Object>> firstPage = jdbc().queryForList(
                    "EXPLAIN " + JdbcSeatExpiryRepository.FIND_ATTRIBUTED_CANDIDATES_SQL, 10);
            assertThat(firstPage).hasSize(2);
            assertThat(String.valueOf(firstPage.get(0).get("table"))).isEqualTo("s");
            assertThat(String.valueOf(firstPage.get(0).get("key")))
                    .isEqualTo(JdbcSeatExpiryRepository.CANDIDATE_INDEX);
            assertThat(String.valueOf(firstPage.get(0).get("Extra"))).doesNotContain("filesort");
            assertThat(String.valueOf(firstPage.get(1).get("table"))).isEqualTo("ts");
            assertThat(String.valueOf(firstPage.get(1).get("key"))).isEqualTo("PRIMARY");
            assertThat(String.valueOf(firstPage.get(1).get("type"))).isEqualTo("eq_ref");

            // ★ 커서 값은 DB 에서 읽는다. JVM 시계로 만들면 세션 시간대 차이로 조건이
            //    항상 거짓이 되어 옵티마이저가 계획을 접어 버린다 (실제로 관측했다).
            java.sql.Timestamp cursorAt = jdbc().queryForObject(
                    "SELECT MIN(expires_at) FROM seat_inventory WHERE status IN ('HELD','PAYING')",
                    java.sql.Timestamp.class);
            List<Map<String, Object>> afterCursor = jdbc().queryForList(
                    "EXPLAIN " + JdbcSeatExpiryRepository.FIND_ATTRIBUTED_CANDIDATES_AFTER_SQL,
                    cursorAt, cursorAt, 0L, 10);
            assertThat(afterCursor).hasSize(2);
            assertThat(String.valueOf(afterCursor.get(0).get("key")))
                    .isEqualTo(JdbcSeatExpiryRepository.CANDIDATE_INDEX);
            assertThat(String.valueOf(afterCursor.get(0).get("type")))
                    .as("range 이지만 이것만으로 커서 하한을 증명하지 않는다 — "
                            + "expires_at <= NOW(3) 만으로도 range 가 나온다. "
                            + "실제 하한 적용은 SeatExpiryCursorScanTest 가 스캔 행 수로 잰다")
                    .isEqualTo("range");
            assertThat(String.valueOf(afterCursor.get(0).get("Extra"))).doesNotContain("filesort");
            assertThat(String.valueOf(afterCursor.get(1).get("key"))).isEqualTo("PRIMARY");
        }
    }
}
