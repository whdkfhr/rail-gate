package com.railgate.reservation.saleevent;

/**
 * 판매 회차의 상태.
 *
 * <pre>
 * SCHEDULED ──open──▶ OPEN ──close──▶ CLOSED
 * </pre>
 *
 * <p>이 Task(2G-E-A)가 고정하는 것은 위 두 전이뿐이다. 나머지 조합은 모두 거부된다.
 * {@code SCHEDULED → CLOSED} 도 마찬가지다 — <b>열린 적 없는 회차를 닫는다는 것이 업무적으로
 * 무엇인지 정해지지 않았기 때문이다.</b> 오픈 전 취소가 필요하다면 그것은 별도의 요구사항과
 * 상태 모델 결정을 거쳐야 한다 (TASK-002G-E-A §7 미결정 항목).
 *
 * <p><b>CLOSED 는 터미널이다.</b> 이 계약에 {@code CLOSED} 에서 나가는 전이는 없다.
 * 재오픈 정책은 요구사항에 없으며 정하지 않았다.
 */
public enum SaleEventStatus {

    /** 등록됐고 아직 오픈되지 않았다. */
    SCHEDULED,

    /** 판매 중. 선점 요청을 받을 수 있는 유일한 상태다. */
    OPEN,

    /** 종료됐다. 터미널 상태다. */
    CLOSED;

    /** 판매를 시작할 수 있는가. */
    boolean isOpenable() {
        return this == SCHEDULED;
    }

    /** 판매를 마감할 수 있는가. 열린 회차만 닫을 수 있다. */
    boolean isClosable() {
        return this == OPEN;
    }
}
