package com.railgate.reservation.infra.seat;

import com.railgate.reservation.seat.SeatId;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * <b>의도적으로 잘못 만든 해제 구현. 테스트 전용이며 운영 코드에 존재하지 않는다.</b>
 *
 * <p>해제를 <b>대상 좌석 기준</b>으로 수행한다. CLAUDE.md 규칙 18 이 금지하는 형태다.
 *
 * <pre>
 * UPDATE seat_inventory SET status='AVAILABLE', ... WHERE id IN (...)
 * </pre>
 *
 * <p>홀드 소유권도, 요청자도, 현재 상태도 확인하지 않는다. 그 결과 두 가지가 깨진다.
 *
 * <ul>
 *   <li><b>남의 홀드를 파괴한다.</b> 응답 유실 후 재요청이 도착할 무렵 그 좌석을
 *       다른 사용자가 이미 잡았다면, 그 사람의 홀드가 조용히 사라진다.</li>
 *   <li><b>확정된 좌석을 되돌린다.</b> {@code status} 를 보지 않으므로 SOLD 도 해제된다.</li>
 * </ul>
 *
 * <p>존재 이유는 CLAUDE.md 규칙 27 이다. 조건부 UPDATE 가 실제로 무언가를 막고 있음을
 * 보이려면 그것이 없을 때 같은 조건에서 깨지는 것을 보여야 한다.
 *
 * @see JdbcSeatReleaseRepository 올바른 구현
 */
final class NaiveSeatReleaseRepository {

    private final JdbcTemplate jdbc;

    NaiveSeatReleaseRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** 좌석 ID 만 믿고 해제한다. 홀드 소유권도 상태도 확인하지 않는다. */
    int release(List<SeatId> seatIds) {
        if (seatIds.isEmpty()) {
            return 0;
        }
        String placeholders = IntStream.range(0, seatIds.size())
                .mapToObj(i -> "?")
                .collect(Collectors.joining(", "));
        Object[] ids = seatIds.stream().map(s -> (Object) s.value()).toArray();

        return jdbc.update("""
                UPDATE seat_inventory
                   SET status='AVAILABLE', hold_id=NULL, held_by=NULL, held_at=NULL,
                       expires_at=NULL, reservation_id=NULL, version=version+1
                 WHERE id IN (%s)
                """.formatted(placeholders), ids);
    }
}
