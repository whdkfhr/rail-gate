package com.railgate.reservation.application.hold.idempotency;

import java.util.Objects;

/**
 * 멱등키 행의 스냅숏.
 *
 * @param fingerprint  최초 요청의 fingerprint
 * @param state        {@code IN_PROGRESS} 또는 {@code COMPLETED}
 * @param ownership    현재 소유권 — 행이 만들어질 때 정해진 세대와, 그 세대 안에서 인수할 때마다
 *                     1 증가하는 fence. 인수는 <b>이 스냅숏의 세대와 fence 가 모두 그대로일 때만</b> 성공한다
 * @param leaseExpired <b>DB 가 {@code lease_until <= NOW(3)} 로 계산한 값</b>. 애플리케이션 시각으로
 *                     판단하지 않는다 (규칙 7). {@code COMPLETED} 이면 의미가 없다
 * @param outcome      {@code COMPLETED} 일 때만 존재한다
 */
record IdempotencyRecord(
        String fingerprint, State state, KeyOwnership ownership, boolean leaseExpired, StoredHoldOutcome outcome) {

    enum State { IN_PROGRESS, COMPLETED }

    IdempotencyRecord {
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(ownership, "ownership");
        if ((state == State.COMPLETED) != (outcome != null)) {
            throw new IllegalArgumentException("결과는 COMPLETED 에만 있다: " + state);
        }
    }
}
