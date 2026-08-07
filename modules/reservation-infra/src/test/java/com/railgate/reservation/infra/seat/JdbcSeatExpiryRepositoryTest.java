package com.railgate.reservation.infra.seat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
}
