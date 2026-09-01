package com.railgate.reservation.infra.saleevent;

/**
 * 좌석 목록에서 quota 범위를 하나로 정할 수 없다.
 *
 * <p>두 경우가 있다.
 * <ul>
 *   <li><b>회차가 둘 이상</b> — 서로 다른 판매 회차의 좌석이 한 요청에 섞였다.
 *       이벤트 간 quota 는 완전히 독립이므로(TASK-002G-D §4) 합산할 수 없다.</li>
 *   <li><b>회차가 없음</b> — 존재하지 않는 좌석이 섞였다.</li>
 * </ul>
 *
 * <p><b>둘 다 요청을 거부해야 하는 상황이며, 아무 회차나 고르는 대체 동작을 두지 않는다.</b>
 * 근거 없이 하나를 고르면 그 사용자의 quota 가 무관한 회차에 합산된다 —
 * TASK-002G-E-B1 §12 가 마이그레이션에서 금지한 것과 같은 종류의 오염이다.
 *
 * <p>HTTP 매핑은 여기서 정하지 않는다. 애플리케이션 계층의 후속 결정이다.
 */
public class SaleEventScopeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SaleEventScopeException(String message) {
        super(message);
    }
}
