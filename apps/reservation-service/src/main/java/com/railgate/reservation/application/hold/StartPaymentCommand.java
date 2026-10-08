package com.railgate.reservation.application.hold;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.seat.SeatId;
import java.util.Objects;

/**
 * 단일 좌석 결제 시작 요청. 기존 저장소의 {@code startPayment(SeatId, HoldId)} 와 같은 입력이다.
 *
 * <h2>★ 여기에 없는 것</h2>
 *
 * <ul>
 *   <li><b>사용자·판매 회차</b> — 결제 시작은 quota 를 바꾸지 않으므로 필요 없다.
 *       받아 두면 쓰지 않는 값이 검증된 것처럼 보인다.</li>
 *   <li><b>결제 금액·수단·PG 정보</b> — 이 명령은 좌석을 결제 단계로 넘길 뿐 결제를 요청하지 않는다.</li>
 *   <li><b>결제 창 길이</b> — 서버 설정이다. 클라이언트가 정하면 만료를 임의로 늘릴 수 있다.</li>
 * </ul>
 *
 * <p>{@code holdId} 는 "지금 이 좌석을 누가 잡고 있는가" 의 유일한 답이다 (규칙 4).
 * <b>그러나 로그인 사용자 인증·인가를 대신하지 않는다.</b> 홀드 id 를 아는 사람이면 누구든
 * 결제를 시작할 수 있으므로, 요청자가 홀드 소유자인지는 인증이 붙은 API 계층이 확인해야 한다.
 */
public record StartPaymentCommand(SeatId seatId, HoldId holdId) {

    public StartPaymentCommand {
        Objects.requireNonNull(seatId, "seatId");
        Objects.requireNonNull(holdId, "holdId");
    }
}
