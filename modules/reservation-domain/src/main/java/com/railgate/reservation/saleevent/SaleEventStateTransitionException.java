package com.railgate.reservation.saleevent;

/**
 * 현재 상태 또는 시간 조건에서 수행할 수 없는 판매 회차 행위를 시도했다.
 *
 * <p>이 예외가 뜻하는 것은 그것뿐이다. <b>원인을 단정하지 않는다.</b>
 * 같은 예외가 다음을 모두 표현한다.
 * <ul>
 *   <li>오픈 예정 시각 이전의 {@code open(now)} — 정상적인 거부일 수 있다</li>
 *   <li>이미 OPEN 인 회차를 다시 여는 요청 — 재시도나 멱등 요청일 수 있다</li>
 *   <li>이미 CLOSED 인 회차를 다시 닫는 요청 — 위와 같다</li>
 *   <li>전이표에 없는 관리 명령 — 버그일 수 있다</li>
 * </ul>
 *
 * <p><b>HTTP 상태와 알람 정책을 여기서 정하지 않는다.</b> 위 목록이 보여주듯 같은 예외가
 * 정상 거부와 버그를 모두 담고 있어서, 매핑은 <b>요청 맥락과 멱등성 정책을 아는 계층</b>이
 * 결정해야 한다. 애플리케이션 서비스와 REST API 는 아직 없다 (후속 Task 2H).
 */
public class SaleEventStateTransitionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SaleEventStateTransitionException(String message) {
        super(message);
    }

    static SaleEventStateTransitionException of(SaleEventId id, SaleEventStatus from, String action) {
        return new SaleEventStateTransitionException(
                "판매 회차 %d 는 %s 상태에서 %s 할 수 없다".formatted(id.value(), from, action));
    }
}
