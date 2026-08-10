package com.railgate.reservation.infra.seat;

import static org.assertj.core.api.Assertions.assertThat;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.ReservationId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.infra.MySqlTestSupport;
import com.railgate.reservation.seat.SeatId;
import java.time.Duration;
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
 * ★ 자발적 해제와 다른 전이의 경쟁.
 *
 * <p>해제는 <b>세 번째 전이 주체</b>다. 지금까지 같은 행을 노리는 것은 확정과 만료 둘이었는데
 * 여기에 사용자의 해제가 더해진다. 셋 다 {@code HELD}/{@code PAYING} 을 출발점으로 하므로
 * 서로 경쟁한다.
 *
 * <p><b>주장 범위 (CLAUDE.md 규칙 30).</b>
 * <ul>
 *   <li>구조적 논증 — 세 UPDATE 가 모두 출발 상태를 {@code WHERE} 에 명시한다.
 *       InnoDB 행 잠금이 직렬화한 뒤 뒤에 온 쪽은 조건 불일치로 {@code affected_rows = 0} 이 된다.</li>
 *   <li>결정적 재현 — 각 순서를 고정해 검증한다 ({@code 결정적_순서}).</li>
 *   <li>통계적 보조 결과 — 동시 경합 반복에서 위반이 관측되지 않았다. 증명이 아니다.</li>
 * </ul>
 */
@Timeout(180)
@DisplayName("자발적 해제와 확정 / 만료의 경쟁")
class SeatReleaseRaceTest extends MySqlTestSupport {

    private static final long SCHEDULE_ID = 1L;
    private static final Duration HOLD_DURATION = Duration.ofMinutes(5);
    private static final Duration PAYMENT_DURATION = Duration.ofMinutes(5);

    private static final HoldId HOLD = HoldId.of("abcdefab-cdef-4abc-8def-abcdefabcdef");
    private static final UserId OWNER = new UserId(7L);
    private static final ReservationId RESERVATION = new ReservationId(100L);

    private static final int ROUNDS = 150;

    private JdbcSeatHoldRepository holdRepository;
    private JdbcSeatPaymentRepository paymentRepository;
    private JdbcSeatExpiryRepository expiryRepository;
    private JdbcSeatReleaseRepository releaseRepository;

    @BeforeEach
    void setUp() {
        holdRepository = new JdbcSeatHoldRepository(dataSource(), HOLD_DURATION);
        paymentRepository = new JdbcSeatPaymentRepository(dataSource(), PAYMENT_DURATION);
        expiryRepository = new JdbcSeatExpiryRepository(dataSource());
        releaseRepository = new JdbcSeatReleaseRepository(dataSource());
    }

    private long givenPayingSeat(String seatNo) {
        long seatId = insertAvailableSeat(SCHEDULE_ID, seatNo);
        holdRepository.hold(new SeatId(seatId), HOLD, OWNER);
        paymentRepository.startPayment(new SeatId(seatId), HOLD);
        return seatId;
    }

    private long givenExpiredHeldSeat(String seatNo) {
        long seatId = insertAvailableSeat(SCHEDULE_ID, seatNo);
        holdRepository.hold(new SeatId(seatId), HOLD, OWNER);
        jdbc().update(
                "UPDATE seat_inventory SET expires_at = DATE_SUB(NOW(3), INTERVAL 1 SECOND) WHERE id = ?",
                seatId);
        return seatId;
    }

    @Nested
    @DisplayName("결정적 순서 검증")
    class 결정적_순서 {

        @Test
        void 해제가_먼저면_뒤늦은_확정은_0건이다() {
            long seatId = givenPayingSeat("1A");

            int released = releaseRepository.releaseAll(HOLD, OWNER);
            SeatConfirmationOutcome confirmed =
                    paymentRepository.confirm(new SeatId(seatId), HOLD, RESERVATION);

            assertThat(released).isEqualTo(1);
            assertThat(confirmed).isEqualTo(SeatConfirmationOutcome.NOT_CONFIRMED);
            assertThat(statusOf(seatId)).isEqualTo("AVAILABLE");
            assertThat(reservationIdOf(seatId)).isNull();
        }

        @Test
        void 확정이_먼저면_뒤늦은_해제는_0건이다() {
            long seatId = givenPayingSeat("1A");
            List<SeatId> candidates = releaseRepository.findReleasableSeats(HOLD, OWNER);

            SeatConfirmationOutcome confirmed =
                    paymentRepository.confirm(new SeatId(seatId), HOLD, RESERVATION);
            int released = releaseRepository.release(HOLD, OWNER, candidates);

            assertThat(confirmed).isEqualTo(SeatConfirmationOutcome.CONFIRMED);
            assertThat(released).isZero();
            assertThat(statusOf(seatId)).isEqualTo("SOLD");
            assertThat(reservationIdOf(seatId)).isEqualTo(RESERVATION.value());
        }

        @Test
        void 해제가_먼저면_뒤늦은_만료는_0건이다() {
            long seatId = givenExpiredHeldSeat("1A");
            List<ExpiryCandidate> expiryCandidates = expiryRepository.findExpiredCandidates(100);

            int released = releaseRepository.releaseAll(HOLD, OWNER);
            int expired = expiryRepository.expire(expiryCandidates);

            assertThat(released).isEqualTo(1);
            assertThat(expired).isZero();
            assertThat(statusOf(seatId)).isEqualTo("AVAILABLE");
        }

        @Test
        void 만료가_먼저면_뒤늦은_해제는_0건이다() {
            long seatId = givenExpiredHeldSeat("1A");
            List<SeatId> releaseCandidates = releaseRepository.findReleasableSeats(HOLD, OWNER);

            int expired = expiryRepository.sweep(100);
            int released = releaseRepository.release(HOLD, OWNER, releaseCandidates);

            assertThat(expired).isEqualTo(1);
            assertThat(released).isZero();
            assertThat(statusOf(seatId)).isEqualTo("AVAILABLE");
        }

        @Test
        void 어느_순서든_version_은_한_번만_증가한다() {
            long confirmFirst = givenPayingSeat("1A");
            Long before = versionOf(confirmFirst);
            List<SeatId> candidates = releaseRepository.findReleasableSeats(HOLD, OWNER);
            paymentRepository.confirm(new SeatId(confirmFirst), HOLD, RESERVATION);
            releaseRepository.release(HOLD, OWNER, candidates);

            assertThat(versionOf(confirmFirst))
                    .as("성공한 전이는 하나뿐이다")
                    .isEqualTo(before + 1);
        }
    }

    @Nested
    @DisplayName("동시 경합 (통계적 보조 결과)")
    class 동시_경합 {

        @Test
        void 해제와_확정을_동시_출발시켜도_최종_상태는_둘_중_하나다() throws Exception {
            List<String> violations = new CopyOnWriteArrayList<>();
            List<String> errors = new CopyOnWriteArrayList<>();
            int releaseWins = 0;
            int confirmWins = 0;

            for (int round = 0; round < ROUNDS; round++) {
                long seatId = givenPayingSeat("seat-" + round);
                Long versionBefore = versionOf(seatId);
                List<SeatId> candidates = List.of(new SeatId(seatId));

                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(2);
                AtomicReference<Integer> released = new AtomicReference<>(0);
                AtomicReference<SeatConfirmationOutcome> confirmed = new AtomicReference<>();

                try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                    pool.submit(() -> {
                        ready.countDown();
                        try {
                            start.await();
                            released.set(releaseRepository.release(HOLD, OWNER, candidates));
                        } catch (Exception e) {
                            errors.add("release: " + e.getClass().getSimpleName());
                        } finally {
                            done.countDown();
                        }
                    });
                    pool.submit(() -> {
                        ready.countDown();
                        try {
                            start.await();
                            confirmed.set(paymentRepository.confirm(
                                    new SeatId(seatId), HOLD, RESERVATION));
                        } catch (Exception e) {
                            errors.add("confirm: " + e.getClass().getSimpleName());
                        } finally {
                            done.countDown();
                        }
                    });
                    assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
                    start.countDown();
                    assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
                }

                String finalStatus = statusOf(seatId);
                boolean releaseWon = released.get() == 1;
                boolean confirmWon = confirmed.get() == SeatConfirmationOutcome.CONFIRMED;

                if (releaseWon == confirmWon) {
                    violations.add("round %d: 성공한 전이가 %s"
                            .formatted(round, releaseWon ? "둘 다" : "없다"));
                    continue;
                }
                if (versionOf(seatId) != versionBefore + 1) {
                    violations.add("round %d: version 증가 %d"
                            .formatted(round, versionOf(seatId) - versionBefore));
                }

                if (confirmWon) {
                    confirmWins++;
                    if (!"SOLD".equals(finalStatus) || reservationIdOf(seatId) == null
                            || holdIdOf(seatId) != null || expiresAtOf(seatId) != null) {
                        violations.add("round %d: SOLD 필드 조합 어긋남 (%s)"
                                .formatted(round, finalStatus));
                    }
                } else {
                    releaseWins++;
                    if (!"AVAILABLE".equals(finalStatus) || reservationIdOf(seatId) != null
                            || holdIdOf(seatId) != null || heldByOf(seatId) != null
                            || expiresAtOf(seatId) != null) {
                        violations.add("round %d: AVAILABLE 필드 조합 어긋남 (%s)"
                                .formatted(round, finalStatus));
                    }
                }
            }

            assertThat(errors).as("시스템 오류 / deadlock / lock timeout").isEmpty();
            assertThat(violations).as("전이 위반 및 필드 조합 위반").isEmpty();
            assertThat(releaseWins + confirmWins).isEqualTo(ROUNDS);
            System.out.printf("[해제 vs 확정 %d회] 해제 승리 %d, 확정 승리 %d%n",
                    ROUNDS, releaseWins, confirmWins);
        }

        @Test
        void 해제와_만료를_동시_출발시켜도_전이는_한_번만_일어난다() throws Exception {
            List<String> violations = new CopyOnWriteArrayList<>();
            List<String> errors = new CopyOnWriteArrayList<>();
            int releaseWins = 0;
            int expiryWins = 0;

            for (int round = 0; round < ROUNDS; round++) {
                long seatId = givenExpiredHeldSeat("seat-" + round);
                Long versionBefore = versionOf(seatId);
                List<SeatId> releaseCandidates = List.of(new SeatId(seatId));
                List<ExpiryCandidate> expiryCandidates =
                        List.of(new ExpiryCandidate(new SeatId(seatId), HOLD));

                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(2);
                AtomicReference<Integer> released = new AtomicReference<>(0);
                AtomicReference<Integer> expired = new AtomicReference<>(0);

                try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                    pool.submit(() -> {
                        ready.countDown();
                        try {
                            start.await();
                            released.set(releaseRepository.release(HOLD, OWNER, releaseCandidates));
                        } catch (Exception e) {
                            errors.add("release: " + e.getClass().getSimpleName());
                        } finally {
                            done.countDown();
                        }
                    });
                    pool.submit(() -> {
                        ready.countDown();
                        try {
                            start.await();
                            expired.set(expiryRepository.expire(expiryCandidates));
                        } catch (Exception e) {
                            errors.add("expire: " + e.getClass().getSimpleName());
                        } finally {
                            done.countDown();
                        }
                    });
                    assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
                    start.countDown();
                    assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
                }

                // 두 전이의 결과 상태가 같으므로(AVAILABLE) 구분은 affected_rows 로만 가능하다.
                int totalTransitions = released.get() + expired.get();
                if (totalTransitions != 1) {
                    violations.add("round %d: 성공한 전이 %d 건".formatted(round, totalTransitions));
                    continue;
                }
                if (versionOf(seatId) != versionBefore + 1) {
                    violations.add("round %d: version 증가 %d"
                            .formatted(round, versionOf(seatId) - versionBefore));
                }
                if (!"AVAILABLE".equals(statusOf(seatId)) || holdIdOf(seatId) != null
                        || heldByOf(seatId) != null || expiresAtOf(seatId) != null
                        || reservationIdOf(seatId) != null) {
                    violations.add("round %d: AVAILABLE 필드 조합 어긋남".formatted(round));
                }

                if (released.get() == 1) {
                    releaseWins++;
                } else {
                    expiryWins++;
                }
            }

            assertThat(errors).as("시스템 오류 / deadlock / lock timeout").isEmpty();
            assertThat(violations).as("중복 전이 및 필드 조합 위반").isEmpty();
            assertThat(releaseWins + expiryWins).isEqualTo(ROUNDS);
            System.out.printf("[해제 vs 만료 %d회] 해제 승리 %d, 만료 승리 %d%n",
                    ROUNDS, releaseWins, expiryWins);
        }

        @Test
        void 경합_후_전체_테이블에_역행이나_잘못된_조합이_없다() throws Exception {
            // 예외를 삼키면 한쪽 작업이 실패하고 다른 쪽만 성공해도
            // 최종 DB 상태가 정상처럼 보여 테스트가 통과한다. 전부 수집해 검증한다.
            List<String> errors = new CopyOnWriteArrayList<>();

            for (int round = 0; round < ROUNDS; round++) {
                long seatId = givenPayingSeat("seat-" + round);
                List<SeatId> candidates = List.of(new SeatId(seatId));
                int currentRound = round;
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(2);

                try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                    pool.submit(() -> {
                        ready.countDown();
                        try {
                            start.await();
                            releaseRepository.release(HOLD, OWNER, candidates);
                        } catch (Exception e) {
                            errors.add("round %d release: %s: %s"
                                    .formatted(currentRound, e.getClass().getSimpleName(),
                                            e.getMessage()));
                        } finally {
                            done.countDown();
                        }
                    });
                    pool.submit(() -> {
                        ready.countDown();
                        try {
                            start.await();
                            paymentRepository.confirm(new SeatId(seatId), HOLD, RESERVATION);
                        } catch (Exception e) {
                            errors.add("round %d confirm: %s: %s"
                                    .formatted(currentRound, e.getClass().getSimpleName(),
                                            e.getMessage()));
                        } finally {
                            done.countDown();
                        }
                    });
                    assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
                    start.countDown();
                    assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
                }
            }

            assertThat(errors)
                    .as("한쪽만 성공해 상태가 정상처럼 보여도 예외가 있었다면 실패다")
                    .isEmpty();

            Long orphanSold = jdbc().queryForObject("""
                    SELECT COUNT(*) FROM seat_inventory
                     WHERE status='SOLD' AND (reservation_id IS NULL OR hold_id IS NOT NULL
                                              OR expires_at IS NOT NULL)
                    """, Long.class);
            Long leakedAvailable = jdbc().queryForObject("""
                    SELECT COUNT(*) FROM seat_inventory
                     WHERE status='AVAILABLE'
                       AND (reservation_id IS NOT NULL OR hold_id IS NOT NULL
                            OR held_by IS NOT NULL OR expires_at IS NOT NULL)
                    """, Long.class);
            Long stuck = jdbc().queryForObject(
                    "SELECT COUNT(*) FROM seat_inventory WHERE status IN ('HELD','PAYING')",
                    Long.class);

            assertThat(orphanSold).as("SOLD 필드 조합 위반").isZero();
            assertThat(leakedAvailable).as("AVAILABLE 잔여 필드").isZero();
            assertThat(stuck).as("경합 후 중간 상태로 남은 좌석").isZero();
        }
    }
}
