package com.railgate.reservation.infra.seat;

import static org.assertj.core.api.Assertions.assertThat;

import com.railgate.reservation.expiry.ExpiredSeat;
import com.railgate.reservation.expiry.ExpiryCursor;
import com.railgate.reservation.infra.MySqlTestSupport;
import com.railgate.reservation.infra.saleevent.QueryPlanProbe;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ★ 커서 조회가 <b>실제로 하한을 적용해 읽는가</b> (Task 2H-D 리뷰 보완).
 *
 * <h2>왜 EXPLAIN 만으로는 부족한가</h2>
 *
 * <p>{@code type = range} 나 인덱스 이름은 <b>커서 하한이 적용됐다는 증거가 아니다.</b>
 * {@code expires_at <= NOW(3)} 조건 하나만으로도 같은 인덱스의 range 스캔이 나온다.
 * 커서가 실제로 시작점을 옮겼는지는 <b>읽은 행 수</b>로만 알 수 있다.
 *
 * <p>그래서 {@link QueryPlanProbe#rowsRead} 로 {@code Handler_read%} 세션 카운터의 증가분을
 * 잰다. 절대 실행 시간을 쓰지 않으므로 머신 성능에 좌우되지 않는다.
 *
 * <h2>무엇을 재현하는가</h2>
 *
 * <p>만료 시각이 <b>완전히 같은</b> 좌석이 많을 때가 최악이다. 행 생성자 비교
 * {@code (expires_at, id) > (?, ?)} 를 MySQL 8.4 가 복합 인덱스의 시작점으로 접지 못하면,
 * 옵티마이저는 {@code expires_at <= NOW(3)} 만으로 범위를 잡고 <b>그 안을 전부 훑으면서</b>
 * 행 생성자 조건으로 걸러낸다. 커서가 뒤쪽을 가리킬수록 읽는 행이 선형으로 늘어난다.
 */
@DisplayName("★ 만료 후보 커서 조회의 실제 스캔 범위")
class SeatExpiryCursorScanTest extends MySqlTestSupport {

    /** 같은 만료 시각을 공유할 좌석 수. 하한이 적용되면 읽는 행이 이 값과 무관해야 한다. */
    private static final int TIED_SEATS = 600;

    private static final long SCHEDULE_ID = 1L;

    private JdbcSeatExpiryRepository repository;
    private QueryPlanProbe probe;
    private Timestamp sharedExpiresAt;
    private long lastSeatId;

    @BeforeEach
    void setUp() {
        repository = new JdbcSeatExpiryRepository(dataSource());
        probe = new QueryPlanProbe(dataSource());
        ensureTrainSchedule(SCHEDULE_ID);

        // 만료 시각이 완전히 같은 좌석 다수. 정렬은 id 로만 갈린다.
        jdbc().update("""
                INSERT INTO seat_inventory (schedule_id, seat_no, status, hold_id, held_by,
                                            held_at, expires_at)
                WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < ?)
                SELECT ?, CONCAT('T', i), 'HELD', UUID(), 7,
                       NOW(3), DATE_SUB(NOW(3), INTERVAL 30 SECOND)
                  FROM n
                """, TIED_SEATS, SCHEDULE_ID);
        jdbc().execute("ANALYZE TABLE seat_inventory");

        sharedExpiresAt = jdbc().queryForObject(
                "SELECT MIN(expires_at) FROM seat_inventory WHERE status = 'HELD'", Timestamp.class);
        lastSeatId = jdbc().queryForObject(
                "SELECT MAX(id) FROM seat_inventory WHERE status = 'HELD'", Long.class);
    }

    /**
     * ★★ 커서가 <b>마지막 직전</b>을 가리켜도 남은 1행만 읽어야 한다.
     *
     * <p>하한이 적용되지 않으면 {@code expires_at <= NOW(3)} 범위 전체({@value #TIED_SEATS} 행)를
     * 훑는다. 여유를 두고 <b>후보 수의 절반</b>을 넘지 않는 것으로 고정한다 — 정확한 행 수는
     * 인덱스 구조와 옵티마이저 판단에 따라 한두 행 달라질 수 있지만, <b>선형 스캔과
     * 하한 적용은 그 사이에서 갈리지 않는다.</b>
     */
    @Test
    void 커서가_뒤쪽을_가리켜도_남은_행만_읽는다() {
        long rows = probe.rowsRead(JdbcSeatExpiryRepository.FIND_ATTRIBUTED_CANDIDATES_AFTER_SQL,
                sharedExpiresAt, sharedExpiresAt, lastSeatId - 1, 1);

        assertThat(rows)
                .as("★★ 같은 만료 시각 %d행 중 마지막 1행을 읽는데 %d행을 훑었다 — "
                        + "하한이 적용되지 않으면 범위 전체를 읽는다", TIED_SEATS, rows)
                .isLessThan(TIED_SEATS / 2);
    }

    /**
     * ★★ 커서 <b>앞</b>의 후보를 늘려도 같은 소량 조회의 스캔이 늘지 않는다.
     *
     * <p>이것이 하한 적용의 핵심 성질이다. 두 지점의 스캔 행 수를 비교하므로
     * 절대값이 아니라 <b>증가 여부</b>로 판정한다.
     */
    @Test
    void 커서_앞의_후보를_늘려도_스캔이_늘지_않는다() {
        long early = probe.rowsRead(JdbcSeatExpiryRepository.FIND_ATTRIBUTED_CANDIDATES_AFTER_SQL,
                sharedExpiresAt, sharedExpiresAt, lastSeatId - TIED_SEATS + 1, 1);
        long late = probe.rowsRead(JdbcSeatExpiryRepository.FIND_ATTRIBUTED_CANDIDATES_AFTER_SQL,
                sharedExpiresAt, sharedExpiresAt, lastSeatId - 1, 1);

        assertThat(late)
                .as("★★ 커서 앞에 약 %d행이 더 있는데 스캔이 늘었다 (앞=%d, 뒤=%d)",
                        TIED_SEATS - 2, early, late)
                .isLessThanOrEqualTo(early + 5);
    }

    /** 하한을 적용해도 <b>결과는 같아야 한다</b> — 최적화가 행을 빠뜨리면 안 된다. */
    @Test
    void 하한을_적용해도_결과_집합은_같다() {
        ExpiryCursor cursor = new ExpiryCursor(sharedExpiresAt.toLocalDateTime(), lastSeatId - 2);

        List<ExpiredSeat> page = repository.findExpiredSeats(10, Optional.of(cursor));

        assertThat(page).extracting(e -> e.seatId().value())
                .as("커서 뒤의 두 좌석이 id 오름차순으로 정확히 나온다")
                .containsExactly(lastSeatId - 1, lastSeatId);
    }

    /** 첫 페이지 조회(커서 없음)는 LIMIT 만큼만 읽는다 — 비교 기준선. */
    @Test
    void 첫_페이지는_LIMIT_만큼만_읽는다() {
        long rows = probe.rowsRead(JdbcSeatExpiryRepository.FIND_ATTRIBUTED_CANDIDATES_SQL, 1);

        assertThat(rows).as("정렬이 인덱스 순서라 LIMIT 이 조기 종료한다").isLessThan(TIED_SEATS / 2);
    }
}
