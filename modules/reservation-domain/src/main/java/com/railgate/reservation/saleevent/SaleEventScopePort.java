package com.railgate.reservation.saleevent;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.seat.SeatId;
import java.util.List;
import java.util.Optional;

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

    /**
     * <b>활성 홀드</b>가 속한 판매 회차를 해석한다 (자발적 해제 경로, Task 2H-B).
     *
     * <p>해제 요청은 좌석 목록을 담지 않는다 — 단위가 홀드이기 때문이다. 그래서 회차도
     * {@code holdId}·{@code userId} 에 맞는 <b>활성 좌석</b>({@code HELD}/{@code PAYING})으로부터
     * 서버가 해석한다. 클라이언트 값을 받지 않는 이유는 {@link #resolve} 와 같다.
     *
     * <p><b>잠금 없는 조회다.</b> 좌석을 먼저 잠그면 quota → seat 잠금 순서(TASK-002G-B)가
     * 뒤집힌다. 결과는 <b>스냅숏</b>이며, 최종 해제 가능 여부는 조건부 UPDATE 가 다시 판단한다.
     *
     * @return 활성 좌석이 하나도 없으면 빈 값. <b>그 원인은 구분되지 않는다</b> —
     *         이미 해제됐거나, 없는 홀드거나, 다른 사용자의 홀드거나, 이미 SOLD 이거나
     * @throws SaleEventScopeException 활성 좌석이 <b>둘 이상의 회차</b>에 걸쳐 있는 경우.
     *                                  비정상 데이터이며 임의 회차를 고르지 않고 쓰기 전에 거절한다
     */
    Optional<SaleEventId> resolveActiveHold(HoldId holdId, UserId userId);
}
