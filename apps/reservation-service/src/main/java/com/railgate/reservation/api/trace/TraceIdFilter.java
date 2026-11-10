package com.railgate.reservation.api.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청마다 traceId 를 만들어 응답 헤더 {@value #TRACE_ID_HEADER}·MDC {@value #MDC_KEY}·요청 속성에 둔다
 * (규칙 24·33, HOLD_HTTP_CONTRACT D1).
 *
 * <ul>
 *   <li><b>보안 필터보다 먼저</b> 실행된다 (등록 순서는 {@code TraceConfig}). 그래서 401·403 응답에도 traceId 가 있다</li>
 *   <li><b>클라이언트가 보낸 {@value #TRACE_ID_HEADER} 를 믿지 않는다</b> — 로그 위조·추적 id 충돌을 막는다. 언제나 새로 만든다</li>
 *   <li>요청이 끝나면 MDC 에서 지운다 (예외가 나도). 스레드 풀이 재사용돼도 다음 요청으로 새지 않는다</li>
 * </ul>
 *
 * <p>Filter 빈으로 두지 않고 등록 빈 하나로만 만든다 — Filter 빈이면 Spring Boot 가 한 번 더 자동 등록한다.
 */
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";
    static final String REQUEST_ATTRIBUTE = TraceIdFilter.class.getName() + ".traceId";

    /** 이 요청의 traceId. 필터를 거치지 않은 요청이면 {@code null}. */
    public static String currentTraceId(HttpServletRequest request) {
        Object value = request.getAttribute(REQUEST_ATTRIBUTE);
        return value instanceof String traceId ? traceId : null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = newTraceId();
        request.setAttribute(REQUEST_ATTRIBUTE, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        MDC.put(MDC_KEY, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** 128비트 무작위 값의 소문자 16진수 32자. */
    static String newTraceId() {
        UUID uuid = UUID.randomUUID();
        return HexFormat.of().toHexDigits(uuid.getMostSignificantBits())
                + HexFormat.of().toHexDigits(uuid.getLeastSignificantBits());
    }
}
