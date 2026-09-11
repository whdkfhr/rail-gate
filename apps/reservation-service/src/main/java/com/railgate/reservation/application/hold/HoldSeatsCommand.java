package com.railgate.reservation.application.hold;

import com.railgate.reservation.UserId;
import com.railgate.reservation.hold.SeatCount;
import com.railgate.reservation.seat.SeatId;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 좌석 선점 요청.
 *
 * <h2>★ 판매 회차가 여기에 없다</h2>
 *
 * <p>클라이언트가 회차를 지정할 수 없다. 값을 받아 그대로 쓰면 사용자가 그것을 바꿔
 * <b>자기 quota 를 무관한 회차에 쌓을 수 있고</b>, 실제로 좌석을 잡는 회차의 카운터는
 * 늘지 않아 I-12 가 우회된다. 회차는 좌석으로부터 서버가 해석한다
 * ({@code SaleEventScopePort}).
 *
 * <h2>검증을 명령 객체가 하는 이유</h2>
 *
 * <p>여기서 거부되는 요청은 <b>DB 를 한 번도 건드리지 않는다.</b> 서비스 안으로 들어간 뒤
 * 검증하면 트랜잭션이 이미 열려 있고, 잘못된 입력 때문에 커넥션을 점유한다.
 * 만들 수 없는 명령은 만들어지지 않게 한다.
 */
public record HoldSeatsCommand(UserId userId, List<SeatId> seatIds) {

    public HoldSeatsCommand {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(seatIds, "seatIds");

        if (seatIds.isEmpty()) {
            throw new IllegalArgumentException("선점할 좌석이 없다");
        }
        // 좌석 수 규칙은 도메인의 값 객체가 소유한다. 여기에 숫자를 다시 쓰면 두 곳이 어긋난다.
        new SeatCount(seatIds.size());

        if (Set.copyOf(seatIds).size() != seatIds.size()) {
            // 중복을 허용하면 quota 는 중복 포함 개수만큼 늘지만 좌석은 그만큼 잡히지 않는다.
            // 저장소도 각자 거부하지만, 그 전에 거부해야 DB 를 건드리지 않는다.
            throw new IllegalArgumentException("같은 좌석이 중복해서 들어 있다: " + seatIds);
        }

        seatIds = List.copyOf(seatIds);
    }
}
