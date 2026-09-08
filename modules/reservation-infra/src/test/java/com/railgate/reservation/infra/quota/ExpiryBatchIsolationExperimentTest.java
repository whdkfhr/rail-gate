package com.railgate.reservation.infra.quota;

import static org.assertj.core.api.Assertions.assertThat;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.ReservationId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.infra.MySqlTestSupport;
import com.railgate.reservation.infra.quota.Task2gfExpirySweeper.FailureKind;
import com.railgate.reservation.infra.quota.Task2gfExpirySweeper.GroupKey;
import com.railgate.reservation.infra.quota.Task2gfExpirySweeper.TransactionMode;
import com.railgate.reservation.infra.seat.JdbcMultiSeatHoldRepository;
import com.railgate.reservation.infra.seat.JdbcSeatExpiryRepository;
import com.railgate.reservation.infra.seat.JdbcSeatPaymentRepository;
import com.railgate.reservation.infra.seat.SeatConfirmationOutcome;
import com.railgate.reservation.infra.seat.SeatPaymentOutcome;
import com.railgate.reservation.seat.SeatId;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * ★ 실험 B·C·F — 배치 원자성, quota 행 부재, 손상 후보 (Task 2G-F).
 *
 * <h2>결정해야 하는 것</h2>
 *
 * <p>2G-C §10 이 남긴 것 중 셋이다.
 * <ul>
 *   <li><b>3·5 배치 원자성과 사용자별 트랜잭션 분리</b> — 한 사용자의 실패가 배치 전체를
 *       롤백해야 하는가</li>
 *   <li><b>4 quota 행 부재</b> — 중단인가, 그룹만 실패인가, 자동 생성인가, 무시인가</li>
 *   <li><b>8 {@code held_by} NULL</b> — 배치 중단인가, 손상 후보만 격리인가</li>
 * </ul>
 *
 * <h2>§ 실패 재현이 목적인 테스트</h2>
 *
 * <p>배치 전체 트랜잭션이 <b>정상 사용자의 회수까지 되돌리는 것</b>을 관측한다.
 * 통과한다는 것은 그 손해를 확인했다는 뜻이지 그 방식이 옳다는 뜻이 아니다 (규칙 27).
 *
 * <h2>범위</h2>
 *
 * <p><b>실험이다.</b> 운영 마이그레이션·저장소·스케줄러를 만들지 않는다.
 * quota 카운터는 {@code task2g_user_hold_quota} 테스트 전용 테이블을 쓰고,
 * 범위 키에는 2G-D 가 결정한 {@code sale_event_id} 를 넣는다.
 * <b>I-12 는 여전히 운영에 구현되지 않았다.</b>
 */
@Timeout(240)
@DisplayName("실험 B·C·F — 배치 원자성 / quota 행 부재 / 손상 후보 (Task 2G-F)")
class ExpiryBatchIsolationExperimentTest extends MySqlTestSupport {

    private static final Duration HOLD_DURATION = Duration.ofMinutes(5);

    private static final long CHUSEOK = 9001L;
    private static final long SCHEDULE = 101L;

    private static final UserId USER_A = new UserId(11L);
    private static final UserId USER_B = new UserId(22L);

    private JdbcSeatExpiryRepository expiryRepository;
    private JdbcMultiSeatHoldRepository holdRepository;
    private JdbcSeatPaymentRepository paymentRepository;
    private Task2gQuotaCounter quota;

    @BeforeEach
    void setUp() {
        Task2gQuotaCounter.createTable(jdbc());
        Task2gQuotaCounter.truncate(jdbc());

        expiryRepository = new JdbcSeatExpiryRepository(dataSource());
        holdRepository = new JdbcMultiSeatHoldRepository(
                dataSource(), transactionManager(), HOLD_DURATION);
        paymentRepository = new JdbcSeatPaymentRepository(dataSource(), HOLD_DURATION);
        quota = new Task2gQuotaCounter(dataSource());

        insertSaleEvent(CHUSEOK);
        jdbc().update("INSERT INTO train_schedule (id, sale_event_id) VALUES (?, ?)",
                SCHEDULE, CHUSEOK);
    }

    private static void insertSaleEvent(long id) {
        jdbc().update("""
                INSERT INTO sale_event (id, name, opens_at, status)
                VALUES (?, '2G-F 실험 회차', DATE_SUB(NOW(3), INTERVAL 1 DAY), 'OPEN')
                """, id);
    }

    /** 이 사용자에게 {@code count} 석을 선점시키고 quota 를 그만큼 올린다. */
    private List<SeatId> holdSeats(UserId user, int count, String prefix) {
        quota.ensureRow(user.value(), CHUSEOK);
        assertThat(quota.tryAcquire(user.value(), CHUSEOK, count))
                .as("픽스처의 quota 확보가 성공해야 한다")
                .isTrue();

        List<SeatId> seats = new java.util.ArrayList<>();
        for (int i = 1; i <= count; i++) {
            seats.add(new SeatId(insertAvailableSeat(SCHEDULE, prefix + i)));
        }
        inTransaction(() -> holdRepository.holdAll(seats, HoldId.newId(), user));
        return seats;
    }

    /** 이 좌석들을 만료시킨다. 상태 전이를 우회하지 않고 만료 시각만 과거로 옮긴다. */
    private static void expireNow(List<SeatId> seats) {
        seats.forEach(seat -> jdbc().update(
                "UPDATE seat_inventory SET expires_at = DATE_SUB(NOW(3), INTERVAL 1 MINUTE) WHERE id = ?",
                seat.value()));
    }

    private static String statusOfSeat(SeatId seat) {
        return jdbc().queryForObject(
                "SELECT status FROM seat_inventory WHERE id = ?", String.class, seat.value());
    }

    private Task2gfExpirySweeper sweeper(TransactionMode mode) {
        return Task2gfExpirySweeper.of(dataSource(), expiryRepository, quota, mode);
    }

    private static GroupKey keyOf(UserId user) {
        return new GroupKey(CHUSEOK, user.value());
    }

    // ------------------------------------------------------------------

    /**
     * §1 정상 경로 — 두 대안이 같은 결과를 낸다.
     *
     * <p>실패가 없으면 배치 전체 트랜잭션과 사용자별 트랜잭션의 <b>결과는 같아야 한다.</b>
     * 다른 것은 실패가 있을 때뿐이다. 그것을 먼저 고정해 두지 않으면 §2 의 차이가
     * 트랜잭션 경계 때문인지 다른 이유인지 구분할 수 없다.
     */
    @Nested
    @DisplayName("§1 실패가 없으면 두 대안의 결과가 같다")
    class 정상_경로 {

        @Test
        void 배치_전체_트랜잭션() {
            List<SeatId> a = holdSeats(USER_A, 3, "A");
            List<SeatId> b = holdSeats(USER_B, 2, "B");
            expireNow(a);
            expireNow(b);

            Task2gfSweepOutcome outcome = sweeper(TransactionMode.BATCH_WIDE).sweep(10);

            assertThat(outcome.reclaimed()).isEqualTo(5);
            assertThat(outcome.succeeded())
                    .containsEntry(keyOf(USER_A), 3)
                    .containsEntry(keyOf(USER_B), 2);
            assertThat(outcome.failed()).isEmpty();
            assertThat(quota.heldSeats(USER_A.value(), CHUSEOK)).isZero();
            assertThat(quota.heldSeats(USER_B.value(), CHUSEOK)).isZero();
        }

        @Test
        void 사용자별_트랜잭션() {
            List<SeatId> a = holdSeats(USER_A, 3, "A");
            List<SeatId> b = holdSeats(USER_B, 2, "B");
            expireNow(a);
            expireNow(b);

            Task2gfSweepOutcome outcome = sweeper(TransactionMode.PER_USER_GROUP).sweep(10);

            assertThat(outcome.reclaimed()).isEqualTo(5);
            assertThat(outcome.succeeded())
                    .containsEntry(keyOf(USER_A), 3)
                    .containsEntry(keyOf(USER_B), 2);
            assertThat(outcome.failed()).isEmpty();
            assertThat(quota.heldSeats(USER_A.value(), CHUSEOK)).isZero();
            assertThat(quota.heldSeats(USER_B.value(), CHUSEOK)).isZero();
        }

        /** 처리 순서는 quota 키 오름차순이다 (2G-B). 두 모드 모두 지킨다. */
        @Test
        void 처리_순서는_quota_키_오름차순이다() {
            holdSeats(USER_B, 2, "B");
            holdSeats(USER_A, 2, "A");
            expireNow(activeSeatsOf(USER_A));
            expireNow(activeSeatsOf(USER_B));

            Task2gfSweepOutcome outcome = sweeper(TransactionMode.PER_USER_GROUP).sweep(10);

            assertThat(outcome.succeeded().keySet())
                    .as("사용자 11 이 22 보다 먼저 처리돼야 한다")
                    .containsExactly(keyOf(USER_A), keyOf(USER_B));
        }
    }

    private static List<SeatId> activeSeatsOf(UserId user) {
        return jdbc().queryForList(
                        "SELECT id FROM seat_inventory WHERE held_by = ? ORDER BY id",
                        Long.class, user.value())
                .stream().map(SeatId::new).toList();
    }

    /**
     * §2 ★ quota 행 부재 — 배치 전체 트랜잭션의 손해를 재현한다.
     *
     * <p>사용자 A 의 카운터는 정상이고 B 의 카운터 행만 없다. 좌석은 양쪽 다 만료됐다.
     */
    @Nested
    @DisplayName("§2 ★ quota 행 부재")
    class quota_행_부재 {

        private List<SeatId> seatsA;
        private List<SeatId> seatsB;

        @BeforeEach
        void givenUserBHasNoQuotaRow() {
            seatsA = holdSeats(USER_A, 3, "A");
            seatsB = holdSeats(USER_B, 2, "B");
            expireNow(seatsA);
            expireNow(seatsB);

            // B 의 카운터 행만 지운다. 좌석은 그대로 B 가 잡고 있다 = drift 상태.
            jdbc().update("DELETE FROM task2g_user_hold_quota WHERE user_id = ?", USER_B.value());
            assertThat(quota.rowCount(USER_B.value(), CHUSEOK)).isZero();
        }

        /**
         * ★ 배치 전체 트랜잭션 — <b>B 의 drift 때문에 A 의 회수까지 되돌아간다.</b>
         *
         * <p>이 테스트가 통과한다는 것은 그 손해를 관측했다는 뜻이다 (규칙 27).
         * B 의 카운터가 없다는 사실과 A 의 좌석이 만료됐다는 사실은 아무 관계가 없는데도,
         * A 의 좌석은 계속 잡힌 채로 남는다 — I-10 회수가 멈춘다.
         */
        @Test
        void 배치_전체_트랜잭션은_정상_사용자의_회수까지_되돌린다() {
            Task2gfSweepOutcome outcome = sweeper(TransactionMode.BATCH_WIDE).sweep(10);

            assertThat(outcome.reclaimed()).as("아무것도 회수되지 않는다").isZero();
            assertThat(outcome.succeeded()).isEmpty();
            assertThat(outcome.failed())
                    .as("두 그룹 모두 실패로 기록된다")
                    .hasSize(2)
                    .allSatisfy(f -> assertThat(f.kind()).isEqualTo(FailureKind.QUOTA_ROW_MISSING));

            assertThat(seatsA).allSatisfy(seat -> assertThat(statusOfSeat(seat))
                    .as("★ A 는 잘못한 것이 없는데 좌석이 회수되지 않았다")
                    .isEqualTo("HELD"));
            assertThat(quota.heldSeats(USER_A.value(), CHUSEOK))
                    .as("A 의 카운터도 그대로다")
                    .isEqualTo(3);
        }

        /**
         * ✅ 사용자별 트랜잭션 — A 는 커밋되고 B 만 롤백된다.
         *
         * <p>그룹 안에서는 <b>좌석 회수와 quota 감소가 원자적</b>이다.
         * B 그룹은 좌석도 카운터도 바뀌지 않는다 — 좌석만 풀리고 카운터가 남는 반쪽 상태가 없다.
         */
        @Test
        void 사용자별_트랜잭션은_정상_그룹만_커밋한다() {
            Task2gfSweepOutcome outcome = sweeper(TransactionMode.PER_USER_GROUP).sweep(10);

            assertThat(outcome.reclaimed()).isEqualTo(3);
            assertThat(outcome.succeeded()).containsExactly(java.util.Map.entry(keyOf(USER_A), 3));
            assertThat(outcome.failed()).hasSize(1);
            assertThat(outcome.failed().get(0).key()).isEqualTo(keyOf(USER_B));
            assertThat(outcome.failed().get(0).kind()).isEqualTo(FailureKind.QUOTA_ROW_MISSING);

            assertThat(seatsA).allSatisfy(
                    seat -> assertThat(statusOfSeat(seat)).isEqualTo("AVAILABLE"));
            assertThat(quota.heldSeats(USER_A.value(), CHUSEOK)).isZero();
        }

        /** ★ 실패 그룹에서 좌석만 풀리거나 quota 만 줄어드는 반쪽 상태가 없어야 한다. */
        @Test
        void 실패_그룹은_좌석도_quota_도_바뀌지_않는다() {
            sweeper(TransactionMode.PER_USER_GROUP).sweep(10);

            assertThat(seatsB).allSatisfy(seat -> assertThat(statusOfSeat(seat))
                    .as("좌석만 해제되면 안 된다")
                    .isEqualTo("HELD"));
            assertThat(quota.rowCount(USER_B.value(), CHUSEOK))
                    .as("없던 행을 만들어내지 않는다")
                    .isZero();
        }

        /** 실패 정보가 결과에 남아야 알람이 대상을 가리킬 수 있다. */
        @Test
        void 실패_그룹과_원인을_호출자가_구분할_수_있다() {
            Task2gfSweepOutcome outcome = sweeper(TransactionMode.PER_USER_GROUP).sweep(10);

            assertThat(outcome.hasFailures()).isTrue();
            assertThat(outcome.failed().get(0).detail())
                    .as("어느 키가 왜 실패했는지 메시지에 남는다")
                    .contains("quota 행이 없다")
                    .contains("사용자=" + USER_B.value());
        }

        /** 재시도해도 결과가 같은 실패는 재시도하지 않는다 (실험 E 와 연결). */
        @Test
        void quota_행_부재는_재시도하지_않는다() {
            Task2gfSweepOutcome outcome = sweeper(TransactionMode.PER_USER_GROUP).sweep(10);

            assertThat(outcome.failed().get(0).attempts())
                    .as("업무 실패는 1회 시도로 끝난다")
                    .isEqualTo(1);
            assertThat(Task2gfFailureClassifier.isRetryable(FailureKind.QUOTA_ROW_MISSING)).isFalse();
        }
    }

    /**
     * §3 ★ quota 감소 거부 — 카운터가 실제보다 작은 drift.
     */
    @Nested
    @DisplayName("§3 ★ quota 감소 거부")
    class quota_감소_거부 {

        @Test
        void 카운터가_실제보다_작으면_그_그룹만_실패한다() {
            List<SeatId> seatsA = holdSeats(USER_A, 3, "A");
            List<SeatId> seatsB = holdSeats(USER_B, 2, "B");
            expireNow(seatsA);
            expireNow(seatsB);

            // B 는 좌석 2석을 잡고 있는데 카운터는 1 이다. 2 를 빼면 음수가 되어 거부된다.
            jdbc().update(
                    "UPDATE task2g_user_hold_quota SET held_seats = 1 WHERE user_id = ?",
                    USER_B.value());

            Task2gfSweepOutcome outcome = sweeper(TransactionMode.PER_USER_GROUP).sweep(10);

            assertThat(outcome.succeeded()).containsExactly(java.util.Map.entry(keyOf(USER_A), 3));
            assertThat(outcome.failed()).hasSize(1);
            assertThat(outcome.failed().get(0).kind())
                    .isEqualTo(FailureKind.QUOTA_DECREASE_REJECTED);
            assertThat(seatsB).allSatisfy(seat -> assertThat(statusOfSeat(seat))
                    .as("감소가 거부됐으므로 좌석 회수도 롤백된다")
                    .isEqualTo("HELD"));
            assertThat(quota.heldSeats(USER_B.value(), CHUSEOK))
                    .as("카운터도 그대로다")
                    .isEqualTo(1);
        }
    }

    /**
     * §4 ★ {@code held_by} NULL — 손상 후보.
     *
     * <p>운영 후보 조회는 {@code hold_id IS NOT NULL} 로 걸러내지만 {@code held_by} 는 보지 않는다.
     * 이 실험의 후보 조회는 <b>둘 다 거르지 않고 읽어서 명시적으로 분리한다.</b>
     */
    @Nested
    @DisplayName("§4 ★ held_by NULL 손상 후보")
    class 손상_후보 {

        private List<SeatId> seatsA;
        private SeatId corrupted;

        @BeforeEach
        void givenOneCorruptSeat() {
            seatsA = holdSeats(USER_A, 3, "A");
            List<SeatId> seatsB = holdSeats(USER_B, 1, "B");
            expireNow(seatsA);
            expireNow(seatsB);

            // HELD·hold_id 있음·held_by NULL 인 손상 행을 만든다.
            corrupted = seatsB.get(0);
            jdbc().update("UPDATE seat_inventory SET held_by = NULL WHERE id = ?",
                    corrupted.value());
        }

        @Test
        void 손상_후보를_조용히_제외하지_않고_결과에_남긴다() {
            Task2gfSweepOutcome outcome = sweeper(TransactionMode.PER_USER_GROUP).sweep(10);

            assertThat(outcome.corrupt()).hasSize(1);
            assertThat(outcome.corrupt().get(0).seatId()).isEqualTo(corrupted.value());
            assertThat(outcome.corrupt().get(0).reason())
                    .contains("held_by")
                    .contains("quota");
        }

        @Test
        void 손상_후보의_좌석을_회수하지_않는다() {
            sweeper(TransactionMode.PER_USER_GROUP).sweep(10);

            assertThat(statusOfSeat(corrupted))
                    .as("소유자를 모르는 채 회수하면 quota 가 영구히 어긋난다")
                    .isEqualTo("HELD");
        }

        @Test
        void 소유자를_추측하지_않는다() {
            Task2gfSweepOutcome outcome = sweeper(TransactionMode.PER_USER_GROUP).sweep(10);

            assertThat(outcome.succeeded().keySet())
                    .as("사용자 0 같은 존재하지 않는 그룹을 만들지 않는다")
                    .containsExactly(keyOf(USER_A));
            assertThat(quota.heldSeats(USER_B.value(), CHUSEOK))
                    .as("B 의 카운터를 임의로 건드리지 않는다")
                    .isEqualTo(1);
        }

        /** ★ 손상 후보가 있어도 정상 사용자의 만료는 진행된다. */
        @Test
        void 손상_후보가_정상_그룹의_회수를_막지_않는다() {
            Task2gfSweepOutcome outcome = sweeper(TransactionMode.PER_USER_GROUP).sweep(10);

            assertThat(outcome.reclaimed()).isEqualTo(3);
            assertThat(seatsA).allSatisfy(
                    seat -> assertThat(statusOfSeat(seat)).isEqualTo("AVAILABLE"));
        }

        /** 자동 보정하지 않는다. 손상 행은 그대로 남고 다음 배치에서도 다시 보고된다. */
        @Test
        void 자동_보정하지_않는다() {
            sweeper(TransactionMode.PER_USER_GROUP).sweep(10);
            Task2gfSweepOutcome second = sweeper(TransactionMode.PER_USER_GROUP).sweep(10);

            assertThat(second.corrupt())
                    .as("고치지 않았으므로 계속 보고돼야 한다")
                    .hasSize(1);
            assertThat(jdbc().queryForObject(
                    "SELECT held_by FROM seat_inventory WHERE id = ?", Long.class, corrupted.value()))
                    .as("값을 지어내 채우지 않는다")
                    .isNull();
        }
    }

    /**
     * §5 stale 후보 — 후보 수와 회수 수가 다른 것은 오류가 아니다.
     */
    @Nested
    @DisplayName("§5 stale 후보")
    class stale_후보 {

        @Test
        void 후보_조회_뒤_확정된_좌석은_stale_로_집계된다() {
            List<SeatId> seatsA = holdSeats(USER_A, 3, "A");
            SeatId sold = seatsA.get(0);
            String holdId = jdbc().queryForObject(
                    "SELECT hold_id FROM seat_inventory WHERE id = ?", String.class, sold.value());

            // 상태 전이를 우회하지 않고 운영 결제 경로로 stale 을 만든다 (2G-C 와 같은 방식).
            // startPayment 는 expires_at > NOW(3) 을 요구하므로 만료보다 먼저다.
            assertThat(paymentRepository.startPayment(sold, HoldId.of(holdId)))
                    .isEqualTo(SeatPaymentOutcome.STARTED);
            expireNow(seatsA);

            // 후보 조회 직후, 정산 전에 그 좌석이 SOLD 로 확정된다.
            Runnable confirmOne = () -> assertThat(paymentRepository.confirm(
                    sold, HoldId.of(holdId), new ReservationId(777L)))
                    .isEqualTo(SeatConfirmationOutcome.CONFIRMED);

            Task2gfSweepOutcome outcome =
                    sweeper(TransactionMode.PER_USER_GROUP).sweep(10, confirmOne);

            assertThat(outcome.candidatesFound()).isEqualTo(3);
            assertThat(outcome.reclaimed()).as("stale 한 하나는 빠진다").isEqualTo(2);
            assertThat(outcome.staleCandidates()).isEqualTo(1);
            assertThat(outcome.failed()).as("stale 은 실패가 아니다").isEmpty();
            assertThat(statusOfSeat(sold)).as("I-11: 확정된 좌석은 회수되지 않는다").isEqualTo("SOLD");
            assertThat(quota.heldSeats(USER_A.value(), CHUSEOK))
                    .as("후보 수 3 이 아니라 실제 회수 수 2 만큼만 줄어든다")
                    .isEqualTo(1);
        }
    }
}
