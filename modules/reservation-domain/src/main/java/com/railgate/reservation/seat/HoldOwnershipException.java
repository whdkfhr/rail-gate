package com.railgate.reservation.seat;

/**
 * 자신이 잡지 않은 좌석을 조작하려 했다.
 *
 * <p>이 방어가 필요한 이유는 응답 유실 때문이다. 사용자 A 의 해제 요청이 성공했지만 응답이 유실되고,
 * 그 사이 사용자 B 가 같은 좌석을 선점한 뒤 A 가 재시도하면, 소유권 확인이 없을 경우
 * B 의 홀드가 파괴된다. 인프라 계층에서는 {@code WHERE hold_id = ?} 조건이 같은 역할을 한다
 * (CLAUDE.md 규칙 18).
 *
 * <p>앱 계층은 해제 경로에서 이 예외를 204 로 흡수한다. 이미 원하는 상태이기 때문이다.
 */
public class HoldOwnershipException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public HoldOwnershipException(String message) {
        super(message);
    }
}
