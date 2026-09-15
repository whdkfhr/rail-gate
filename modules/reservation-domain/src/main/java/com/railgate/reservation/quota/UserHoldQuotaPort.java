package com.railgate.reservation.quota;

import com.railgate.reservation.UserId;
import com.railgate.reservation.saleevent.SaleEventId;

/**
 * 1인당 활성 선점 좌석 수 카운터 (I-12).
 *
 * <h2>★ 호출자의 트랜잭션 안에서만 부른다</h2>
 *
 * <p>이 포트의 구현은 <b>트랜잭션을 열지 않는다.</b> 호출자가 연 트랜잭션에 참여하며,
 * 참여하지 않은 상태에서 부르면 {@link IllegalStateException} 으로 거부한다.
 *
 * <p>그래야 하는 이유는 단순하다. quota 증가가 좌석 선점과 다른 시점에 확정되면,
 * 좌석 선점이 실패했는데 카운터만 늘어난 채 남는다. 그 사용자는
 * <b>잡지도 못한 좌석 때문에 상한을 소진</b>한다.
 *
 * <h2>선점 경로가 쓰는 것만 노출한다</h2>
 *
 * <p>감소({@code release})와 다중 키 잠금({@code lockAll})은 여기에 없다.
 * 확정·해제·만료 경로가 생길 때 그 경로가 필요로 하는 포트를 따로 정의한다.
 * 쓰지 않는 연산을 미리 노출하면 "누가 무엇을 호출하는가" 가 흐려진다.
 */
public interface UserHoldQuotaPort {

    /**
     * 카운터 행을 원자적으로 확보한다. 이미 있으면 <b>값을 바꾸지 않고</b> 그대로 둔다.
     *
     * <p>{@link #tryAcquire} 는 없는 행을 만들지 않으므로 확보 경로는 이것을 먼저 부른다.
     *
     * @throws IllegalStateException 호출자의 트랜잭션에 참여하지 않은 경우
     */
    void ensureRow(SaleEventId saleEventId, UserId userId);

    /**
     * 상한 검사와 증가를 <b>한 문장으로</b> 수행한다 (CLAUDE.md 규칙 8).
     *
     * <p>집계로 검사하면 다른 트랜잭션의 미커밋 홀드가 보이지 않아, 같은 사용자의
     * 동시 요청 두 개가 모두 통과한다. 조건을 {@code WHERE} 절에 두면 읽고 판단하는
     * 구간 자체가 없어진다.
     *
     * @param seats 이 요청이 잡으려는 좌석 수 (1~4)
     * <p><b>{@link QuotaAcquireOutcome#LIMIT_EXCEEDED} 는 두 경우를 함께 뜻한다</b> —
     * 상한 초과와 카운터 행 부재다. 이 연산은 행을 만들지 않기 때문이다.
     * 따라서 <b>같은 트랜잭션에서 {@link #ensureRow} 를 먼저 호출한 호출자만</b>
     * 그 결과를 상한 초과로 해석할 수 있다. 행이 있음을 스스로 보장했기 때문이다.
     *
     * @return 확보 성공 여부. <b>상한 초과는 예외가 아니라 결과값이다</b> — 한도는
     *         이 도메인의 정상적인 업무 거절이며 오류가 아니다
     * @throws IllegalArgumentException {@code seats} 가 1~4 범위 밖인 경우
     * @throws IllegalStateException    호출자의 트랜잭션에 참여하지 않은 경우
     */
    QuotaAcquireOutcome tryAcquire(SaleEventId saleEventId, UserId userId, int seats);
}
