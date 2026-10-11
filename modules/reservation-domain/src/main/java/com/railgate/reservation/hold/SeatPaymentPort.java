package com.railgate.reservation.hold;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.seat.SeatId;

/**
 * 단일 좌석 결제 시작 ({@code HELD → PAYING}).
 *
 * <p>기존 저장소의 {@code startPayment(SeatId, HoldId)} 계약 그대로다.
 * <b>{@link SeatConfirmationPort} 와 분리한 이유</b>는 두 유스케이스가 서로의 메서드를 쓰지 않기
 * 때문이다. 확정 서비스가 결제 시작을, 결제 시작 서비스가 확정을 호출할 수 있게 열어 둘 이유가 없다.
 *
 * <p><b>결제 요청이나 승인이 아니다.</b> 좌석을 결제 단계로 넘기고 만료 시각을 결제 창만큼
 * 보장할 뿐이며 PG 를 부르지 않는다. 다좌석 결제 시작도 이 포트의 범위가 아니다.
 *
 * <p><b>quota 를 바꾸지 않는다.</b> {@code HELD} 와 {@code PAYING} 은 모두 활성 점유이므로
 * 이 전이는 I-12 의 카운터 값을 바꾸지 않는다.
 */
public interface SeatPaymentPort {

    /**
     * {@code id}·{@code hold_id}·{@code status='HELD'}·{@code expires_at > NOW(3)} 조건의
     * 조건부 UPDATE 로 결제를 시작한다. 만료 시각은
     * {@code GREATEST(기존 값, NOW(3) + 결제 창)} 이며 앞당겨지지 않는다 (P-4).
     *
     * @return {@link SeatPaymentOutcome#STARTED} 면 {@code affected_rows = 1}.
     *         {@link SeatPaymentOutcome#NOT_STARTED} 는 정상 동작이다
     */
    SeatPaymentOutcome startPayment(SeatId seatId, HoldId holdId);
}
