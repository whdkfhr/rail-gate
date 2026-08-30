package com.railgate.reservation.saleevent;

import java.time.Instant;

/**
 * 판매 회차 값의 유효성 규칙. 술어(predicate)만 두고 예외는 던지지 않는다.
 *
 * <p><b>예외 타입을 여기서 정하지 않는 이유.</b> 같은 규칙이라도 위반의 의미가 다르다.
 * {@link SaleEvent#schedule} 은 <b>잘못된 입력</b>이므로 {@code IllegalArgumentException},
 * {@link SaleEventSnapshot} 은 <b>저장소의 손상된 행</b>이므로
 * {@link SaleEventRestoreException} 이다. 규칙을 한 곳에 두면서 대응은 호출부가 정하도록
 * 술어와 예외를 분리했다. 규칙이 바뀌면 고칠 곳은 여기 하나다.
 */
final class SaleEventRules {

    private SaleEventRules() {
    }

    /** 회차 이름이 있는가. 공백만 있는 이름은 없는 것으로 본다. */
    static boolean hasName(String name) {
        return name != null && !name.isBlank();
    }

    /**
     * 판매 기간이 유효한가.
     *
     * <p>마감 시각은 없어도 된다(무기한 판매). 있다면 <b>오픈 시각보다 뒤여야</b> 한다.
     * 같은 순간이면 열자마자 닫히는 회차가 되므로 거부한다.
     */
    static boolean isValidSalePeriod(Instant opensAt, Instant closesAt) {
        return closesAt == null || closesAt.isAfter(opensAt);
    }
}
