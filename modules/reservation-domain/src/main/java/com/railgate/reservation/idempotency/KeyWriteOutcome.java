package com.railgate.reservation.idempotency;

/**
 * 소유권 조건부 쓰기(완료 기록·포기)의 결과. {@code affected_rows} 를 그대로 옮긴 것이다.
 *
 * <p>{@link #NOT_OWNER} 는 <b>정상 결과</b>다. 그사이 인수됐거나, 이미 완료됐거나, 행이 삭제·재생성돼
 * 세대가 바뀌었거나, 행이 없다는 뜻이며 저장소는 그중 무엇인지 구분하지 않는다. 호출자는 기록을
 * 포기하고(성공 기록이면 본 작업까지 롤백하고) 필요하면 다시 조회한다.
 */
public enum KeyWriteOutcome {
    /** {@code affected_rows = 1}. 이 소유권이 현재 소유자였고 기록이 반영됐다. */
    APPLIED,
    /** {@code affected_rows = 0}. 이 소유권은 현재 소유자가 아니다. */
    NOT_OWNER
}
