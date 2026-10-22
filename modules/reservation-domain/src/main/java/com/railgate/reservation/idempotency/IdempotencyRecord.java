package com.railgate.reservation.idempotency;

import java.util.Objects;
import java.util.Optional;

/**
 * 키 행의 스냅숏. <b>읽은 시점의 값일 뿐</b> 이후 변경을 막지 않는다 — 판단 뒤의 변경은 반드시
 * {@link #ownership()} 을 조건으로 쓰는 조건부 연산으로 한다.
 *
 * @param fingerprint  최초 요청의 fingerprint
 * @param state        상태
 * @param ownership    현재 소유권 (세대·fence)
 * @param leaseExpired <b>DB 가 {@code lease_until <= NOW(3)} 로 계산한 값</b> (규칙 7).
 *                     {@code COMPLETED} 이면 언제나 {@code false} 다 — 리스가 없다
 * @param outcome      {@code COMPLETED} 일 때만 있다
 */
public record IdempotencyRecord(
        RequestFingerprint fingerprint,
        IdempotencyKeyState state,
        KeyOwnership ownership,
        boolean leaseExpired,
        Optional<StoredHoldOutcome> outcome) {

    public IdempotencyRecord {
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(ownership, "ownership");
        Objects.requireNonNull(outcome, "outcome");
        if ((state == IdempotencyKeyState.COMPLETED) != outcome.isPresent()) {
            throw new IllegalArgumentException("결과는 COMPLETED 에만 있다: " + state);
        }
        if (state == IdempotencyKeyState.COMPLETED && leaseExpired) {
            throw new IllegalArgumentException("COMPLETED 에는 리스가 없다");
        }
    }
}
