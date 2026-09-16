package com.railgate.reservation.quota;

/**
 * 조건에 맞는 활성 좌석은 있는데 <b>귀속 정보가 손상돼</b> quota 를 정할 수 없다.
 *
 * <p>대표적으로 {@code PAYING} 인데 {@code held_by} 가 {@code NULL} 인 행이다.
 * I-8 전이 규칙상 활성 좌석은 항상 점유자를 갖는다. 이 상태는 drift 검사 V-7c 가 잡는
 * 손상 데이터이며, <b>"대상 없음"(NOT_CONFIRMED)과 구분한다</b> — 대상은 있는데 누구의
 * 것인지 모르는 것이지, 대상이 없는 것이 아니다.
 *
 * <p>다른 소유자의 quota 를 추측하거나 만들지 않는다. 쓰기 전에 거절한다.
 */
public class HoldAttributionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public HoldAttributionException(String message) {
        super(message);
    }
}
