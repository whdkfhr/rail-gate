package com.railgate.reservation.application.hold.idempotency;

import com.railgate.reservation.UserId;
import java.util.Objects;

/**
 * 멱등키의 범위. <b>(사용자, 연산, 클라이언트 키)</b> 세 값이 모두 같아야 같은 키다.
 *
 * <ul>
 *   <li><b>사용자</b> — 서로 다른 사용자가 우연히(또는 일부러) 같은 키를 보내도 결과를 공유하지
 *       않는다. 키만으로 범위를 잡으면 남의 {@code holdId} 를 재생 응답으로 받을 수 있다.
 *       값은 <b>인증된 주체</b>에서 와야 한다. 아직 인증이 없으므로 이 실험은 명령의
 *       {@code userId} 를 그대로 쓴다 — 계약일 뿐 구현이 아니다.</li>
 *   <li><b>연산</b> — 같은 키로 선점과 해제를 보내도 서로의 결과로 재생되지 않게 한다.</li>
 *   <li><b>클라이언트 키</b> — {@link IdempotencyKey} 의 바이트 비교.</li>
 * </ul>
 */
record IdempotencyScope(UserId userId, String operation, IdempotencyKey key) {

    /** 이번 Task 의 유일한 적용 대상. */
    static final String HOLD_SEATS = "HOLD_SEATS";

    IdempotencyScope {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(key, "key");
    }

    static IdempotencyScope holdSeats(UserId userId, IdempotencyKey key) {
        return new IdempotencyScope(userId, HOLD_SEATS, key);
    }
}
