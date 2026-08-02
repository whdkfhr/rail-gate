package com.railgate.reservation.infra.seat;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.seat.SeatId;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * <b>의도적으로 잘못 만든 구현. 테스트 전용이며 운영 코드에 존재하지 않는다.</b>
 *
 * <p>CLAUDE.md 규칙 1 이 금지하는 TOCTOU 패턴을 그대로 구현한다.
 * 상태를 {@code SELECT} 로 확인한 뒤 {@code UPDATE} 하며, 두 문장 사이에 창이 열린다.
 *
 * <pre>
 * SELECT status FROM seat_inventory WHERE id = ?     -- 잠금 없음
 *   ...  ← 여기가 창(window). 다른 세션이 끼어든다
 * UPDATE seat_inventory SET status='HELD', ... WHERE id = ?   -- 상태 조건 없음
 * </pre>
 *
 * <p>존재 이유는 CLAUDE.md 규칙 27 이다.
 * "한 번도 실패한 적 없는 동시성 테스트는 테스트가 아니다."
 * 조건부 UPDATE 가 실제로 무언가를 막고 있음을 보이려면,
 * 그것이 없을 때 실제로 깨지는 것을 같은 조건에서 보여야 한다.
 *
 * @see JdbcSeatHoldRepository 올바른 구현
 */
final class NaiveSeatHoldRepository {

    private final JdbcTemplate jdbc;
    private final long holdSeconds;

    NaiveSeatHoldRepository(DataSource dataSource, long holdSeconds) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.holdSeconds = holdSeconds;
    }

    /**
     * @param afterRead 상태를 읽은 뒤 UPDATE 하기 전에 실행된다.
     *                  테스트가 여기에 배리어를 넣어 레이스 창을 결정적으로 벌린다.
     *                  {@code Thread.sleep} 을 쓰지 않는 이유는 머신 부하에 따라
     *                  재현이 되기도 안 되기도 하는 테스트가 되기 때문이다.
     */
    SeatHoldOutcome hold(SeatId seatId, HoldId holdId, UserId userId, Runnable afterRead) {
        // 1단계: 상태 확인. 잠금을 걸지 않으므로 다른 세션이 자유롭게 변경할 수 있다.
        String status = jdbc.queryForObject(
                "SELECT status FROM seat_inventory WHERE id = ?", String.class, seatId.value());

        if (!"AVAILABLE".equals(status)) {
            return SeatHoldOutcome.ALREADY_HELD;
        }

        afterRead.run();

        // 2단계: 갱신. WHERE 에 상태 조건이 없으므로 무조건 성공한다.
        //         1단계에서 본 상태가 아직 유효한지 확인할 방법이 없다.
        jdbc.update("""
                        UPDATE seat_inventory
                           SET status     = 'HELD',
                               hold_id    = ?,
                               held_by    = ?,
                               held_at    = NOW(3),
                               expires_at = DATE_ADD(NOW(3), INTERVAL ? SECOND),
                               version    = version + 1
                         WHERE id = ?
                        """,
                holdId.asString(), userId.value(), holdSeconds, seatId.value());

        return SeatHoldOutcome.HELD;
    }
}
