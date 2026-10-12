package com.railgate.reservation.application.hold.idempotency;

import java.util.Objects;
import java.util.UUID;

/**
 * 키 행에 대한 한 시도의 소유권. <b>세대(generation)와 fence 를 함께 들고 다닌다.</b>
 *
 * <ul>
 *   <li><b>generation</b> — 키 행이 INSERT 될 때마다 새로 부여한다. 인수해도 바뀌지 않는다.
 *       보존 기간이 지나 행이 삭제되고 같은 키로 다시 만들어지면 다른 값이 된다</li>
 *   <li><b>fence</b> — 같은 세대 안에서 인수할 때마다 1 씩 증가한다. 새 세대는 다시 1 부터다</li>
 * </ul>
 *
 * <p>fence 만으로는 삭제 전 행의 fence 1 과 재생성된 행의 fence 1 을 구별할 수 없다 (PR #28 리뷰 P2).
 * 그래서 인수·완료·포기는 모두 <b>키 범위 + generation + fence</b> 를 한 UPDATE 의 조건으로 확인한다.
 *
 * <p>Task 2I-A 실험용 — 운영 코드가 아니다.
 */
record KeyOwnership(String generation, long fence) {

    KeyOwnership {
        Objects.requireNonNull(generation, "generation");
        UUID.fromString(generation); // 형식 검증. 행의 CHAR(36) 열과 같은 표현이다
        if (fence < 1) {
            throw new IllegalArgumentException("fence 는 1 이상이다: " + fence);
        }
    }

    /** 새 키 행의 첫 소유권. 세대는 이 시점에 새로 만든다. */
    static KeyOwnership newGeneration() {
        return new KeyOwnership(UUID.randomUUID().toString(), 1);
    }

    /** 같은 세대에서 인수한 다음 소유권. */
    KeyOwnership next() {
        return new KeyOwnership(generation, fence + 1);
    }
}
