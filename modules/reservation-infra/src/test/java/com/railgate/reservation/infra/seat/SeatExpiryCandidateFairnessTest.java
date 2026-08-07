package com.railgate.reservation.infra.seat;

import static org.assertj.core.api.Assertions.assertThat;

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
 * ★ 만료 후보 선택의 공정성과 결정성.
 *
 * <p>I-10 은 "만료된 좌석은 다시 판매 가능해진다" 이지만, <b>언제</b> 그렇게 되는지는 말하지 않는다.
 * 배치 크기가 유한한 이상 어떤 후보를 먼저 고르느냐가 실질적인 회수 지연을 결정한다.
 * 특정 좌석이 계속 뒤로 밀리면 그 좌석은 사실상 회수되지 않는 것과 같다.
 *
 * <p><b>왜 상태별 편향이 생기는가.</b>
 * {@code (status, expires_at)} 인덱스는 첫 컬럼이 {@code status} 다.
 * {@code status IN ('HELD','PAYING')} 은 두 개의 범위 스캔이 되고, 스캔은 상태값 순서를 따른다.
 * {@code ORDER BY} 가 없으면 반환 순서는 그 스캔 순서에 좌우되므로,
 * 한 상태의 만료 행이 배치 크기보다 많이 쌓이면 다른 상태의 <b>더 오래된</b> 좌석이
 * 매 배치에서 반복적으로 밀려난다.
 *
 * <p><b>두 정렬의 목적을 혼동하지 않는다.</b>
 * <ul>
 *   <li>후보 SELECT 의 {@code ORDER BY expires_at, id} — <b>공정성</b>.
 *       어떤 만료 좌석을 먼저 처리할지 정한다.</li>
 *   <li>회수 UPDATE 전 Java 의 id 오름차순 정렬 — <b>데드락 방지</b>.
 *       어떤 순서로 행을 잠글지 정한다 (CLAUDE.md 규칙 2).</li>
 * </ul>
 * 후보 SELECT 는 행 잠금을 잡지 않으므로 그 정렬은 잠금 순서와 무관하다.
 */
@Timeout(60)
@DisplayName("만료 후보 선택의 공정성과 결정성 - I-10")
class SeatExpiryCandidateFairnessTest extends MySqlTestSupport {

    private static final long SCHEDULE_ID = 1L;
    private static final Duration HOLD_DURATION = Duration.ofMinutes(5);
    private static final Duration PAYMENT_DURATION = Duration.ofMinutes(5);
    private static final UserId USER = new UserId(7L);

    private JdbcSeatHoldRepository holdRepository;
    private JdbcSeatPaymentRepository paymentRepository;
    private JdbcSeatExpiryRepository repository;

    @BeforeEach
    void setUp() {
        holdRepository = new JdbcSeatHoldRepository(dataSource(), HOLD_DURATION);
        paymentRepository = new JdbcSeatPaymentRepository(dataSource(), PAYMENT_DURATION);
        repository = new JdbcSeatExpiryRepository(dataSource());
    }

    private HoldId holdOf(long seed) {
        return HoldId.of("%08d-0000-4000-8000-000000000000".formatted(seed));
    }

    /** 만료 시각을 SQL 로 명확히 배치한다. Java 시각과 DB 시각의 우연한 일치를 기대하지 않는다. */
    private void setExpiredSecondsAgo(long seatId, int seconds) {
        jdbc().update(
                "UPDATE seat_inventory SET expires_at = DATE_SUB(NOW(3), INTERVAL ? SECOND) WHERE id = ?",
                seconds, seatId);
    }

    private long expiredHeld(String seatNo, long seed, int secondsAgo) {
        long id = insertAvailableSeat(SCHEDULE_ID, seatNo);
        holdRepository.hold(new SeatId(id), holdOf(seed), USER);
        setExpiredSecondsAgo(id, secondsAgo);
        return id;
    }

    private long expiredPaying(String seatNo, long seed, int secondsAgo) {
        long id = insertAvailableSeat(SCHEDULE_ID, seatNo);
        holdRepository.hold(new SeatId(id), holdOf(seed), USER);
        paymentRepository.startPayment(new SeatId(id), holdOf(seed));
        setExpiredSecondsAgo(id, secondsAgo);
        return id;
    }

    @Nested
    @DisplayName("★ 오래된 후보가 뒤로 밀리지 않는다")
    class 공정성 {

        @Test
        void 가장_오래_만료된_좌석이_상태와_무관하게_첫_배치에_포함된다() {
            // 가장 오래된 것은 PAYING 이다.
            long oldestPaying = expiredPaying("oldest", 1, 3600);
            // 그보다 나중에 만료된 HELD 를 batchSize 보다 많이 쌓는다.
            for (int i = 0; i < 10; i++) {
                expiredHeld("held-" + i, 100 + i, 60);
            }

            List<ExpiryCandidate> firstBatch = repository.findExpiredCandidates(3);

            assertThat(firstBatch).hasSize(3);
            assertThat(firstBatch.stream().map(c -> c.seatId().value()))
                    .as("가장 오래 만료된 좌석이 backlog 에 밀려서는 안 된다")
                    .contains(oldestPaying);
            assertThat(firstBatch.get(0).seatId().value())
                    .as("가장 오래된 것이 맨 앞이어야 한다")
                    .isEqualTo(oldestPaying);
        }

        @Test
        void 후보는_만료_시각_오름차순으로_선택된다() {
            long third = expiredHeld("c", 3, 10);
            long first = expiredPaying("a", 1, 300);
            long second = expiredHeld("b", 2, 100);

            List<Long> ids = repository.findExpiredCandidates(10).stream()
                    .map(c -> c.seatId().value())
                    .toList();

            assertThat(ids).containsExactly(first, second, third);
        }

        @Test
        void 만료_시각이_같으면_좌석_식별자_오름차순이다() {
            long first = expiredHeld("a", 1, 60);
            long second = expiredHeld("b", 2, 60);
            long third = expiredHeld("c", 3, 60);
            // 세 좌석의 만료 시각을 완전히 동일하게 맞춘다.
            jdbc().update("""
                    UPDATE seat_inventory
                       SET expires_at = (SELECT * FROM (SELECT MIN(expires_at) FROM seat_inventory) t)
                     WHERE id IN (?, ?, ?)
                    """, first, second, third);

            List<Long> ids = repository.findExpiredCandidates(10).stream()
                    .map(candidate -> candidate.seatId().value())
                    .toList();

            assertThat(ids).containsExactly(first, second, third);
        }

        @Test
        void 같은_데이터에서_반복_조회하면_같은_순서가_나온다() {
            for (int i = 0; i < 5; i++) {
                expiredHeld("held-" + i, 100 + i, 100 - i * 10);
            }
            for (int i = 0; i < 5; i++) {
                expiredPaying("paying-" + i, 200 + i, 95 - i * 10);
            }

            List<Long> first = repository.findExpiredCandidates(6).stream()
                    .map(c -> c.seatId().value()).toList();

            for (int attempt = 0; attempt < 5; attempt++) {
                List<Long> repeated = repository.findExpiredCandidates(6).stream()
                        .map(c -> c.seatId().value()).toList();
                assertThat(repeated).as("조회 %d 회차".formatted(attempt)).isEqualTo(first);
            }
        }

        @Test
        void 작은_배치를_반복하면_오래된_후보부터_빠져나간다() {
            // 만료가 오래된 순서대로 좌석을 만든다. seed 가 클수록 최근 만료다.
            List<Long> expectedOrder = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                int secondsAgo = 600 - i * 100;
                long id = (i % 2 == 0)
                        ? expiredPaying("p-" + i, 300 + i, secondsAgo)
                        : expiredHeld("h-" + i, 400 + i, secondsAgo);
                expectedOrder.add(id);
            }

            List<Long> sweptOrder = new ArrayList<>();
            for (int round = 0; round < 3; round++) {
                List<ExpiryCandidate> batch = repository.findExpiredCandidates(2);
                batch.forEach(c -> sweptOrder.add(c.seatId().value()));
                repository.expire(batch);
            }

            assertThat(sweptOrder)
                    .as("HELD/PAYING 이 섞여 있어도 오래된 순서로 빠져나간다")
                    .containsExactlyElementsOf(expectedOrder);
        }

        @Test
        void 특정_상태만_지속적으로_우선되지_않는다() {
            // HELD 와 PAYING 을 만료 시각 기준으로 번갈아 배치한다.
            List<Long> payingIds = new ArrayList<>();
            List<Long> heldIds = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                payingIds.add(expiredPaying("p-" + i, 300 + i, 800 - i * 200));
                heldIds.add(expiredHeld("h-" + i, 400 + i, 700 - i * 200));
            }

            List<ExpiryCandidate> firstBatch = repository.findExpiredCandidates(4);
            List<String> statuses = firstBatch.stream()
                    .map(c -> statusOf(c.seatId().value()))
                    .toList();

            assertThat(statuses)
                    .as("첫 배치가 한 상태로만 채워지면 다른 상태가 계속 밀린다")
                    .contains("HELD", "PAYING");
            assertThat(payingIds).hasSize(4);
            assertThat(heldIds).hasSize(4);
        }
    }

    @Nested
    @DisplayName("실행 계획")
    class 실행_계획 {

        @Test
        void 후보_조회는_만료_후보_인덱스를_쓰고_filesort_하지_않는다() {
            for (int i = 0; i < 20; i++) {
                expiredHeld("h-" + i, 100 + i, 60 + i);
            }

            // 운영 SQL 상수를 그대로 EXPLAIN 한다.
            // 테스트가 복사한 SQL 과 운영 SQL 이 조용히 어긋나는 것을 막는다.
            Map<String, Object> plan = jdbc().queryForMap(
                    "EXPLAIN " + JdbcSeatExpiryRepository.FIND_CANDIDATES_SQL, 10);

            assertThat(String.valueOf(plan.get("key")))
                    .isEqualTo(JdbcSeatExpiryRepository.CANDIDATE_INDEX);
            assertThat(String.valueOf(plan.get("Extra")))
                    .as("정렬이 인덱스로 해결되어야 LIMIT 조기 종료가 가능하다")
                    .doesNotContain("filesort");
        }

        @Test
        void 만료_UPDATE_는_계속_기본_키_인덱스를_쓴다() {
            long a = expiredHeld("a", 1, 60);
            long b = expiredHeld("b", 2, 60);

            Map<String, Object> plan = jdbc().queryForMap(
                    "EXPLAIN " + JdbcSeatExpiryRepository.expireSql(2),
                    a, holdOf(1).asString(), b, holdOf(2).asString());

            assertThat(String.valueOf(plan.get("key"))).isEqualTo("PRIMARY");
        }
    }
}
