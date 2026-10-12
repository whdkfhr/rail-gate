package com.railgate.reservation.application.hold.idempotency;

import java.util.Objects;

/**
 * 멱등 선점 호출의 응답 종류. HTTP 매핑은 <b>계약일 뿐 구현이 없다</b> (ADR-009 §계약).
 */
sealed interface Task2iaHoldResponse {

    /** 이 호출이 본 작업을 실행했고 결과를 기록했다. */
    record Executed(StoredHoldOutcome outcome) implements Task2iaHoldResponse {
        public Executed {
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    /** 저장된 최초 결과를 재생했다. 본 작업을 실행하지 않았다. */
    record Replayed(StoredHoldOutcome outcome) implements Task2iaHoldResponse {
        public Replayed {
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    /** 같은 키의 다른 시도가 리스를 쥐고 있다. 본 작업을 실행하지 않았다. 나중에 같은 키로 다시 보낸다. */
    record InProgress() implements Task2iaHoldResponse {
    }

    /** 같은 키·다른 요청. 실행도 재생도 하지 않는다. */
    record FingerprintMismatch() implements Task2iaHoldResponse {
    }

    /**
     * 이 시도가 실행하는 동안 리스가 만료돼 다른 시도가 인수했다. <b>이 시도의 상태 변경은 남지 않았다</b>
     * (선택 구현에서만 생긴다). 같은 키로 다시 보내면 인수자의 결과를 받는다.
     */
    record Superseded() implements Task2iaHoldResponse {
    }
}
