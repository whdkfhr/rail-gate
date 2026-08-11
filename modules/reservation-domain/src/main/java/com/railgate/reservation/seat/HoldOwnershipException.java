package com.railgate.reservation.seat;

/**
 * 자신이 잡지 않은 좌석을 조작하려 했다.
 *
 * <p>이 방어가 필요한 이유는 응답 유실 때문이다. 사용자 A 의 해제 요청이 성공했지만 응답이 유실되고,
 * 그 사이 사용자 B 가 같은 좌석을 선점한 뒤 A 가 재시도하면, 소유권 확인이 없을 경우
 * B 의 홀드가 파괴된다. 인프라 계층에서는 {@code WHERE hold_id = ?} 조건이 같은 역할을 한다
 * (CLAUDE.md 규칙 18).
 *
 * <p>앱 계층의 자연 멱등 해제 정책은 이 예외를 <b>성공 no-op(204)</b> 으로 매핑할 수 있다
 * (규칙 18). 이는 대상의 <b>존재 여부와 소유 여부를 외부 응답에서 구분하지 않는 정책</b>
 * 때문이지, 원하는 상태에 도달했기 때문이 아니다. 그 좌석은 여전히 다른 사용자가
 * {@code HELD} 나 {@code PAYING} 으로 잡고 있을 수 있다.
 *
 * <p>따라서 <b>204 는 좌석이 {@code AVAILABLE} 임을 증명하지 않으며 요청자의 소유임을
 * 뜻하지도 않는다.</b>
 */
public class HoldOwnershipException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public HoldOwnershipException(String message) {
        super(message);
    }
}
