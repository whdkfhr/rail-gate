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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * ★ I-11 — 만료 처리와 좌석 확정이 경쟁해도 SOLD 좌석은 해제되지 않는다.
 *
 * <p><b>이 경쟁이 실재하는 이유.</b>
 * {@link JdbcSeatPaymentRepository#confirm} 의 조건에는 {@code expires_at} 이 없다.
 * {@code WHERE id=? AND hold_id=? AND status='PAYING'} 뿐이므로
 * <b>이미 만료된 PAYING 좌석도 확정된다.</b> 결제 승인이 늦게 도착한 정상 요청이
 * 스위퍼가 회수하려는 바로 그 좌석을 확정하려 하는 상황은 예외가 아니라 정상 경로다.
 *
 * <p><b>주장 범위 (CLAUDE.md 규칙 30).</b>
 * <ul>
 *   <li>구조적 논증 — 두 UPDATE 가 같은 행을 대상으로 하고 서로의 출발 상태를
 *       {@code WHERE} 에 명시하므로, InnoDB 행 잠금이 직렬화한 뒤 뒤에 온 쪽은
 *       조건 불일치로 {@code affected_rows = 0} 이 된다.</li>
 *   <li>결정적 재현 — 확정 우선 / 만료 우선 두 순서를 각각 고정해 검증한다 ({@code 결정적_순서}).</li>
 *   <li>통계적 보조 결과 — 동시 경합 반복에서 위반이 관측되지 않았다.
 *       위반 부재의 증명이 아니다 ({@code 동시_경합}).</li>
 * </ul>
 */
@Timeout(180)
@DisplayName("만료 스위퍼와 좌석 확정의 경쟁 - I-11")
class SeatExpiryConfirmRaceTest extends MySqlTestSupport {

    private static final long SCHEDULE_ID = 1L;
    private static final Duration HOLD_DURATION = Duration.ofMinutes(5);
    private static final Duration PAYMENT_DURATION = Duration.ofMinutes(5);

    private static final HoldId HOLD = HoldId.of("abcdefab-cdef-4abc-8def-abcdefabcdef");
    private static final UserId USER = new UserId(7L);
    private static final ReservationId RESERVATION = new ReservationId(100L);

    /** 경합 반복 횟수. 한 번의 실행으로는 인터리빙을 충분히 훑지 못한다. */
    private static final int ROUNDS = 200;

    private JdbcSeatHoldRepository holdRepository;
    private JdbcSeatPaymentRepository paymentRepository;
    private JdbcSeatExpiryRepository expiryRepository;

    @BeforeEach
    void setUp() {
        holdRepository = new JdbcSeatHoldRepository(dataSource(), HOLD_DURATION);
        paymentRepository = new JdbcSeatPaymentRepository(dataSource(), PAYMENT_DURATION);
        expiryRepository = new JdbcSeatExpiryRepository(dataSource());
    }

    /** 만료된 PAYING 좌석을 만들고 그 id 를 돌려준다. 스위퍼 후보이면서 확정 대상이다. */
    private long givenExpiredPayingSeat(String seatNo) {
        long seatId = insertAvailableSeat(SCHEDULE_ID, seatNo);
        holdRepository.hold(new SeatId(seatId), HOLD, USER);
        paymentRepository.startPayment(new SeatId(seatId), HOLD);
        jdbc().update(
                "UPDATE seat_inventory SET expires_at = DATE_SUB(NOW(3), INTERVAL 1 SECOND) WHERE id = ?",
                seatId);
        return seatId;
    }

    @Nested
    @DisplayName("결정적 순서 검증")
    class 결정적_순서 {

        @Test
        void 확정이_먼저_성공하면_뒤늦은_만료는_0건이다() {
            long seatId = givenExpiredPayingSeat("1A");
            List<ExpiryCandidate> candidates = expiryRepository.findExpiredCandidates(100);
            assertThat(candidates).hasSize(1);

            SeatConfirmationOutcome confirmed =
                    paymentRepository.confirm(new SeatId(seatId), HOLD, RESERVATION);
            int recovered = expiryRepository.expire(candidates);

            assertThat(confirmed).isEqualTo(SeatConfirmationOutcome.CONFIRMED);
            assertThat(recovered).as("stale expiry").isZero();
            assertThat(statusOf(seatId)).isEqualTo("SOLD");
            assertThat(reservationIdOf(seatId)).isEqualTo(RESERVATION.value());
        }

        @Test
        void 만료가_먼저_성공하면_뒤늦은_확정은_0건이다() {
            long seatId = givenExpiredPayingSeat("1A");

            int recovered = expiryRepository.sweep(100);
            SeatConfirmationOutcome confirmed =
                    paymentRepository.confirm(new SeatId(seatId), HOLD, RESERVATION);

            assertThat(recovered).isEqualTo(1);
            assertThat(confirmed).as("stale confirm").isEqualTo(SeatConfirmationOutcome.NOT_CONFIRMED);
            assertThat(statusOf(seatId)).isEqualTo("AVAILABLE");
            assertThat(reservationIdOf(seatId)).isNull();
        }

        @Test
        void 확정이_먼저면_version_은_확정_한_번만_증가한다() {
            long seatId = givenExpiredPayingSeat("1A");
            Long before = versionOf(seatId);
            List<ExpiryCandidate> candidates = expiryRepository.findExpiredCandidates(100);

            paymentRepository.confirm(new SeatId(seatId), HOLD, RESERVATION);
            expiryRepository.expire(candidates);

            assertThat(versionOf(seatId)).as("성공한 전이는 하나뿐이다").isEqualTo(before + 1);
        }

        @Test
        void 만료가_먼저면_version_은_만료_한_번만_증가한다() {
            long seatId = givenExpiredPayingSeat("1A");
            Long before = versionOf(seatId);

            expiryRepository.sweep(100);
            paymentRepository.confirm(new SeatId(seatId), HOLD, RESERVATION);

            assertThat(versionOf(seatId)).isEqualTo(before + 1);
        }
    }

    @Nested
    @DisplayName("동시 경합 (통계적 보조 결과)")
    class 동시_경합 {

        @Test
        void 확정과_만료를_동시_출발시켜도_최종_상태는_둘_중_하나다() throws Exception {
            List<String> violations = new CopyOnWriteArrayList<>();
            List<String> errors = new CopyOnWriteArrayList<>();
            int soldWins = 0;
            int expiryWins = 0;

            for (int round = 0; round < ROUNDS; round++) {
                long seatId = givenExpiredPayingSeat("seat-" + round);
                Long versionBefore = versionOf(seatId);
                List<ExpiryCandidate> candidates =
                        List.of(new ExpiryCandidate(new SeatId(seatId), HOLD));

                RaceOutcome outcome = race(seatId, candidates, errors);

                String finalStatus = statusOf(seatId);
                Long versionAfter = versionOf(seatId);

                // 최종 상태는 정확히 둘 중 하나여야 한다.
                if (!List.of("SOLD", "AVAILABLE").contains(finalStatus)) {
                    violations.add("round %d: 예상 밖 상태 %s".formatted(round, finalStatus));
                    continue;
                }

                // 정확히 한 전이만 성공해야 한다.
                boolean confirmWon = outcome.confirmed() == SeatConfirmationOutcome.CONFIRMED;
                boolean expiryWon = outcome.recovered() == 1;
                if (confirmWon == expiryWon) {
                    violations.add("round %d: 성공한 전이가 %s"
                            .formatted(round, confirmWon ? "둘 다" : "없다"));
                    continue;
                }

                if (versionAfter != versionBefore + 1) {
                    violations.add("round %d: version 증가가 %d (기대 1)"
                            .formatted(round, versionAfter - versionBefore));
                }

                if (confirmWon) {
                    soldWins++;
                    if (!"SOLD".equals(finalStatus)) {
                        violations.add("round %d: 확정 성공인데 상태가 %s".formatted(round, finalStatus));
                    }
                    // SOLD: reservation_id 존재, 활성 홀드 필드 없음
                    if (reservationIdOf(seatId) == null || holdIdOf(seatId) != null
                            || heldByOf(seatId) != null || heldAtOf(seatId) != null
                            || expiresAtOf(seatId) != null) {
                        violations.add("round %d: SOLD 필드 조합이 어긋남".formatted(round));
                    }
                } else {
                    expiryWins++;
                    if (!"AVAILABLE".equals(finalStatus)) {
                        violations.add("round %d: 만료 성공인데 상태가 %s".formatted(round, finalStatus));
                    }
                    // AVAILABLE: reservation_id 와 활성 홀드 필드 모두 없음
                    if (reservationIdOf(seatId) != null || holdIdOf(seatId) != null
                            || heldByOf(seatId) != null || heldAtOf(seatId) != null
                            || expiresAtOf(seatId) != null) {
                        violations.add("round %d: AVAILABLE 필드 조합이 어긋남".formatted(round));
                    }
                }
            }

            assertThat(errors).as("시스템 오류 / deadlock / lock timeout").isEmpty();
            assertThat(violations).as("I-11 위반 및 상태 정합성 위반").isEmpty();
            assertThat(soldWins + expiryWins).isEqualTo(ROUNDS);
            System.out.printf("[I-11 경합 %d회] 확정 승리 %d, 만료 승리 %d%n",
                    ROUNDS, soldWins, expiryWins);
        }

        @Test
        void 확정된_좌석이_사후에_풀린_기록이_없다() throws Exception {
            // 위 테스트가 라운드마다 즉시 검사한다면, 이 테스트는 전체 실행이 끝난 뒤
            // 테이블 전체를 한 번 훑어 SOLD 인데 예약이 없거나 AVAILABLE 인데 예약이 남은 행을 찾는다.
            List<String> errors = new CopyOnWriteArrayList<>();
            List<Long> seatIds = new ArrayList<>();

            for (int round = 0; round < ROUNDS; round++) {
                long seatId = givenExpiredPayingSeat("seat-" + round);
                seatIds.add(seatId);
                race(seatId, List.of(new ExpiryCandidate(new SeatId(seatId), HOLD)), errors);
            }

            assertThat(errors).isEmpty();

            Long orphanSold = jdbc().queryForObject("""
                    SELECT COUNT(*) FROM seat_inventory
                     WHERE status = 'SOLD' AND reservation_id IS NULL
                    """, Long.class);
            Long leakedAvailable = jdbc().queryForObject("""
                    SELECT COUNT(*) FROM seat_inventory
                     WHERE status = 'AVAILABLE'
                       AND (reservation_id IS NOT NULL OR hold_id IS NOT NULL
                            OR held_by IS NOT NULL OR expires_at IS NOT NULL)
                    """, Long.class);
            Long stuck = jdbc().queryForObject("""
                    SELECT COUNT(*) FROM seat_inventory
                     WHERE status IN ('HELD','PAYING')
                    """, Long.class);

            assertThat(orphanSold).as("V-5 고아 SOLD").isZero();
            assertThat(leakedAvailable).as("AVAILABLE 인데 잔여 필드가 남음").isZero();
            assertThat(stuck).as("경합 후 중간 상태로 남은 좌석").isZero();
            assertThat(seatIds).hasSize(ROUNDS);
        }
    }

    // ------------------------------------------------------------------

    /** 확정과 만료를 {@code CountDownLatch} 로 동시에 출발시킨다. {@code Thread.sleep} 미사용. */
    private RaceOutcome race(long seatId, List<ExpiryCandidate> candidates, List<String> errors)
            throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        AtomicReference<SeatConfirmationOutcome> confirmed = new AtomicReference<>();
        AtomicReference<Integer> recovered = new AtomicReference<>();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    confirmed.set(paymentRepository.confirm(new SeatId(seatId), HOLD, RESERVATION));
                } catch (Exception e) {
                    errors.add("confirm: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                } finally {
                    done.countDown();
                }
            });
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    recovered.set(expiryRepository.expire(candidates));
                } catch (Exception e) {
                    errors.add("expire: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                } finally {
                    done.countDown();
                }
            });

            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        }

        return new RaceOutcome(confirmed.get(), recovered.get() == null ? 0 : recovered.get());
    }

    private record RaceOutcome(SeatConfirmationOutcome confirmed, int recovered) {
    }
}
