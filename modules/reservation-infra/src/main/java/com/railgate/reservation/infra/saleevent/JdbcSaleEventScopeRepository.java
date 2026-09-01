package com.railgate.reservation.infra.saleevent;

import com.railgate.reservation.saleevent.SaleEventId;
import com.railgate.reservation.seat.SeatId;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 좌석 목록이 어느 판매 회차의 quota 에 귀속되는지 해석한다.
 *
 * <h2>왜 정규화 조인인가</h2>
 *
 * <p>{@code seat_inventory} 에 {@code sale_event_id} 를 복제하지 않기로 했다
 * (TASK-002G-E-B1 §8). 실제 MySQL 8.4 에서 좌석 60,000행 기준 조인 쪽이 {@code eq_ref}/PRIMARY
 * 였고, 읽는 행 수가 <b>요청 좌석 수</b>에 갇혔다. 좌석을 150배로 늘려도 늘지 않았다.
 *
 * <p><b>조인 비용이 0 이라는 뜻은 아니다.</b> 비정규화 대비 요청당 최대 6행을 더 읽는다.
 * 그 비용이 전체 크기가 아니라 요청 크기에 매여 있어서 받아들인 것이다.
 * 대신 좌석 행과 운행편 행이 어긋나는 drift 가 존재할 수 없다 — 진실의 원천이 하나다.
 *
 * <h2>조회 계약 — 좌석마다 한 행</h2>
 *
 * <p>요청한 <b>좌석 하나당 결과 한 행</b>을 받는다. 그 위에서 두 가지를 검사한다.
 *
 * <ol>
 *   <li><b>행 수가 요청 좌석 수보다 적으면 거부한다.</b> 존재하지 않는 좌석이 섞였다는 뜻이다.</li>
 *   <li><b>{@code sale_event_id} 의 종류가 하나가 아니면 거부한다.</b> 서로 다른 회차의 좌석이
 *       섞였다는 뜻이고, 이벤트 간 quota 는 독립이므로 합산할 수 없다 (2G-D §4).</li>
 * </ol>
 *
 * <p><b>{@code DISTINCT} 를 쓰지 않는 이유가 1번이다.</b> {@code DISTINCT} 로 받으면 없는 좌석이
 * 섞여도 남은 좌석들의 회차가 하나로 모이는 한 통과해 버린다. 호출부는 "네 좌석의 범위" 를
 * 받았다고 믿지만 실제로는 세 좌석의 범위이고, quota 계산이 요청과 다른 집합 위에서 이뤄진다.
 * 좌석 수를 세려면 좌석마다 한 행이 필요하다.
 *
 * <p>중복 좌석 입력도 거부한다. {@code IN} 절에서 합쳐져 1번의 개수 검사를 무의미하게 만든다.
 *
 * <p><b>어느 쪽도 아무 회차나 고르는 대체 동작을 두지 않는다.</b>
 *
 * <h2>이 클래스가 하지 않는 것</h2>
 *
 * <p><b>quota 상한을 검사하지 않는다.</b> 여기서 얻는 것은 "어느 카운터를 볼 것인가" 이고,
 * 그 카운터에 조건부 UPDATE 를 거는 것은 {@code user_hold_quota} 의 몫이다 —
 * <b>그 테이블은 아직 없다. I-12 는 여전히 운영에 구현되지 않았다.</b>
 */
public class JdbcSaleEventScopeRepository {

    /**
     * 한 요청이 담을 수 있는 좌석 수 상한 (REQUIREMENTS.md P-2).
     *
     * <p>{@code IN} 절을 만들기 전에 검사한다. 상한을 넘는 목록은 어차피 quota 를 통과할 수
     * 없고, 길이가 제한되지 않으면 SQL 문자열이 요청에 따라 무한히 길어진다.
     */
    public static final int MAX_SEATS_PER_REQUEST = 4;

    private final JdbcTemplate jdbc;

    public JdbcSaleEventScopeRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
    }

    /**
     * 실행할 SQL. <b>테스트가 이 문자열을 그대로 EXPLAIN 한다.</b>
     *
     * <p>테스트가 복사한 SQL 과 실제 SQL 이 조용히 어긋나면 실행 계획 검증이 아무것도
     * 증명하지 못한다. {@code JdbcSeatExpiryRepository.expireSql} 과 같은 방식이다.
     *
     * <h2>★ STRAIGHT_JOIN 인 이유 — 조인 순서를 고정하지 않으면 비용이 뒤집힌다</h2>
     *
     * <p>이것이 이 Task 에서 실측으로 드러난 문제다. 평범한 {@code JOIN} 으로 두면
     * <b>통계가 최신이 아닐 때 옵티마이저가 조인 순서를 뒤집는다.</b>
     * {@code train_schedule} 을 먼저 훑고, 운행편마다
     * {@code uk_seat_inventory_seat (schedule_id, seat_no)} 로 좌석을 찾아 들어간다.
     * 그러면 읽는 행 수가 <b>요청 좌석 수가 아니라 전체 좌석 수</b>에 비례한다.
     *
     * <p>좌석 600행(운행편 3개 × 200석), 4석 요청 실측:
     * <pre>
     *                        driving table          읽은 행
     *   JOIN (통계 stale)     train_schedule           520 ~ 609
     *   STRAIGHT_JOIN         seat_inventory             5 ~ 7
     *   JOIN (ANALYZE 직후)   seat_inventory             5
     * </pre>
     *
     * <p><b>TASK-002G-E-B1 §7 의 측정(7행)은 픽스처가 {@code ANALYZE TABLE} 을 돌린
     * 상태에서 나온 값이었다.</b> 운영에서는 통계가 계속 흔들리므로 그 조건이 유지되지 않는다.
     * 정규화를 택한 근거가 "비용이 요청 좌석 수에 갇힌다" 였으니, <b>그 근거를 유지하려면
     * 조인 순서를 고정해야 한다.</b> 고정하지 않으면 B1 의 결정 자체가 성립하지 않는다.
     *
     * <p>CLAUDE.md 규칙 3 이 {@code IN} 절 UPDATE 에 {@code FORCE INDEX (PRIMARY)} 를
     * 요구하는 것과 같은 문제다 — 옵티마이저의 선택에 계약을 맡기지 않고 EXPLAIN 어서션으로
     * 강제한다. 다만 여기서 고정해야 하는 것은 인덱스가 아니라 <b>조인 순서</b>다.
     * 좌석 PK 로 먼저 좁히고 나면 {@code ts} 는 {@code eq_ref} 한 건씩이 된다.
     *
     * <p>{@code ANALYZE TABLE} 을 주기적으로 도는 것으로 대신하지 않는다.
     * 그것은 "대체로 맞는 계획" 을 기대하는 것이지 보장이 아니다.
     */
    public static String resolveSql(int seatCount) {
        return """
                SELECT ts.sale_event_id
                  FROM seat_inventory s
                  STRAIGHT_JOIN train_schedule ts ON ts.id = s.schedule_id
                 WHERE s.id IN (%s)
                """.formatted(placeholders(seatCount));
    }

    /**
     * 조인 순서를 고정하지 않은 형태. <b>운영 경로가 아니라 비교 대상이다.</b>
     *
     * <p>{@code NaiveSeatHoldRepository} 와 같은 성격이다 — "고정하지 않으면 어떻게 되는가" 를
     * 테스트가 실제로 관측할 수 있게 남겨 둔다. 통과한다는 것이 옳다는 뜻이 아니다 (규칙 27).
     */
    static String naiveResolveSql(int seatCount) {
        return """
                SELECT ts.sale_event_id
                  FROM seat_inventory s
                  JOIN train_schedule ts ON ts.id = s.schedule_id
                 WHERE s.id IN (%s)
                """.formatted(placeholders(seatCount));
    }

    /**
     * 좌석들이 속한 판매 회차를 해석한다.
     *
     * @throws IllegalArgumentException 목록이 비었거나 {@link #MAX_SEATS_PER_REQUEST} 를 넘는 경우
     * @throws SaleEventScopeException  회차가 하나로 정해지지 않는 경우
     */
    public SaleEventId resolve(List<SeatId> seatIds) {
        Objects.requireNonNull(seatIds, "seatIds");
        if (seatIds.isEmpty()) {
            throw new IllegalArgumentException("좌석이 없는 요청은 quota 범위를 가질 수 없다");
        }
        if (seatIds.size() > MAX_SEATS_PER_REQUEST) {
            throw new IllegalArgumentException(
                    "한 요청의 좌석은 %d석을 넘을 수 없다 (P-2): %d석"
                            .formatted(MAX_SEATS_PER_REQUEST, seatIds.size()));
        }

        if (Set.copyOf(seatIds).size() != seatIds.size()) {
            throw new IllegalArgumentException("같은 좌석이 두 번 들어 있다: " + seatIds);
        }

        Object[] parameters = seatIds.stream().map(seatId -> (Object) seatId.value()).toArray();
        // DISTINCT 를 쓰지 않는다. 좌석마다 한 행을 받아야 "몇 석이 실제로 있었는가" 를 셀 수 있다.
        List<Long> resolved = jdbc.queryForList(
                resolveSql(seatIds.size()), Long.class, parameters);

        // 요청한 좌석이 전부 있어야 한다.
        //
        // 행 수가 모자란다는 것은 존재하지 않는 좌석이 섞였다는 뜻이다. 그것을 무시하고
        // 나머지로 범위를 정하면, 호출부는 "네 좌석의 범위" 를 받았다고 믿지만 실제로는
        // 세 좌석의 범위다. quota 계산이 요청과 다른 집합 위에서 이뤄진다.
        if (resolved.size() != seatIds.size()) {
            throw new SaleEventScopeException(
                    "좌석 %s 중 %d석만 조회됐다. 존재하지 않는 좌석이 섞였다"
                            .formatted(seatIds, resolved.size()));
        }

        Set<Long> distinctEvents = Set.copyOf(resolved);
        if (distinctEvents.size() != 1) {
            throw new SaleEventScopeException(
                    "좌석 %s 가 서로 다른 판매 회차 %s 에 걸쳐 있다. 회차 간 quota 는 독립이므로 합산할 수 없다"
                            .formatted(seatIds, distinctEvents));
        }
        return new SaleEventId(resolved.get(0));
    }

    private static String placeholders(int count) {
        return IntStream.range(0, count).mapToObj(i -> "?").collect(Collectors.joining(", "));
    }
}
