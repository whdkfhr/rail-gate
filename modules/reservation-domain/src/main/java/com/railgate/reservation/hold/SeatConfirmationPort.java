package com.railgate.reservation.hold;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.ReservationId;
import com.railgate.reservation.seat.SeatId;

/**
 * 단일 좌석 확정 ({@code PAYING → SOLD}).
 *
 * <p>기존 저장소의 {@code confirm(SeatId, HoldId, ReservationId)} 계약 그대로다.
 * <b>다좌석 예약 확정으로 확대하지 않는다.</b> 결제 시작({@code startPayment})은
 * 이 포트에 없다 — 확정 유스케이스가 쓰지 않으며 {@link SeatPaymentPort} 가 따로 맡는다.
 *
 * <p><b>결제 승인 여부를 검증하지 않는다.</b> 호출자가 승인을 확인한 뒤 부르는 것이 전제이며,
 * 그 전제를 DB 조건으로 강제하는 것은 I-15 의 몫이다 (미구현).
 */
public interface SeatConfirmationPort {

    /**
     * 결제 중인 좌석을 예약에 확정한다. {@code hold_id}·{@code PAYING} 조건의 조건부 UPDATE 이며
     * 만료 시각 조건은 <b>없다</b> — 이미 만료된 PAYING 좌석도 확정된다 (TASK-002B 계약).
     *
     * @return {@link SeatConfirmationOutcome#CONFIRMED} 면 {@code affected_rows = 1}.
     *         {@link SeatConfirmationOutcome#NOT_CONFIRMED} 는 정상 동작이다
     */
    SeatConfirmationOutcome confirm(SeatId seatId, HoldId holdId, ReservationId reservationId);
}
