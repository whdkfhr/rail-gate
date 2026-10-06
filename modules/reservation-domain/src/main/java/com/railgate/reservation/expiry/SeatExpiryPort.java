package com.railgate.reservation.expiry;

import java.util.List;
import java.util.Optional;

/**
 * 만료 좌석의 후보 조회와 회수 (I-10 / I-11).
 *
 * <h2>후보 조회와 회수를 나눈 이유</h2>
 *
 * <p>만료 대상을 찾는 기준({@code expires_at})과 잠금 순서의 기준(PK)이 다르다.
 * 후보 인덱스 순서로 곧장 잠그면 확정 경로(PK 순서)와 교차해 데드락이 난다 (규칙 2).
 * 후보를 읽고, PK 오름차순으로 정렬한 뒤, 단일 벌크 UPDATE 로 회수한다.
 *
 * <h2>실패 후보를 지나 진행한다</h2>
 *
 * <p>회수되지 않는 후보(손상·quota 부재)는 후보 집합에서 사라지지 않으므로 정렬 앞자리를
 * <b>영구히 차지한다.</b> 첫 페이지만 보면 그 뒤의 정상 좌석에 끝내 도달하지 못한다.
 * 그래서 조회는 {@link ExpiryCursor} 로 <b>페이지를 넘길 수 있게</b> 되어 있다.
 *
 * <p>그 대가로 조회와 회수 사이에 창이 열린다. <b>후보는 스냅숏이고 회수 UPDATE 가
 * {@code (id, hold_id)} 쌍·활성 상태·만료 시각을 다시 검사한다.</b> 후보 수와 회수 수가
 * 다른 것은 오류가 아니다 (I-11: 그 사이 확정된 좌석은 회수되지 않는다).
 */
public interface SeatExpiryPort {

    /**
     * 만료 후보 <b>한 페이지</b>를 DB 시각({@code NOW(3)}) 기준으로 찾는다. 잠금을 잡지 않는다.
     *
     * <p>순서는 {@code expires_at, id} 오름차순(공정성)이며 {@code pageSize} 로 자른다.
     * <b>손상 후보({@code hold_id}·{@code held_by} 없음)도 포함</b>해 돌려준다 —
     * 호출자가 분리 보고한다.
     *
     * <p>이름이 저장소의 기존 {@code findExpiredCandidates}(좌석·홀드만, 커서 없음)와 다른
     * 이유는 그 계약을 그대로 두기 위해서다. 이 조회는 귀속 정보와 정렬 키를 더 읽고
     * 손상 행을 숨기지 않는다.
     *
     * @param after 이 커서 <b>뒤</b>부터 읽는다. 비어 있으면 처음부터.
     *              {@code OFFSET} 이 아닌 이유는 {@link ExpiryCursor} 에 있다
     * @throws IllegalArgumentException {@code pageSize} 가 0 이하
     */
    List<ExpiredSeat> findExpiredSeats(int pageSize, Optional<ExpiryCursor> after);

    /**
     * 후보를 회수한다. 각 후보의 {@code (id, hold_id)}·활성 상태·만료 시각을 <b>다시</b> 검사한다.
     *
     * <p>호출자가 연 트랜잭션에 참여한다. 같은 트랜잭션에서 quota 감소를 함께 하면 둘이
     * 같은 시점에 확정된다.
     *
     * @return 실제로 회수된 행 수. 후보 수보다 적을 수 있다
     */
    int expire(List<ExpiryCandidate> candidates);
}
