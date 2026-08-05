package com.railgate.reservation.infra.seat;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.seat.SeatId;
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
     * 좌석을 하나씩 순회하며 선점한다. 트랜잭션이 없으므로 각 성공은 즉시 확정된다.
     *
     * @return 하나라도 실패하면 {@link MultiSeatHoldOutcome#SEAT_UNAVAILABLE}.
     *         <b>그러나 그때까지 성공한 좌석은 그대로 남는다.</b>
     */
    MultiSeatHoldOutcome holdAll(List<SeatId> seatIds, HoldId holdId, UserId userId) {
        for (SeatId seatId : seatIds) {
            int affected = jdbc.update(HOLD_ONE_SQL,
                    holdId.asString(), userId.value(), holdSeconds, seatId.value());

            if (affected != 1) {
                // 앞서 잡은 좌석들을 되돌릴 방법이 없다.
                return MultiSeatHoldOutcome.SEAT_UNAVAILABLE;
            }
        }
        return MultiSeatHoldOutcome.HELD;
    }
}
