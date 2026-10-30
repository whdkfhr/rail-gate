package com.railgate.reservation.application.idempotency;

import com.railgate.reservation.idempotency.StoredHoldOutcome;
import java.util.Objects;

/**
 * 멱등 선점 호출의 결과 종류. <b>HTTP 상태 코드를 담지 않는다</b> — 매핑은 HTTP 계층의 몫이다.
 * 매핑(ADR-009 C3 채택)은 {@code docs/HOLD_HTTP_CONTRACT.md} §6 에 설계만 있고 HTTP 구현은 아직 없다.
 *
 * <p>{@link Executed} 와 {@link Replayed} 는 <b>같은 저장된 업무 결과</b>({@link StoredHoldOutcome})를 담는다.
 * 실행한 호출과 재생한 호출이 클라이언트에게 같은 본문을 내도록 하기 위해서다 (규칙 17).
 */
public sealed interface IdempotentHoldResult {

    /** 이 호출이 본 작업을 실행했고 그 결과가 커밋됐다 (성공 또는 업무 실패). */
    record Executed(StoredHoldOutcome outcome) implements IdempotentHoldResult {
        public Executed {
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    /** 저장된 최초 결과를 재생했다. 본 작업을 실행하지 않았다. <b>재생은 현재 상태가 아니다.</b> */
    record Replayed(StoredHoldOutcome outcome) implements IdempotentHoldResult {
        public Replayed {
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    /** 같은 키의 다른 시도가 유효한 리스를 쥐고 있다(또는 경쟁이 계속돼 판정을 미뤘다). 실행하지 않았다. */
    record InProgress() implements IdempotentHoldResult {
    }

    /** 같은 키에 다른 요청(fingerprint 불일치)이 실렸다. 실행도 재생도 하지 않았다. */
    record FingerprintMismatch() implements IdempotentHoldResult {
    }

    /**
     * 이 시도가 실행하는 동안 소유권(세대·fence)을 잃었다. 본 작업의 상태 변경은 <b>남지 않았다</b> —
     * 성공 기록이 거절되면 TX1 전체가 롤백된다. 같은 키로 다시 보내면 현재 소유자의 결과를 받는다.
     */
    record OwnershipLost() implements IdempotentHoldResult {
    }
}
