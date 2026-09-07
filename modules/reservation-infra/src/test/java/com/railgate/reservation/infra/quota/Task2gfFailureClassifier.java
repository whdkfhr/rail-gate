package com.railgate.reservation.infra.quota;

import java.sql.SQLException;
import java.sql.SQLTransactionRollbackException;
import org.springframework.dao.PessimisticLockingFailureException;

/**
 * <b>테스트 전용</b> 실패 분류기 (Task 2G-F 실험 E).
 *
 * <h2>왜 최상위 예외만 보면 안 되는가</h2>
 *
 * <p>2G-B 가 관측한 것 — <b>NESTED savepoint 가 MySQL 1213(deadlock) 을 1305 로 덮는다.</b>
 * savepoint 롤백이 실패하면서 원래 오류가 그 뒤에 묻힌다. 최상위 예외 타입이나 코드만 보고
 * 분류하면 <b>재시도해야 할 데드락을 영구 실패로 처리</b>하게 된다.
 *
 * <p>그래서 <b>cause 사슬 전체</b>를 훑어 vendor code 를 찾는다.
 *
 * <h2>vendor code 를 예외 클래스보다 우선한다</h2>
 *
 * <p>Spring 의 잠금 예외 계층은 deadlock 과 lock wait timeout 을 확실히 구분해 주지 않는다.
 * {@link PessimisticLockingFailureException} 하나만 보고 "데드락이다" 라고 단정하면
 * 1205(대기 시간 초과)를 1213 으로 잘못 부르게 된다. <b>두 실패의 재시도 정책이 같더라도
 * 원인 분류가 틀리면 관측값이 거짓말을 한다</b> — 경합이 심해진 것인지 잠금이 오래 잡힌 것인지
 * 구분할 수 없다.
 *
 * <p>그래서 판정 순서를 이렇게 둔다. <b>아래로 갈수록 아는 것이 적어지고, 분류도 그만큼
 * 덜 단정적이 된다.</b>
 *
 * <ol>
 *   <li>cause 사슬의 <b>vendor code</b> (1213 / 1205) — 원인을 특정할 수 있다</li>
 *   <li>JDBC 표준 타입 {@link SQLTransactionRollbackException} — 드라이버가 "이 트랜잭션은
 *       롤백됐다" 만 알렸다. <b>deadlock 인지 직렬화 실패인지 알 수 없으므로</b>
 *       {@code TRANSIENT_TRANSACTION_ROLLBACK} 으로 따로 센다</li>
 *   <li>Spring 의 {@link PessimisticLockingFailureException} — <b>잠금을 얻지 못했다는
 *       것까지만</b> 알려 준다. 1205 처럼 보유 시간이 길어서인지, 1213 처럼 순서가
 *       어긋나서인지 말해 주지 않으므로 {@code TRANSIENT_LOCK_FAILURE} 로 따로 센다</li>
 * </ol>
 *
 * <p><b>네 경우 모두 재시도한다.</b> 분류를 나누는 이유는 재시도 여부가 아니라
 * <b>관측값 때문</b>이다.
 *
 * <ul>
 *   <li>원인 미상 롤백을 {@code DEADLOCK} 으로 세면 <b>데드락 지표가 부풀려져</b>
 *       잠금 순서를 의심하게 된다</li>
 *   <li>원인 미상 잠금 실패를 {@code LOCK_WAIT_TIMEOUT} 으로 세면 <b>timeout 지표가
 *       부풀려져</b> 트랜잭션 길이나 타임아웃 설정을 의심하게 된다</li>
 * </ul>
 *
 * <p>두 경우 다 원인이 거기에 없을 수 있는데도 그렇게 읽히게 만든다.
 * <b>재시도 여부와 원인 분류는 별개의 문제다.</b>
 *
 * <h2>재시도 여부는 원인이 결정한다</h2>
 *
 * <p><b>일시적 잠금 실패만 재시도한다.</b> quota 행 부재나 감소 거부는 재시도해도 결과가 같고,
 * 오히려 문제를 감춘 채 배치를 느리게 만든다. 손상 데이터도 마찬가지다.
 */
final class Task2gfFailureClassifier {

    /** InnoDB 데드락. 피해자 트랜잭션은 이미 롤백됐으므로 새 트랜잭션으로 재시도할 수 있다. */
    static final int MYSQL_DEADLOCK = 1213;

    /** 잠금 대기 시간 초과. {@code innodb_lock_wait_timeout} 이 3초다 (규칙 9). */
    static final int MYSQL_LOCK_WAIT_TIMEOUT = 1205;

    /** savepoint 롤백 실패. 1213 을 덮을 수 있다 (2G-B). */
    static final int MYSQL_XA_ROLLBACK_FAIL = 1305;

    private Task2gfFailureClassifier() {
    }

    /**
     * 실패 원인을 분류한다.
     *
     * <p>업무 실패({@code QuotaDriftException} 계열)를 먼저 판정한다. 그 예외가 잠금 관련
     * 예외를 cause 로 갖는 일은 없지만, 순서를 명시해 두면 나중에 감싸는 계층이 생겨도
     * 업무 실패가 재시도 대상으로 오분류되지 않는다.
     */
    static Task2gfExpirySweeper.FailureKind classify(Throwable failure) {
        if (failure == null) {
            throw new IllegalArgumentException("failure 는 null 일 수 없다");
        }
        if (hasCauseOfType(failure, Task2gfExpirySweeper.QuotaRowMissingException.class)) {
            return Task2gfExpirySweeper.FailureKind.QUOTA_ROW_MISSING;
        }
        if (hasCauseOfType(failure, Task2gfExpirySweeper.QuotaDecreaseRejectedException.class)) {
            return Task2gfExpirySweeper.FailureKind.QUOTA_DECREASE_REJECTED;
        }
        if (hasCauseOfType(failure, Task2gfExpirySweeper.CorruptCandidateException.class)) {
            return Task2gfExpirySweeper.FailureKind.CORRUPT_CANDIDATE;
        }
        // (1) vendor code 가 가장 정확하다. savepoint 가 덮어도 사슬 어딘가에 남는다.
        if (hasVendorCode(failure, MYSQL_DEADLOCK)) {
            return Task2gfExpirySweeper.FailureKind.DEADLOCK;
        }
        if (hasVendorCode(failure, MYSQL_LOCK_WAIT_TIMEOUT)) {
            return Task2gfExpirySweeper.FailureKind.LOCK_WAIT_TIMEOUT;
        }
        // (2) JDBC 표준 타입. 드라이버가 "이 트랜잭션은 롤백됐다" 만 알렸다.
        //     deadlock 인지 직렬화 실패인지 알 수 없으므로 데드락으로 세지 않는다.
        if (hasCauseOfType(failure, SQLTransactionRollbackException.class)) {
            return Task2gfExpirySweeper.FailureKind.TRANSIENT_TRANSACTION_ROLLBACK;
        }
        // (3) 여기까지 오면 "잠금을 얻지 못했다" 는 사실만 안다.
        //     deadlock 도 timeout 도 아닌 별도 이름으로 센다.
        if (hasCauseOfType(failure, PessimisticLockingFailureException.class)) {
            return Task2gfExpirySweeper.FailureKind.TRANSIENT_LOCK_FAILURE;
        }
        return Task2gfExpirySweeper.FailureKind.UNKNOWN;
    }

    /**
     * 이 원인이 재시도 대상인가. <b>일시적 실패만 참이다.</b>
     *
     * <p>{@code TRANSIENT_*} 둘도 포함한다 — 원인은 특정하지 못했지만
     * <b>일시적이라는 것은 드라이버나 Spring 이 알려 준 것</b>이라 사실이다.
     * 분류를 나눈 것은 재시도 여부가 아니라 지표를 섞지 않기 위해서다.
     */
    static boolean isRetryable(Task2gfExpirySweeper.FailureKind kind) {
        return kind == Task2gfExpirySweeper.FailureKind.DEADLOCK
                || kind == Task2gfExpirySweeper.FailureKind.LOCK_WAIT_TIMEOUT
                || kind == Task2gfExpirySweeper.FailureKind.TRANSIENT_TRANSACTION_ROLLBACK
                || kind == Task2gfExpirySweeper.FailureKind.TRANSIENT_LOCK_FAILURE;
    }

    /** cause 사슬 전체에서 vendor code 를 찾는다. 최상위만 보면 1305 에 가려진다. */
    static boolean hasVendorCode(Throwable failure, int vendorCode) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException sqlException
                    && sqlException.getErrorCode() == vendorCode) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private static boolean hasCauseOfType(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
