package com.railgate.reservation.hold;

/**
 * 한 선점 요청에 포함된 좌석 수 (FR-2.3).
 *
 * <p><b>이 값 객체는 I-12 를 보장하지 않는다.</b> I-12 는 "한 사용자가 동시에 4석을 초과해
 * 보유할 수 없다" 는 규칙인데, 여기서 검증하는 것은 요청 하나의 크기일 뿐이다.
 * 사용자가 3석 요청을 동시에 두 번 보내면 두 요청 모두 유효하지만 총 6석을 점유한다.
 * I-12 는 {@code user_hold_quota} 카운터 행의 조건부 UPDATE 로 강제한다 (TASK-2).
 */
public record SeatCount(int value) {

    public static final int MIN = 1;
    public static final int MAX = 4;

    public SeatCount {
        if (value < MIN || value > MAX) {
            throw new IllegalArgumentException(
                    "한 요청의 좌석 수는 %d~%d석이어야 한다: %d".formatted(MIN, MAX, value));
        }
    }
}
