package com.railgate.reservation.application.hold.idempotency;

import java.util.Objects;

/**
 * 멱등키 행의 스냅숏.
 *
 * @param fingerprint  최초 요청의 fingerprint
 * @param state        {@code IN_PROGRESS} 또는 {@code COMPLETED}
 * @param fence        키를 소유한 시도의 번호. 확보 시 1, 인수할 때마다 1 증가한다
 * @param leaseExpired <b>DB 가 {@code lease_until <= NOW(3)} 로 계산한 값</b>. 애플리케이션 시각으로
 *                     판단하지 않는다 (규칙 7). {@code COMPLETED} 이면 의미가 없다
 * @param outcome      {@code COMPLETED} 일 때만 존재한다
 */
record IdempotencyRecord(
        String fingerprint, State state, long fence, boolean leaseExpired, StoredHoldOutcome outcome) {

    enum State { IN_PROGRESS, COMPLETED }

    IdempotencyRecord {
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(state, "state");
        if (fence < 1) {
            throw new IllegalArgumentException("fence 는 1 이상이다: " + fence);
        }
        if ((state == State.COMPLETED) != (outcome != null)) {
            throw new IllegalArgumentException("결과는 COMPLETED 에만 있다: " + state);
        }
    }
}
