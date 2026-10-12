package com.railgate.reservation.idempotency;

import java.util.Objects;
import java.util.UUID;

/**
 * 키 행에 대한 한 시도의 소유권 — <b>세대(generation)와 fence</b>.
 *
 * <ul>
 *   <li><b>generation</b> — 키 행을 INSERT 할 때마다 새로 정한다. 인수해도 바뀌지 않는다.
 *       보존 기간 뒤 행이 삭제되고 같은 키로 다시 만들어지면 다른 값이 된다</li>
 *   <li><b>fence</b> — 같은 세대 안에서 인수할 때마다 1 증가한다. 새 세대는 다시 1 부터다</li>
 * </ul>
 *
 * <p>fence 만으로는 삭제 전 행의 fence 1 과 재생성된 행의 fence 1 을 구별할 수 없다
 * (TASK-002I-A §4-1 RED-4). 그래서 인수·완료·포기는 모두 이 두 값을 함께 조건으로 쓴다.
 * 세대 값의 충돌 확률은 무시할 수 있다고 <b>가정</b>한다 (UUID v4).
 */
public record KeyOwnership(UUID generation, long fence) {

    public KeyOwnership {
        Objects.requireNonNull(generation, "generation");
        if (fence < 1) {
            throw new IllegalArgumentException("fence 는 1 이상이다: " + fence);
        }
    }

    /** 새 키 행의 첫 소유권. */
    public static KeyOwnership newGeneration() {
        return new KeyOwnership(UUID.randomUUID(), 1);
    }

    /** 같은 세대에서 인수한 다음 소유권. */
    public KeyOwnership next() {
        return new KeyOwnership(generation, fence + 1);
    }
}
