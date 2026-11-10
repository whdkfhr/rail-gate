package com.railgate.reservation.api.security;

import com.railgate.reservation.api.trace.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;

/**
 * 보안 단계의 고정 오류 본문 {@code { code, message, traceId }} (규칙 24). 재생 대상이 아니므로 본문에 traceId 를 담는다.
 *
 * <p>값은 모두 고정 문구와 서버가 만든 16진수 traceId 뿐이라 JSON 이스케이프가 필요 없다. 예외 메시지·토큰·키는 담지 않는다.
 */
final class ErrorResponses {

    static final String UNAUTHORIZED_CODE = "AUTHENTICATION_REQUIRED";
    static final String UNAUTHORIZED_MESSAGE = "인증이 필요합니다.";
    static final String FORBIDDEN_CODE = "FORBIDDEN";
    static final String FORBIDDEN_MESSAGE = "이 요청을 수행할 권한이 없습니다.";

    private ErrorResponses() {
    }

    static void write(HttpServletRequest request, HttpServletResponse response, int status, String code, String message)
            throws IOException {
        String traceId = TraceIdFilter.currentTraceId(request);
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String body = "{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"traceId\":"
                + (traceId == null ? "null" : "\"" + traceId + "\"") + "}";
        response.getWriter().write(body);
    }
}
