package com.railgate.reservation.application.idempotency;

import com.railgate.reservation.idempotency.IdempotencyKeyState;
import com.railgate.reservation.idempotency.IdempotencyRecord;
import com.railgate.reservation.idempotency.RequestFingerprint;
import java.util.Objects;

/**
 * 이미 키 행이 있을 때 무엇을 할지. <b>순수 함수</b>다 — 리스 만료 여부도 DB 가 계산해 넘긴 값이다 (규칙 7).
 *
 * <h2>판정 순서가 계약이다 (IDEMPOTENCY_CONTRACT.md §3)</h2>
 *
 * <ol>
 *   <li><b>불일치</b> — 상태보다 먼저 본다. 같은 키에 다른 요청을 실으면 실행도 재생도 인수도 하지 않는다</li>
 *   <li><b>재생</b> — 완료된 행</li>
 *   <li><b>진행 중</b> — 리스가 살아 있는 행</li>
 *   <li><b>인수</b> — 리스가 만료된 행. 리스 만료는 구 작업자의 종료를 뜻하지 않으므로 이후의 모든 기록은
 *       인수한 소유권(세대·fence)을 조건으로 한다</li>
 * </ol>
 */
enum HoldIdempotencyDecision {
    MISMATCH,
    REPLAY,
    IN_PROGRESS,
    TAKE_OVER;

    static HoldIdempotencyDecision decide(IdempotencyRecord existing, RequestFingerprint request) {
        Objects.requireNonNull(existing, "existing");
        Objects.requireNonNull(request, "request");
        if (!existing.fingerprint().equals(request)) {
            return MISMATCH;
        }
        if (existing.state() == IdempotencyKeyState.COMPLETED) {
            return REPLAY;
        }
        return existing.leaseExpired() ? TAKE_OVER : IN_PROGRESS;
    }
}
