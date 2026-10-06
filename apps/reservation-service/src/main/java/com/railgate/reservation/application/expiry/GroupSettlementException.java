package com.railgate.reservation.application.expiry;

import com.railgate.reservation.application.hold.QuotaInconsistencyException;
import java.util.Objects;

/**
 * 만료 그룹 정산의 업무 실패. {@link QuotaInconsistencyException} 의 한 종류이며 원인을 담는다.
 *
 * <p>분류기가 cause 사슬에서 이 예외를 찾으면 재시도하지 않는다 — 카운터가 없거나 부족한 것은
 * 다시 시도해도 같고, 배치만 느려진다. 자동 생성·보정하지 않는 이유는 선점·해제·확정 경로와 같다.
 */
public class GroupSettlementException extends QuotaInconsistencyException {

    private static final long serialVersionUID = 1L;

    private final GroupFailureKind kind;

    public GroupSettlementException(GroupFailureKind kind, String message) {
        super(message);
        this.kind = Objects.requireNonNull(kind, "kind");
        if (kind.isRetryable()) {
            throw new IllegalArgumentException("업무 실패 원인이어야 한다: " + kind);
        }
    }

    public GroupFailureKind kind() {
        return kind;
    }
}
