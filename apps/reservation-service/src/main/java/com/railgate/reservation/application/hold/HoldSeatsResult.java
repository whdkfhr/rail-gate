package com.railgate.reservation.application.hold;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.saleevent.SaleEventId;

/**
 * 선점 성공 결과.
 *
 * <p><b>실패를 표현하지 않는다.</b> 이 값이 존재한다는 것 자체가 "요청 좌석 전부를
 * 선점했고 quota 도 확보했다" 는 뜻이다. 실패는 예외로 구분한다 —
 * {@link QuotaExceededException}(상한)과 {@code SeatUnavailableException}(경합)이
 * 서로 다른 타입이어야 호출부가 다른 응답을 낼 수 있다.
 *
 * @param holdId      이 선점의 식별자. 해제·확정이 이 값을 기준으로 동작한다
 * @param saleEventId <b>서버가 좌석에서 해석한</b> 판매 회차. 클라이언트 입력이 아니다
 * @param seatCount   선점된 좌석 수. 요청 좌석 수와 항상 같다 (I-9, 부분 성공이 없다)
 */
public record HoldSeatsResult(HoldId holdId, SaleEventId saleEventId, int seatCount) {
}
