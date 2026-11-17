package com.railgate.reservation.api.hold;

import com.railgate.reservation.idempotency.HoldRejectionCode;

/**
 * 선점 HTTP 응답의 오류 코드와 <b>고정 문구</b> (HOLD_HTTP_CONTRACT §6·§7).
 *
 * <p>문구는 예외 메시지·SQL·사용자 id·멱등키·좌석 상태 상세를 담지 않는다. <b>재생 대상</b>({@link #replayable})의 문구·상태는
 * 재생 가능한 키가 남아 있는 동안 바꾸지 않는다 — 배포 간 응답 호환 조건 (§7.3, CLAUDE.md 규칙 24).
 */
enum HoldErrorCode {

    // ---- 재생 대상 업무 실패 (본문 { code, message }, traceId 는 헤더로만)
    SEAT_UNAVAILABLE(409, "선택한 좌석 중 이미 선점된 좌석이 있습니다.", true, false, Category.NORMAL),
    QUOTA_EXCEEDED(409, "1인당 선점할 수 있는 좌석 수를 넘었습니다.", true, false, Category.NORMAL),
    SALE_EVENT_SCOPE_REJECTED(422, "선택한 좌석의 판매 회차를 하나로 확인할 수 없습니다.", true, false, Category.CLIENT_ERROR),

    // ---- 재생되지 않는 응답 (본문 { code, message, traceId })
    IDEMPOTENCY_REQUEST_IN_PROGRESS(409, "같은 요청을 처리 중입니다. 잠시 후 같은 키로 다시 시도하세요.", false, true, Category.NORMAL),
    IDEMPOTENCY_KEY_REUSED(422, "같은 멱등키로 다른 요청을 보낼 수 없습니다.", false, false, Category.CLIENT_ERROR),
    IDEMPOTENCY_KEY_INVALID(400, "Idempotency-Key 헤더가 없거나 형식이 올바르지 않습니다.", false, false, Category.CLIENT_ERROR),
    INVALID_REQUEST(400, "요청 본문이 올바르지 않습니다.", false, false, Category.CLIENT_ERROR),
    UNSUPPORTED_MEDIA_TYPE(415, "요청 본문은 application/json 이어야 합니다.", false, false, Category.CLIENT_ERROR),
    ADMISSION_EVENT_MISMATCH(403, "입장 권한의 판매 회차가 요청 좌석의 판매 회차와 다릅니다.", false, false, Category.CLIENT_ERROR),
    SERVICE_UNAVAILABLE(503, "일시적으로 요청을 처리할 수 없습니다. 잠시 후 다시 시도하세요.", false, true, Category.SERVER_ERROR),
    INTERNAL_ERROR(500, "요청을 처리하지 못했습니다.", false, false, Category.SERVER_ERROR);

    /** 서버 오류율·로그 수준의 분류 (HOLD_HTTP_CONTRACT §6.2). */
    enum Category {
        NORMAL, CLIENT_ERROR, SERVER_ERROR;

        String tag() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** {@code Retry-After} 초 값 — 재시도 권고 간격이지 완료 예상 시간이 아니다. 1초는 근거 없는 가정이다 (§6.1). */
    static final int RETRY_AFTER_SECONDS = 1;

    final int status;
    final String message;
    final boolean replayable;
    final boolean retryAfter;
    final Category category;

    HoldErrorCode(int status, String message, boolean replayable, boolean retryAfter, Category category) {
        this.status = status;
        this.message = message;
        this.replayable = replayable;
        this.retryAfter = retryAfter;
        this.category = category;
    }

    static HoldErrorCode of(HoldRejectionCode rejection) {
        return switch (rejection) {
            case SEAT_UNAVAILABLE -> SEAT_UNAVAILABLE;
            case QUOTA_EXCEEDED -> QUOTA_EXCEEDED;
            case SALE_EVENT_SCOPE_REJECTED -> SALE_EVENT_SCOPE_REJECTED;
        };
    }
}
