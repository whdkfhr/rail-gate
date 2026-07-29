package com.railgate.reservation.seat;

/** 사용자에게 보이는 좌석 번호. 예: {@code 12A}. */
public record SeatNo(String value) {

    private static final int MAX_LENGTH = 10;

    public SeatNo {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("좌석 번호는 비어 있을 수 없다");
        }
        value = value.strip();
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("좌석 번호는 " + MAX_LENGTH + "자를 넘을 수 없다: " + value);
        }
    }
}
