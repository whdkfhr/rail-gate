package com.railgate.reservation.saleevent;

import com.railgate.reservation.seat.SeatId;
import java.util.List;

/**
 * 좌석 목록이 어느 판매 회차의 quota 에 귀속되는지 해석한다.
 *
 * <h2>★ 클라이언트가 보낸 회차 값을 믿지 않는다</h2>
 *
 * <p>요청 본문에 {@code saleEventId} 를 담게 하고 그것을 그대로 쓰면, 사용자가 값을 바꿔
 * <b>자기 quota 를 무관한 회차에 쌓을 수 있다.</b> 그러면 실제로 좌석을 잡는 회차의
 * 카운터는 늘지 않아 I-12 가 우회된다. 범위는 <b>좌석으로부터 서버가 해석</b>한다.
 *
 * <h2>왜 도메인에 포트가 있는가</h2>
 *
 * <p>애플리케이션 서비스는 "좌석에서 회차를 안다" 는 능력만 필요하고, 그것이 조인 쿼리인지
 * 캐시인지는 알 필요가 없다. 구현은 {@code reservation-infra} 에 있다.
 * 이 인터페이스와 {@link SaleEventScopeException} 은 순수 Java 이며 JDBC 타입을 노출하지 않는다.
 */
public interface SaleEventScopePort {

    /**
     * 좌석들이 속한 판매 회차를 해석한다.
     *
     * @throws IllegalArgumentException 목록이 비었거나, 중복이 있거나, 상한을 넘는 경우
     * @throws SaleEventScopeException  존재하지 않는 좌석이 섞였거나 회차가 둘 이상인 경우.
     *                                  <b>아무 회차나 고르는 대체 동작은 없다.</b>
     */
    SaleEventId resolve(List<SeatId> seatIds);
}
