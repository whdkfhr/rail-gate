package com.railgate.reservation.application.hold;

/**
 * 해제 결과. <b>실패를 표현하지 않는다</b> — 실패는 예외다.
 *
 * <h2>★ 0 은 성공 no-op 이다</h2>
 *
 * <p>"현재 요청 조건에 맞는 해제 가능한 활성 좌석이 없다" 는 사실 하나이며,
 * 자연 멱등 계약상 성공이다 (규칙 18). <b>원인은 구분되지 않는다</b> — 이미 해제됐거나,
 * 없는 홀드거나, 다른 사용자의 홀드거나, 이미 SOLD 이거나. 구분해 노출하면 홀드 식별자로
 * 남의 상태를 탐색하는 수단이 된다.
 *
 * <h2>★ 이 값이 최종 커밋을 뜻하지 않는다</h2>
 *
 * <p>서비스는 {@code REQUIRED} 로 참여하므로, 바깥에 트랜잭션이 있으면 이 값은
 * <b>그 트랜잭션 안에서의</b> 결과다. 바깥이 롤백하면 해제도 quota 감소도 사라진다.
 * 독립 호출에서만 반환과 커밋이 같은 시점이다.
 *
 * @param releasedSeats 실제로 해제된 좌석 수. quota 도 정확히 이만큼 줄었다
 */
public record ReleaseHoldResult(int releasedSeats) {

    public ReleaseHoldResult {
        if (releasedSeats < 0) {
            throw new IllegalArgumentException("해제 수는 음수일 수 없다: " + releasedSeats);
        }
    }

    /** 활성 좌석이 없어 아무것도 하지 않았다. */
    static final ReleaseHoldResult NONE = new ReleaseHoldResult(0);

    public boolean released() {
        return releasedSeats > 0;
    }
}
