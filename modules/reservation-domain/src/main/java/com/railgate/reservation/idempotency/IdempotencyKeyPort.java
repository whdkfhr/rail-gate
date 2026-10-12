package com.railgate.reservation.idempotency;

import com.railgate.reservation.idempotency.StoredHoldOutcome.Rejected;
import com.railgate.reservation.idempotency.StoredHoldOutcome.Succeeded;
import java.time.Duration;
import java.util.Optional;

/**
 * 선점 요청의 멱등키 저장소 (ADR-009 후보 B, generation + fence).
 *
 * <h2>저장소가 보장하는 것</h2>
 *
 * <ul>
 *   <li><b>모든 변경은 조건부 UPDATE 한 문장</b>이고 성공은 {@code affected_rows} 로만 판정한다.
 *       인수·성공 기록·실패 기록·포기는 <b>키 범위 + generation + fence + 상태 조건</b>을 같은 문장에서
 *       확인한다. 앞서 SELECT 로 확인하지 않는다</li>
 *   <li>INSERT 마다 새 세대를 부여하고, 인수는 같은 세대에서 fence 만 1 올린다. 따라서 과거 세대의
 *       소유권은 재생성된 행을 바꾸지 못한다</li>
 *   <li>리스 판정과 완료 시각은 DB 의 {@code NOW(3)} 다 (규칙 7). 시각 정밀도는 밀리초다</li>
 *   <li><b>트랜잭션을 열지 않는다.</b> 변경 연산은 호출자의 트랜잭션에 참여해야 하며, 참여하지 않으면
 *       SQL 전에 거부한다. 따라서 호출자가 롤백하면 키 변경도 함께 롤백된다</li>
 * </ul>
 *
 * <h2>저장소가 하지 않는 것</h2>
 *
 * <ul>
 *   <li>fingerprint 비교·재생·진행 중 판정 — 호출자(조정자)의 몫이다. 저장소는 값을 돌려줄 뿐이다</li>
 *   <li>TX0·TX1·TX2 경계를 여는 일, 예외를 업무 실패로 분류하는 일, HTTP 응답</li>
 *   <li>리스 길이 정책 — 호출자가 매번 넘긴다. 저장소에 기본값이 없다</li>
 *   <li>보존 기간 삭제 — 없다. 진행 중 행은 삭제 대상이 아니라는 후속 계약만 있다</li>
 * </ul>
 *
 * <p><b>이 포트가 있다고 요청 경로의 I-18 이 완성된 것이 아니다.</b> 운영 조정자와 배선이 없다.
 */
public interface IdempotencyKeyPort {

    /**
     * 새 키 행을 {@code IN_PROGRESS}·새 세대·fence 1·리스 {@code NOW(3) + lease} 로 넣는다.
     *
     * <p><b>같은 범위의 행이 이미 있으면 빈 값</b>이다. 그 외의 DB 오류(잠금 대기 초과, 연결 장애,
     * 제약 위반 등)는 빈 값으로 바꾸지 않고 그대로 던진다.
     *
     * <p><b>빈 값을 받은 트랜잭션에서 계속 진행하지 말 것.</b> 중복 키 오류 뒤 같은 트랜잭션이 어떤
     * 상태인지는 DB·드라이버·트랜잭션 관리자 구성에 따라 다를 수 있고, 이 저장소는 그것을 보장하지
     * 않는다. 계약(규칙 16)은 이 트랜잭션(TX0)을 끝내고 <b>새 트랜잭션에서</b> {@link #find} 하는 것이다.
     *
     * @return 확보한 소유권. 이미 있으면 빈 값
     * @throws IllegalStateException    호출자 트랜잭션에 참여하지 않은 경우 (SQL 전)
     * @throws IllegalArgumentException 리스가 1ms 미만이거나 밀리초 단위가 아닌 경우
     */
    Optional<KeyOwnership> tryInsert(IdempotencyScope scope, RequestFingerprint fingerprint, Duration lease);

    /**
     * 키 행을 읽는다. 잠그지 않는 일반 SELECT 다.
     *
     * <p>트랜잭션 밖에서도 부를 수 있다. 트랜잭션 안이면 그 격리 수준의 스냅숏을 본다 —
     * REPEATABLE READ 에서는 그 트랜잭션의 첫 읽기 시점 값이다. 판단 뒤의 변경은 반드시
     * 읽은 {@link IdempotencyRecord#ownership()} 을 조건으로 하는 조건부 연산으로 한다.
     */
    Optional<IdempotencyRecord> find(IdempotencyScope scope);

    /**
     * 리스가 만료된 진행 중 행을 인수한다. 스냅숏의 세대·fence 가 그대로여야 한다.
     *
     * @param seen 조회한 스냅숏의 소유권
     * @return 인수한 소유권(같은 세대, fence + 1). 남이 먼저 인수했거나, 완료됐거나, 리스가 살아
     *         있거나, 행이 삭제·재생성돼 세대가 달라졌거나, 행이 없으면 빈 값
     */
    Optional<KeyOwnership> takeOver(IdempotencyScope scope, KeyOwnership seen, Duration lease);

    /**
     * 성공 결과를 기록하고 {@code COMPLETED} 로 만든다. <b>본 작업과 같은 트랜잭션(TX1)에서 부른다.</b>
     * {@link KeyWriteOutcome#NOT_OWNER} 면 호출자는 TX1 전체를 롤백해야 한다.
     */
    KeyWriteOutcome completeSucceeded(IdempotencyScope scope, KeyOwnership mine, Succeeded outcome);

    /**
     * 업무 실패를 기록하고 {@code COMPLETED} 로 만든다. <b>본 작업(TX1)이 롤백됐다는 것이 확인된 뒤에만</b>
     * 별도 트랜잭션(TX2)에서 부른다.
     */
    KeyWriteOutcome completeRejected(IdempotencyScope scope, KeyOwnership mine, Rejected outcome);

    /**
     * 리스를 즉시 만료시켜 다음 요청이 바로 인수하게 한다. 결과는 기록하지 않는다.
     *
     * <p>커밋 결과가 불명인 장애 뒤에 불러도 안전하다 — {@code state='IN_PROGRESS'} 조건 때문에
     * 이미 완료된 행은 바꾸지 않는다.
     */
    KeyWriteOutcome abandon(IdempotencyScope scope, KeyOwnership mine);
}
