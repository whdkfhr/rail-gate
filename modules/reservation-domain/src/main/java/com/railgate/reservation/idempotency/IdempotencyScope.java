package com.railgate.reservation.idempotency;

import com.railgate.reservation.UserId;
import java.util.Objects;

/**
 * 멱등키의 범위 — <b>(사용자, 연산, 클라이언트 키)</b>. 저장 행의 PK 와 같다.
 *
 * <p>사용자는 <b>인증된 주체</b>에서 와야 한다. 인증은 아직 없으므로 이 타입은 값만 담는다 —
 * 호출자가 요청 본문의 사용자 id 를 넣으면 남의 키 범위를 쓸 수 있다는 한계가 남는다.
 */
public record IdempotencyScope(UserId userId, IdempotentOperation operation, IdempotencyKey key) {

    public IdempotencyScope {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(key, "key");
    }

    public static IdempotencyScope holdSeats(UserId userId, IdempotencyKey key) {
        return new IdempotencyScope(userId, IdempotentOperation.HOLD_SEATS, key);
    }
}
