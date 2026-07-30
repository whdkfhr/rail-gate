package com.railgate.reservation;

import java.util.Objects;
import java.util.UUID;

/**
 * 좌석 선점(hold) 한 건의 식별자.
 *
 * <p>이 식별자가 도메인 어휘에 있어야 하는 이유는 I-11 때문이다.
 * 좌석의 확정과 해제는 "이 좌석을 지금 누가 잡고 있는가" 를 확인한 뒤에만 일어나야 한다.
 * 인프라 계층에서는 그대로 {@code WHERE hold_id = ?} 조건이 된다.
 */
public record HoldId(UUID value) {

    public HoldId {
        Objects.requireNonNull(value, "holdId 는 null 일 수 없다");
    }

    public static HoldId of(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("holdId 는 비어 있을 수 없다");
        }
        return new HoldId(UUID.fromString(raw));
    }

    public static HoldId newId() {
        return new HoldId(UUID.randomUUID());
    }

    public String asString() {
        return value.toString();
    }
}
