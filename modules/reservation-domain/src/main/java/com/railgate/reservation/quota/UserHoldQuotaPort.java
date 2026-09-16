package com.railgate.reservation.quota;

import com.railgate.reservation.UserId;
import com.railgate.reservation.saleevent.SaleEventId;
import java.util.Optional;

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
 * <h2>소비자가 실제로 쓰는 연산만 노출한다</h2>
 *
 * <p>선점 경로(Task 2H-A)는 {@link #ensureRow}·{@link #tryAcquire} 를,
 * 자발적 해제 경로(Task 2H-B)는 {@link #lockRow}·{@link #release} 를 쓴다.
 * 다중 키 잠금({@code lockAll})은 아직 소비자가 없어 여기에 없다.
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

    /**
     * 카운터 행을 <b>명시적으로 잠그고</b> 현재 값을 읽는다 ({@code SELECT ... FOR UPDATE}).
     *
     * <p>감소 경로가 좌석보다 <b>먼저</b> quota 를 잠그기 위한 연산이다 (TASK-002G-B).
     * 선점 경로는 {@link #ensureRow} 가 그 잠금을 겸하지만, 감소 경로는 행을 만들면 안 되므로
     * 잠금만 따로 얻는다.
     *
     * <p><b>빈 값은 "잠금이 없다" 는 뜻이 아니라 "행이 없다" 는 뜻이다.</b> 없는 키를
     * 조회해도 InnoDB 는 다음 레코드 앞의 갭을 잠근다 (TASK-002G-G, REPEATABLE READ 에서 측정).
     * <b>행이 없어도 자동 생성하지 않는다.</b>
     *
     * @return 현재 {@code held_seats}. 행이 없으면 빈 값
     * @throws IllegalStateException 호출자의 트랜잭션에 참여하지 않은 경우
     */
    Optional<Integer> lockRow(SaleEventId saleEventId, UserId userId);

    /**
     * 카운터를 <b>실제로 변경된 좌석 수만큼</b> 줄인다 (TASK-002G-C).
     *
     * <p>{@code held_seats - seats >= 0} 조건을 UPDATE 에 두어 음수를 막는다.
     * <b>없는 행을 만들지 않고, 값을 자동 보정하지도 않는다.</b>
     *
     * @param seats 실제로 해제된 좌석 수 (1~4). <b>0 이면 호출하지 않는다</b>
     * @return {@link QuotaReleaseOutcome#REJECTED} 는 행이 없거나 음수가 될 요청이다 —
     *         정상 흐름에서는 일어나지 않으며 drift 신호다
     * @throws IllegalArgumentException {@code seats} 가 1~4 범위 밖인 경우
     * @throws IllegalStateException    호출자의 트랜잭션에 참여하지 않은 경우
     */
    QuotaReleaseOutcome release(SaleEventId saleEventId, UserId userId, int seats);
}
