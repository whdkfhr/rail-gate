package com.railgate.reservation.application.hold;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.UserId;
import java.util.Objects;

/**
 * 홀드 해제 요청 (FR-2.5).
 *
 * <h2>★ 여기에 없는 것</h2>
 *
 * <ul>
 *   <li><b>좌석 목록</b> — 해제의 단위는 홀드다. 좌석을 고르게 하면 홀드 일부만 풀린
 *       어중간한 상태가 생긴다.</li>
 *   <li><b>판매 회차</b> — 선점과 같은 이유로 클라이언트 값을 믿지 않는다. 서버가 활성
 *       홀드의 좌석으로부터 해석한다.</li>
 * </ul>
 *
 * <p>{@code userId} 는 요청자다. 최종 UPDATE 가 {@code held_by} 로 소유권을 다시 검사하므로
 * 남의 홀드를 지정해도 0 건이 된다 — 그리고 그 0 건은 "남의 것" 이라고 알려주지 않는다.
 */
public record ReleaseHoldCommand(HoldId holdId, UserId userId) {

    public ReleaseHoldCommand {
        Objects.requireNonNull(holdId, "holdId");
        Objects.requireNonNull(userId, "userId");
    }
}
