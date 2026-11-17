package com.railgate.reservation.api.hold;

/**
 * 선점 요청에 대한 HTTP 응답 하나. {@code body} 는 그대로 내보낼 JSON 문자열이다.
 *
 * @param code       오류 코드. 성공(201)이면 {@code null}
 * @param location   {@code Location} 헤더. 201 에만 있다
 * @param retryAfter {@code Retry-After} 초. 없으면 {@code null}
 */
record HoldHttpResponse(int status, HoldErrorCode code, String body, String location, Integer retryAfter) {

    static final String CONTENT_TYPE = "application/json;charset=UTF-8";

    HoldErrorCode.Category category() {
        return code == null ? HoldErrorCode.Category.NORMAL : code.category;
    }

    String codeTag() {
        return code == null ? "none" : code.name();
    }
}
