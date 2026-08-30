package com.railgate.reservation.saleevent;

/**
 * 판매 회차의 상태.
 *
 * <pre>
 * SCHEDULED ──open──▶ OPEN ──close──▶ CLOSED
 *     │                                  ▲
 *     └──────────close (회차 취소)────────┘
 * </pre>
 *
 * <p>{@code SCHEDULED → CLOSED} 를 허용하는 이유: 오픈 전에 취소되는 회차가 있다.
 * 이것을 막으면 취소하려고 일단 판매를 여는 우회를 만들게 된다.
 *
 * <p><b>CLOSED 는 터미널이다.</b> 재오픈을 허용하지 않는 이유는 quota 의 의미 때문이다.
 * I-12 의 범위가 판매 회차 단위이므로(TASK-002G-D §4), 종료된 회차의 quota 를 정리한 뒤
 * 다시 열면 "이 사용자가 이 회차에서 몇 석을 잡고 있었는가" 의 답이 달라진다.
 * 재판매가 필요하면 새 회차를 만든다.
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

    /** 판매를 마감할 수 있는가. 오픈 전 취소도 마감으로 표현한다. */
    boolean isClosable() {
        return this == SCHEDULED || this == OPEN;
    }
}
