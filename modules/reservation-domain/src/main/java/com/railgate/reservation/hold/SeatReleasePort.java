package com.railgate.reservation.hold;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.UserId;

/**
 * 홀드 단위 자발적 해제 (FR-2.5).
 *
 * <h2>해제의 단위는 좌석이 아니라 홀드다</h2>
 *
 * <p>사용자는 "내가 잡은 것을 놓겠다" 고 요청하며 그 홀드의 좌석이 함께 풀린다.
 * 그래서 이 포트는 좌석 목록을 받지 않는다. 좌석 단위로 노출하면 홀드 일부만 풀린
 * 어중간한 상태가 생긴다.
 *
 * <h2>전부-또는-전무가 아니다</h2>
 *
 * <p>다좌석 선점(I-9)과 반대다. 후보 하나가 stale 해도 나머지는 해제한다.
 * <b>반환값이 실제로 바뀐 행 수</b>이므로 quota 감소는 그 값으로 해야 한다 (TASK-002G-C) —
 * 요청 수나 후보 수로 줄이면 stale 만큼 카운터가 어긋난다.
 */
public interface SeatReleasePort {

    /**
     * 홀드에 묶인 좌석을 찾아 해제한다.
     *
     * <p>호출자가 연 트랜잭션이 있으면 그 트랜잭션에 참여한다. 그래서 같은 트랜잭션에서
     * quota 감소를 함께 하면 둘이 같은 시점에 확정된다.
     *
     * @return 실제로 해제된 좌석 수. <b>0 은 "지금 조건에 맞는 해제 가능한 활성 좌석이 없다"</b>
     *         는 뜻이며 자연 멱등 계약상 성공 no-op 이다 (규칙 18). 원인은 구분되지 않는다 —
     *         이미 해제됐거나, 없는 홀드거나, 다른 사용자의 홀드거나, 이미 SOLD 이거나.
     *         <b>0 이 좌석이 AVAILABLE 임을 증명하지도, 요청자가 소유자임을 뜻하지도 않는다.</b>
     */
    int releaseAll(HoldId holdId, UserId userId);
}
