package com.railgate.reservation.api.hold;

import com.railgate.reservation.application.hold.AdmissionEventMismatchException;
import com.railgate.reservation.application.idempotency.IdempotentHoldResult;
import com.railgate.reservation.idempotency.StoredHoldOutcome;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * 조정자 결과·오류 → HTTP 응답 (HOLD_HTTP_CONTRACT §6·§7).
 *
 * <h2>재생 대상 응답은 저장 결과에서만 만든다 (D1)</h2>
 *
 * <p>201 의 본문·{@code Location} 은 저장된 성공 결과({@code holdId}·{@code saleEventId}·{@code seatCount})에서, 저장된 업무 실패의
 * 본문은 코드의 고정 문구에서 <b>결정적으로</b> 만든다. 현재 좌석을 다시 읽거나 {@code expiresAt} 을 넣지 않고(D2), 재생 대상
 * 본문에는 {@code traceId} 를 넣지 않는다 — 그래서 실행과 재생의 상태·본문 바이트·{@code Location}·{@code Content-Type} 이 같다.
 * 현재 요청의 traceId 는 언제나 {@code X-Trace-Id} 헤더로 나간다 ({@code TraceIdFilter}).
 *
 * <p>JSON 은 직접 쓴다. 값이 고정 문구·UUID·양의 정수뿐이라 이스케이프가 필요 없고, 직렬화 라이브러리 설정이 바뀌어도 바이트가
 * 바뀌지 않는다 (§7.3 배포 간 응답 호환 조건).
 *
 * <h2>장애 분류</h2>
 *
 * <ul>
 *   <li>{@link AdmissionEventMismatchException} (D4) → 403. 서버 장애가 아니다</li>
 *   <li>재시도 가능한 장애 — {@link TransientDataAccessException}(잠금 대기 초과·데드락·질의 시간 초과 포함),
 *       {@link PessimisticLockingFailureException}, 연결 실패({@link CannotGetJdbcConnectionException},
 *       트랜잭션 시작 시의 {@link CannotCreateTransactionException}) → 503 + {@code Retry-After}</li>
 *   <li>그 밖의 예외(커밋 결과 불명 포함) → 500</li>
 * </ul>
 */
final class HoldHttpResponses {

    static final String LOCATION_PREFIX = "/api/reservations/holds/";

    private HoldHttpResponses() {
    }

    static HoldHttpResponse from(IdempotentHoldResult result, String traceId) {
        return switch (result) {
            case IdempotentHoldResult.Executed executed -> stored(executed.outcome());
            case IdempotentHoldResult.Replayed replayed -> stored(replayed.outcome());
            case IdempotentHoldResult.InProgress inProgress -> error(HoldErrorCode.IDEMPOTENCY_REQUEST_IN_PROGRESS, traceId);
            case IdempotentHoldResult.OwnershipLost lost -> error(HoldErrorCode.IDEMPOTENCY_REQUEST_IN_PROGRESS, traceId);
            case IdempotentHoldResult.FingerprintMismatch mismatch -> error(HoldErrorCode.IDEMPOTENCY_KEY_REUSED, traceId);
        };
    }

    /**
     * <b>현재 요청을 거절하는 응답</b> — 저장되지 않으므로 재생 대상이 아니다. 규칙 24 대로 본문에 현재 traceId 를 담는다.
     * 코드가 재생 대상 업무 실패와 같아도(사전 조회의 422) 이 형식이다 — 형식은 코드가 아니라 <b>응답의 출처</b>로 정한다.
     */
    static HoldHttpResponse error(HoldErrorCode code, String traceId) {
        String body = "{\"code\":\"" + code.name() + "\",\"message\":\"" + code.message + "\",\"traceId\":"
                + (traceId == null ? "null" : "\"" + traceId + "\"") + "}";
        return new HoldHttpResponse(code.status, code, body, null,
                code.retryAfter ? HoldErrorCode.RETRY_AFTER_SECONDS : null);
    }

    /** <b>저장된 업무 실패</b>에서 만드는 응답 — 실행·재생이 같은 바이트여야 하므로 본문은 {code, message} 뿐이다 (D1, 규칙 24 예외). */
    private static HoldHttpResponse storedRejection(HoldErrorCode code) {
        String body = "{\"code\":\"" + code.name() + "\",\"message\":\"" + code.message + "\"}";
        return new HoldHttpResponse(code.status, code, body, null, null);
    }

    static HoldHttpResponse failure(RuntimeException failure, String traceId) {
        if (failure instanceof AdmissionEventMismatchException) {
            return error(HoldErrorCode.ADMISSION_EVENT_MISMATCH, traceId);
        }
        return error(isRetryable(failure) ? HoldErrorCode.SERVICE_UNAVAILABLE : HoldErrorCode.INTERNAL_ERROR, traceId);
    }

    static boolean isRetryable(RuntimeException failure) {
        return failure instanceof TransientDataAccessException
                || failure instanceof PessimisticLockingFailureException
                || failure instanceof CannotGetJdbcConnectionException
                || failure instanceof CannotCreateTransactionException;
    }

    private static HoldHttpResponse stored(StoredHoldOutcome outcome) {
        return switch (outcome) {
            case StoredHoldOutcome.Succeeded succeeded -> {
                String holdId = succeeded.holdId().asString();
                String body = "{\"holdId\":\"" + holdId + "\",\"saleEventId\":" + succeeded.saleEventId().value()
                        + ",\"seatCount\":" + succeeded.seatCount() + "}";
                yield new HoldHttpResponse(201, null, body, LOCATION_PREFIX + holdId, null);
            }
            case StoredHoldOutcome.Rejected rejected -> storedRejection(HoldErrorCode.of(rejected.code()));
        };
    }
}
