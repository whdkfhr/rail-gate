package com.railgate.reservation.application.expiry;

/**
 * 그룹 정산 실패의 원인 분류 (TASK-002G-F 실험 E).
 *
 * <p><b>재시도 여부와 원인 분류는 별개의 문제다.</b> 아래 넷은 모두 재시도하지만 이름을 섞지
 * 않는다 — 원인 미상을 {@code DEADLOCK} 으로 세면 데드락 지표가, {@code LOCK_WAIT_TIMEOUT} 으로
 * 세면 timeout 지표가 부풀려져 잘못된 곳을 의심하게 된다.
 */
public enum GroupFailureKind {

    /** MySQL 1213. 잠금 순서가 어긋났다는 신호. 피해자는 이미 롤백됐으므로 새 트랜잭션으로 재시도. */
    DEADLOCK(true),

    /** MySQL 1205. 잠금이 오래 잡혀 있었다는 신호 ({@code innodb_lock_wait_timeout = 3}). */
    LOCK_WAIT_TIMEOUT(true),

    /** vendor code 없는 {@code SQLTransactionRollbackException}. 드라이버가 "롤백됐다" 만 알렸다. */
    TRANSIENT_TRANSACTION_ROLLBACK(true),

    /** vendor code 없는 Spring {@code PessimisticLockingFailureException}. "잠금 실패" 까지만 안다. */
    TRANSIENT_LOCK_FAILURE(true),

    /** 좌석은 그 사용자 것인데 카운터 행이 없다. 재시도해도 같다 — drift 신호. */
    QUOTA_ROW_MISSING(false),

    /** 카운터가 실제 회수 수보다 작아 음수가 될 감소가 거절됐다. 재시도해도 같다. */
    QUOTA_DECREASE_REJECTED(false),

    /** 분류할 수 없는 오류. 재시도하지 않는다 — 무엇인지 모르는 것을 반복하면 배치만 느려진다. */
    UNKNOWN(false);

    private final boolean retryable;

    GroupFailureKind(boolean retryable) {
        this.retryable = retryable;
    }

    /** 일시적 실패만 참이다. 업무 실패와 원인 미상은 거짓이다. */
    public boolean isRetryable() {
        return retryable;
    }
}
