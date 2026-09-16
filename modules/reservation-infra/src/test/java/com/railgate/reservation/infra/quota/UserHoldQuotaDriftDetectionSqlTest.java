package com.railgate.reservation.infra.quota;

import com.railgate.reservation.quota.QuotaAcquireOutcome;
import com.railgate.reservation.quota.QuotaReleaseOutcome;
import static org.assertj.core.api.Assertions.assertThat;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.infra.MySqlTestSupport;
import com.railgate.reservation.infra.seat.JdbcMultiSeatHoldRepository;
import com.railgate.reservation.saleevent.SaleEventId;
import com.railgate.reservation.seat.SeatId;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * ★ 운영 drift 탐지 SQL 검증 (Task 2G-G).
 *
 * <h2>파일을 그대로 읽어 실행한다</h2>
 *
 * <p>{@code load-test/verify/user_hold_quota_drift.sql} 을 <b>테스트가 직접 읽는다.</b>
 * SQL 을 자바 상수로 복사해 두면 운영 파일이 바뀌어도 이 검증이 눈치채지 못한다.
 * {@code JdbcSeatExpiryRepositoryTest} 가 운영 SQL 상수를 그대로 EXPLAIN 하는 것과 같은 원칙이다.
 *
 * <h2>탐지만 한다</h2>
 *
 * <p>이 SQL 에는 보정문이 없다. 좌석과 카운터 중 무엇이 진실의 원천인지 근거 없이 정할 수
 * 없고, 특히 확정 경로의 quota 감소가 아직 없어 <b>카운터가 "옳게" 클 수 있다</b>
 * (TASK-002G-C 미해결). 그 상태에서 좌석 집계에 맞추면 진짜 문제를 지운다.
 */
@Timeout(300)
@DisplayName("user_hold_quota drift 탐지 SQL (Task 2G-G)")
class UserHoldQuotaDriftDetectionSqlTest extends MySqlTestSupport {

    private static final Duration HOLD_DURATION = Duration.ofMinutes(5);

    private static final SaleEventId CHUSEOK = new SaleEventId(9001L);
    private static final SaleEventId SEOLLAL = new SaleEventId(9002L);
    private static final long SCHEDULE_CHUSEOK = 101L;
    private static final long SCHEDULE_SEOLLAL = 201L;

    private static final UserId USER_A = new UserId(11L);
    private static final UserId USER_B = new UserId(22L);

    /** 파일에서 읽은 세 쿼리. 순서는 파일의 V-7a·V-7b·V-7c 와 같다. */
    private static List<String> driftQueries;

    private JdbcUserHoldQuotaRepository quota;
    private JdbcMultiSeatHoldRepository holdRepository;

    @BeforeEach
    void setUp() {
        if (driftQueries == null) {
            driftQueries = loadDriftQueries();
        }
        quota = new JdbcUserHoldQuotaRepository(dataSource());
        holdRepository = new JdbcMultiSeatHoldRepository(
                dataSource(), transactionManager(), HOLD_DURATION);

        insertSaleEvent(CHUSEOK, "추석");
        insertSaleEvent(SEOLLAL, "설");
        jdbc().update("INSERT INTO train_schedule (id, sale_event_id) VALUES (?, ?), (?, ?)",
                SCHEDULE_CHUSEOK, CHUSEOK.value(), SCHEDULE_SEOLLAL, SEOLLAL.value());
    }

    /**
     * 저장소 루트를 찾아 검증 SQL 파일을 읽는다.
     *
     * <p>Gradle 의 테스트 작업 디렉터리는 모듈마다 다르므로, {@code settings.gradle.kts} 가
     * 있는 곳까지 거슬러 올라가 루트를 정한다. 상대 경로를 하드코딩하면 모듈이 옮겨질 때 깨진다.
     */
    private static List<String> loadDriftQueries() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("settings.gradle.kts"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IllegalStateException("저장소 루트를 찾지 못했다 (settings.gradle.kts 없음)");
        }
        Path sqlFile = root.resolve("load-test/verify/user_hold_quota_drift.sql");
        String content;
        try {
            content = Files.readString(sqlFile);
        } catch (IOException e) {
            throw new UncheckedIOException("검증 SQL 을 읽지 못했다: " + sqlFile, e);
        }

        List<String> queries = new ArrayList<>();
        for (String statement : content.split(";")) {
            // 주석만 남은 조각을 버린다. 실제 실행되는 문장만 남긴다.
            String stripped = Arrays.stream(statement.split("\n"))
                    .filter(line -> !line.stripLeading().startsWith("--"))
                    .reduce("", (a, b) -> a + "\n" + b)
                    .strip();
            if (!stripped.isEmpty()) {
                queries.add(stripped);
            }
        }
        return List.copyOf(queries);
    }

    private static void insertSaleEvent(SaleEventId id, String name) {
        jdbc().update("""
                INSERT INTO sale_event (id, name, opens_at, status)
                VALUES (?, ?, DATE_SUB(NOW(3), INTERVAL 1 DAY), 'OPEN')
                """, id.value(), name);
    }

    private List<SeatId> holdSeats(UserId user, long scheduleId, SaleEventId saleEventId,
            int count, String prefix) {
        inTransaction(() -> {
            quota.ensureRow(saleEventId, user);
            assertThat(quota.tryAcquire(saleEventId, user, count))
                    .isEqualTo(QuotaAcquireOutcome.ACQUIRED);
        });
        List<SeatId> seats = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            seats.add(new SeatId(insertAvailableSeat(scheduleId, prefix + i)));
        }
        inTransaction(() -> holdRepository.holdAll(seats, HoldId.newId(), user));
        return seats;
    }

    /** 세 쿼리를 모두 실행해 반환된 행을 합친다. 하나라도 행이 있으면 drift 다. */
    private static List<Map<String, Object>> detectAll() {
        List<Map<String, Object>> rows = new ArrayList<>();
        driftQueries.forEach(sql -> rows.addAll(jdbc().queryForList(sql)));
        return rows;
    }

    private static List<Map<String, Object>> run(int index) {
        return jdbc().queryForList(driftQueries.get(index));
    }

    // ------------------------------------------------------------------

    @Test
    void 파일에서_세_쿼리를_읽는다() {
        assertThat(driftQueries)
                .as("V-7a·V-7b·V-7c 세 문장이어야 한다")
                .hasSize(3);
        assertThat(driftQueries.get(0)).contains("user_hold_quota").contains("drift_kind");
        assertThat(driftQueries)
                .as("보정문을 함께 두지 않는다")
                .noneSatisfy(sql -> assertThat(sql.toUpperCase())
                        .containsAnyOf("UPDATE ", "DELETE ", "INSERT "));
    }

    /**
     * ★ 정상 상태에서는 아무것도 보고하지 않는다.
     *
     * <p>그래야 탐지가 신호가 된다. 정상에서도 행이 나오면 아무도 보지 않게 된다.
     */
    @Nested
    @DisplayName("§1 정상 상태")
    class 정상_상태 {

        @Test
        void 카운터와_활성_좌석이_같으면_보고하지_않는다() {
            holdSeats(USER_A, SCHEDULE_CHUSEOK, CHUSEOK, 3, "A");
            holdSeats(USER_B, SCHEDULE_CHUSEOK, CHUSEOK, 2, "B");

            assertThat(detectAll()).isEmpty();
        }

        /** ★ 카운터 0 이고 활성 좌석도 0 이면 drift 가 아니다. */
        @Test
        void 카운터_0_과_활성_좌석_0_은_drift_가_아니다() {
            inTransaction(() -> quota.ensureRow(CHUSEOK, USER_A));

            assertThat(detectAll())
                    .as("좌석을 다 놓아준 뒤의 정상 상태다. 행은 0 으로 남는다")
                    .isEmpty();
        }

        @Test
        void 전량_반납_후에도_보고하지_않는다() {
            List<SeatId> seats = holdSeats(USER_A, SCHEDULE_CHUSEOK, CHUSEOK, 3, "A");
            releaseAll(seats, 3);

            assertThat(jdbc().queryForObject("""
                    SELECT held_seats FROM user_hold_quota
                     WHERE sale_event_id = ? AND user_id = ?
                    """, Integer.class, CHUSEOK.value(), USER_A.value())).isZero();
            assertThat(detectAll()).isEmpty();
        }

        /** 서로 다른 회차는 별개로 대조한다. 합산하면 없는 drift 를 만들어낸다. */
        @Test
        void 서로_다른_회차는_별개로_대조한다() {
            holdSeats(USER_A, SCHEDULE_CHUSEOK, CHUSEOK, 3, "C");
            holdSeats(USER_A, SCHEDULE_SEOLLAL, SEOLLAL, 2, "S");

            assertThat(detectAll()).isEmpty();
        }

        /** SOLD 는 활성 선점이 아니다 (2G-D §4). */
        @Test
        void SOLD_는_활성으로_세지_않는다() {
            List<SeatId> seats = holdSeats(USER_A, SCHEDULE_CHUSEOK, CHUSEOK, 3, "A");
            jdbc().update("""
                    UPDATE seat_inventory
                       SET status = 'SOLD', reservation_id = 777,
                           hold_id = NULL, held_by = NULL, held_at = NULL, expires_at = NULL
                     WHERE id = ?
                    """, seats.get(0).value());
            releaseAll(seats.subList(0, 1), 1);

            assertThat(detectAll()).isEmpty();
        }

        private void releaseAll(List<SeatId> seats, int count) {
            inTransaction(() -> assertThat(quota.release(CHUSEOK, USER_A, count))
                    .isEqualTo(QuotaReleaseOutcome.RELEASED));
            seats.forEach(seat -> jdbc().update("""
                    UPDATE seat_inventory
                       SET status = 'AVAILABLE', hold_id = NULL, held_by = NULL,
                           held_at = NULL, expires_at = NULL
                     WHERE id = ? AND status IN ('HELD','PAYING')
                    """, seat.value()));
        }
    }

    /**
     * ★ §2 네 가지 drift 를 각각 탐지한다.
     */
    @Nested
    @DisplayName("§2 ★ drift 방향별 탐지")
    class drift_탐지 {

        @Test
        void 카운터_과대() {
            holdSeats(USER_A, SCHEDULE_CHUSEOK, CHUSEOK, 1, "A");
            jdbc().update("UPDATE user_hold_quota SET held_seats = 3 WHERE user_id = ?",
                    USER_A.value());

            assertThat(run(0)).singleElement().satisfies(row -> {
                assertThat(row.get("drift_kind")).isEqualTo("QUOTA_GREATER");
                assertThat(row.get("quota_held_seats")).isEqualTo(3);
                assertThat(((Number) row.get("active_seats")).intValue()).isEqualTo(1);
            });
        }

        @Test
        void 카운터_과소() {
            holdSeats(USER_A, SCHEDULE_CHUSEOK, CHUSEOK, 3, "A");
            jdbc().update("UPDATE user_hold_quota SET held_seats = 1 WHERE user_id = ?",
                    USER_A.value());

            assertThat(run(0)).singleElement().satisfies(row ->
                    assertThat(row.get("drift_kind")).isEqualTo("QUOTA_LESS"));
        }

        @Test
        void 활성_좌석_없는_카운터() {
            inTransaction(() -> {
                quota.ensureRow(CHUSEOK, USER_A);
                quota.tryAcquire(CHUSEOK, USER_A, 2);
            });

            assertThat(run(0)).singleElement().satisfies(row -> {
                assertThat(row.get("drift_kind")).isEqualTo("QUOTA_WITHOUT_SEATS");
                assertThat(((Number) row.get("active_seats")).intValue()).isZero();
            });
        }

        /** ★ 가장 위험한 방향 — 그 사용자에게는 상한 검사가 아예 적용되지 않는다. */
        @Test
        void 카운터_행_부재() {
            holdSeats(USER_A, SCHEDULE_CHUSEOK, CHUSEOK, 3, "A");
            jdbc().update("DELETE FROM user_hold_quota WHERE user_id = ?", USER_A.value());

            assertThat(run(0)).as("V-7a 로는 보이지 않는다").isEmpty();
            assertThat(run(1)).singleElement().satisfies(row -> {
                assertThat(row.get("drift_kind")).isEqualTo("SEATS_WITHOUT_QUOTA");
                assertThat(((Number) row.get("active_seats")).intValue()).isEqualTo(3);
            });
        }

        /** ★ 대조 쿼리의 사각지대 — 집계에서 빠져 "맞아 보인다". */
        @Test
        void held_by_NULL_활성_좌석() {
            List<SeatId> seats = holdSeats(USER_A, SCHEDULE_CHUSEOK, CHUSEOK, 3, "A");
            jdbc().update("UPDATE seat_inventory SET held_by = NULL WHERE id = ?",
                    seats.get(0).value());
            inTransaction(() -> quota.release(CHUSEOK, USER_A, 1));

            assertThat(run(0)).as("카운터 2, 집계 2 로 맞아 보인다").isEmpty();
            assertThat(run(1)).isEmpty();
            assertThat(run(2)).singleElement().satisfies(row -> {
                assertThat(row.get("drift_kind")).isEqualTo("ACTIVE_SEAT_WITHOUT_HOLDER");
                assertThat(((Number) row.get("seat_id")).longValue())
                        .isEqualTo(seats.get(0).value());
            });
        }

        @Test
        void 여러_사용자의_drift_를_함께_보고한다() {
            holdSeats(USER_A, SCHEDULE_CHUSEOK, CHUSEOK, 3, "A");
            holdSeats(USER_B, SCHEDULE_CHUSEOK, CHUSEOK, 2, "B");
            jdbc().update("DELETE FROM user_hold_quota WHERE user_id = ?", USER_A.value());
            jdbc().update("UPDATE user_hold_quota SET held_seats = 1 WHERE user_id = ?",
                    USER_B.value());

            assertThat(detectAll()).hasSize(2);
            assertThat(detectAll()).extracting(row -> row.get("drift_kind"))
                    .containsExactlyInAnyOrder("SEATS_WITHOUT_QUOTA", "QUOTA_LESS");
        }
    }

    /** 탐지는 아무것도 고치지 않는다. 반복 실행해도 결과가 같다. */
    @Nested
    @DisplayName("§3 읽기 전용")
    class 읽기_전용 {

        @Test
        void 탐지_후에도_상태가_그대로다() {
            holdSeats(USER_A, SCHEDULE_CHUSEOK, CHUSEOK, 3, "A");
            jdbc().update("UPDATE user_hold_quota SET held_seats = 1 WHERE user_id = ?",
                    USER_A.value());

            assertThat(detectAll()).hasSize(1);

            assertThat(jdbc().queryForObject("""
                    SELECT held_seats FROM user_hold_quota WHERE user_id = ?
                    """, Integer.class, USER_A.value()))
                    .as("자동 보정하지 않는다")
                    .isEqualTo(1);
            assertThat(jdbc().queryForObject("""
                    SELECT COUNT(*) FROM seat_inventory
                     WHERE held_by = ? AND status IN ('HELD','PAYING')
                    """, Long.class, USER_A.value()))
                    .as("좌석도 건드리지 않는다")
                    .isEqualTo(3);
        }

        @Test
        void 반복_실행해도_결과가_같다() {
            holdSeats(USER_A, SCHEDULE_CHUSEOK, CHUSEOK, 2, "A");
            jdbc().update("DELETE FROM user_hold_quota WHERE user_id = ?", USER_A.value());

            assertThat(detectAll()).isEqualTo(detectAll());
        }
    }
}
