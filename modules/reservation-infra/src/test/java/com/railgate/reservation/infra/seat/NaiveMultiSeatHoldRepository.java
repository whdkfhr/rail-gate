package com.railgate.reservation.infra.seat;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.seat.SeatId;
import com.railgate.reservation.seat.SeatUnavailableException;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * <b>의도적으로 잘못 만든 구현. 테스트 전용이며 운영 코드에 존재하지 않는다.</b>
 *
 * <p>CLAUDE.md 규칙 11 이 금지하는 패턴이다. 여러 좌석을 반복문으로 하나씩 UPDATE 하며,
 * 트랜잭션 경계가 없어 각 문장이 즉시 커밋된다.
 *
 * <p>각 UPDATE 자체는 조건부 UPDATE 라서 <b>단일 좌석 수준에서는 올바르다.</b>
 * 그래서 이 구현의 결함은 경쟁 조건이 아니다. 동시 요청이 하나도 없어도 깨진다.
 * 중간에 한 좌석이 불가능하면 <b>앞서 성공한 좌석들이 그대로 남는다.</b>
 * 되돌릴 수단이 없기 때문이다.
 *
 * <p>존재 이유는 CLAUDE.md 규칙 27 이다. 벌크 UPDATE + 롤백이 실제로 무언가를 막고 있음을
 * 보이려면, 그것이 없을 때 같은 조건에서 실제로 깨지는 것을 보여야 한다.
 *
 * @see JdbcMultiSeatHoldRepository 올바른 구현
 */
final class NaiveMultiSeatHoldRepository {

    private static final String HOLD_ONE_SQL = """
            UPDATE seat_inventory
               SET status     = 'HELD',
                   hold_id    = ?,
                   held_by    = ?,
                   held_at    = NOW(3),
                   expires_at = DATE_ADD(NOW(3), INTERVAL ? SECOND),
                   version    = version + 1
             WHERE id     = ?
               AND status = 'AVAILABLE'
            """;

    private final JdbcTemplate jdbc;
    private final long holdSeconds;

    NaiveMultiSeatHoldRepository(DataSource dataSource, long holdSeconds) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.holdSeconds = holdSeconds;
    }

    /**
     * 좌석을 하나씩 순회하며 선점한다.
     *
     * <p>운영 구현과 같은 계약({@code void} + {@link SeatUnavailableException})을 쓴다.
     * 비교 대상이 반환 타입이 아니라 <b>실패 후 남는 상태</b> 이기 때문이다.
     *
     * <p>차이는 하나다. 이 구현은 좌석마다 개별 UPDATE 를 확정하므로,
     * 중간에 실패해도 <b>그때까지 성공한 좌석을 되돌릴 방법이 없다.</b>
     */
    void holdAll(List<SeatId> seatIds, HoldId holdId, UserId userId) {
        for (SeatId seatId : seatIds) {
            int affected = jdbc.update(HOLD_ONE_SQL,
                    holdId.asString(), userId.value(), holdSeconds, seatId.value());

            if (affected != 1) {
                // 앞서 잡은 좌석들을 되돌릴 방법이 없다.
                throw new SeatUnavailableException(
                        "요청한 좌석 중 하나 이상을 선점할 수 없어 요청 전체가 실패했다");
            }
        }
    }
}
