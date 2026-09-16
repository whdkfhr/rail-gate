package com.railgate.reservation.application.hold;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.ReservationId;
import com.railgate.reservation.seat.SeatId;
import java.util.Objects;

/**
 * 단일 좌석 확정 요청. 기존 저장소의 {@code confirm(SeatId, HoldId, ReservationId)} 와 같은 입력이다.
 *
 * <h2>★ 여기에 없는 것</h2>
 *
 * <ul>
 *   <li><b>사용자·판매 회차</b> — 어느 quota 를 줄일지는 클라이언트가 아니라 <b>좌석 행</b>에서
 *       서버가 읽는다. 값을 받으면 남의 카운터를 줄이게 할 수 있다.</li>
 *   <li><b>결제 승인 증거</b> — 결제 승인 확인은 호출자의 전제다. 이 명령은 I-15 를 검증하지 않는다.</li>
 * </ul>
 *
 * <p>{@code holdId} 는 "지금 이 좌석을 누가 잡고 있는가" 의 유일한 답이다 (규칙 4).
 * 다른 홀드로 요청하면 조건부 UPDATE 가 0 건이 되고, 그 0 건은 남의 홀드라고 알려주지 않는다.
 */
public record ConfirmSeatCommand(SeatId seatId, HoldId holdId, ReservationId reservationId) {

    public ConfirmSeatCommand {
        Objects.requireNonNull(seatId, "seatId");
        Objects.requireNonNull(holdId, "holdId");
        Objects.requireNonNull(reservationId, "reservationId");
    }
}
