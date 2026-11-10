package com.railgate.reservation.api.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * 403 — 인증은 됐지만 이 경로에 접근할 수 없다. 지금은 공개 API 밖의 경로(기본 거부)에서만 나온다.
 * 입장 토큰의 사용자·회차 불일치 403(ADR-015 §5)은 입장 검증 구현 때 별도 {@code code} 로 낸다.
 */
public final class JsonAccessDeniedHandler implements AccessDeniedHandler {

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
            throws IOException {
        ErrorResponses.write(request, response, HttpServletResponse.SC_FORBIDDEN,
                ErrorResponses.FORBIDDEN_CODE, ErrorResponses.FORBIDDEN_MESSAGE);
    }
}
