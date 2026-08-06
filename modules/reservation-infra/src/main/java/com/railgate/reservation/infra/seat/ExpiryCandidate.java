package com.railgate.reservation.infra.seat;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.seat.SeatId;
import java.util.Objects;

/**
 * 만료 후보 조회 시점의 좌석 스냅샷.
 *
 * <p><b>이 값은 "그때 그랬다" 는 기록일 뿐 현재 상태의 진실이 아니다.</b>
 * 후보 SELECT 는 행 잠금을 잡지 않으므로, 이 값을 만든 직후부터 실제 회수 UPDATE 사이에
 * 다른 세션이 같은 행을 자유롭게 바꿀 수 있다. 확정, 해제, 만료 시각 연장, 다른 스위퍼의
 * 회수가 모두 가능하다.
 *
 * <p>그래서 {@code holdId} 를 함께 담는다. 회수 UPDATE 가 <b>이 좌석이 여전히 그때 그 홀드를
 * 잡고 있는지</b> 다시 확인하는 데 쓴다 (CLAUDE.md 규칙 4). 좌석 ID 만으로 회수하면
 * 그 사이 확정됐거나 남이 새로 잡은 좌석을 되돌리게 된다.
 */
public record ExpiryCandidate(SeatId seatId, HoldId holdId) {

    public ExpiryCandidate {
        Objects.requireNonNull(seatId, "seatId");
        Objects.requireNonNull(holdId, "holdId");
    }
}
