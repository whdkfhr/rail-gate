package com.railgate.reservation.api.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * 401 — 액세스 토큰이 없거나, 형식·서명·용도가 틀리거나, 만료됐다 (ADR-015 §5).
 *
 * <p>{@code WWW-Authenticate} 는 고정 {@code Bearer} 다. 기본 진입점처럼 {@code error_description} 에 예외 메시지를 싣지 않는다
 * (ADR-016 §4). 실패 사유는 응답에도 로그에도 남기지 않는다.
 */
public final class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        ErrorResponses.write(request, response, HttpServletResponse.SC_UNAUTHORIZED,
                ErrorResponses.UNAUTHORIZED_CODE, ErrorResponses.UNAUTHORIZED_MESSAGE);
    }
}
