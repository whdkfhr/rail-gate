package com.railgate.reservation.application.hold.idempotency;

import java.util.Objects;

/**
 * 이미 키 행이 있을 때 무엇을 할지 정한다. <b>순수 함수</b>다 — 시각도 DB 가 계산해 넘긴다.
 *
 * <h2>판정 순서가 계약이다</h2>
 *
 * <ol>
 *   <li><b>fingerprint 불일치 → {@link #MISMATCH}</b>. 상태보다 먼저 본다. 진행 중이든 완료든,
 *       같은 키에 다른 요청을 실으면 그 요청은 실행되지도 재생되지도 않는다</li>
 *   <li>{@code COMPLETED} → {@link #REPLAY}. 저장된 최초 결과를 그대로 돌려준다</li>
 *   <li>{@code IN_PROGRESS} 이고 리스 유효 → {@link #IN_PROGRESS}. 기다렸다 다시 보내라는 응답이다</li>
 *   <li>{@code IN_PROGRESS} 이고 리스 만료 → {@link #TAKE_OVER}. <b>리스 만료는 기존 작업자의 종료를
 *       뜻하지 않는다</b> — 인수 후에도 구 작업자가 살아 있을 수 있으므로 fencing 이 필요하다</li>
 * </ol>
 */
enum IdempotencyDecision {
    REPLAY,
    MISMATCH,
    IN_PROGRESS,
    TAKE_OVER;

    static IdempotencyDecision decide(IdempotencyRecord existing, String requestFingerprint) {
        Objects.requireNonNull(existing, "existing");
        Objects.requireNonNull(requestFingerprint, "requestFingerprint");

        if (!existing.fingerprint().equals(requestFingerprint)) {
            return MISMATCH;
        }
        if (existing.state() == IdempotencyRecord.State.COMPLETED) {
            return REPLAY;
        }
        return existing.leaseExpired() ? TAKE_OVER : IN_PROGRESS;
    }
}
