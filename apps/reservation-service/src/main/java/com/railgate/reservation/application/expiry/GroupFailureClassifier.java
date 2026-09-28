package com.railgate.reservation.application.expiry;

import java.sql.SQLException;
import java.sql.SQLTransactionRollbackException;
import org.springframework.dao.PessimisticLockingFailureException;

/**
 * 그룹 정산 실패를 분류한다 (TASK-002G-F 실험 E 의 계약을 운영 코드로 옮긴 것).
 *
 * <h2>cause 사슬 전체를 훑는 이유</h2>
 *
 * <p>NESTED savepoint 롤백이 MySQL 1213(deadlock)을 1305 로 덮을 수 있다 (TASK-002G-B).
 * 최상위 예외만 보면 재시도해야 할 데드락을 영구 실패로 처리한다.
 *
 * <h2>판정 순서 — 아래로 갈수록 아는 것이 적어진다</h2>
 *
 * <ol>
 *   <li>업무 실패({@link GroupSettlementException}) — 재시도 대상으로 오분류되지 않도록 먼저</li>
 *   <li>cause 사슬의 <b>vendor code</b> 1213 / 1205 — 원인을 특정한다</li>
 *   <li>{@link SQLTransactionRollbackException} — "롤백됐다" 만 안다</li>
 *   <li>{@link PessimisticLockingFailureException} — "잠금을 얻지 못했다" 만 안다</li>
 * </ol>
 */
final class GroupFailureClassifier {

    static final int MYSQL_DEADLOCK = 1213;
    static final int MYSQL_LOCK_WAIT_TIMEOUT = 1205;

    private GroupFailureClassifier() {
    }

    static GroupFailureKind classify(Throwable failure) {
        if (failure == null) {
            throw new IllegalArgumentException("failure 는 null 일 수 없다");
        }
        GroupSettlementException settlement = findCause(failure, GroupSettlementException.class);
        if (settlement != null) {
            return settlement.kind();
        }
        if (hasVendorCode(failure, MYSQL_DEADLOCK)) {
            return GroupFailureKind.DEADLOCK;
        }
        if (hasVendorCode(failure, MYSQL_LOCK_WAIT_TIMEOUT)) {
            return GroupFailureKind.LOCK_WAIT_TIMEOUT;
        }
        if (findCause(failure, SQLTransactionRollbackException.class) != null) {
            return GroupFailureKind.TRANSIENT_TRANSACTION_ROLLBACK;
        }
        if (findCause(failure, PessimisticLockingFailureException.class) != null) {
            return GroupFailureKind.TRANSIENT_LOCK_FAILURE;
        }
        return GroupFailureKind.UNKNOWN;
    }

    static boolean hasVendorCode(Throwable failure, int vendorCode) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getErrorCode() == vendorCode) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private static <T extends Throwable> T findCause(Throwable failure, Class<T> type) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return null;
    }
}
