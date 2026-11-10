package com.railgate.reservation.api.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Task 2J-B — traceId 생성·헤더·MDC 전파와 요청 종료 후 정리 (서블릿 없이). */
@DisplayName("Task 2J-B — TraceIdFilter")
class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @AfterEach
    void clear() {
        MDC.clear();
    }

    @Test
    void 요청_처리_중에는_MDC_와_요청_속성과_헤더가_같은_traceId_다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();
        FilterChain chain = (req, res) -> seen.set(MDC.get(TraceIdFilter.MDC_KEY));

        filter.doFilter(request, response, chain);

        assertThat(seen.get()).matches("[0-9a-f]{32}");
        assertThat(response.getHeader(TraceIdFilter.TRACE_ID_HEADER)).isEqualTo(seen.get());
        assertThat(TraceIdFilter.currentTraceId(request)).isEqualTo(seen.get());
    }

    @Test
    void 요청이_끝나면_MDC_에서_지운다() throws Exception {
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), (req, res) -> { });
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void 처리_중_예외가_나도_MDC_에서_지운다() {
        FilterChain failing = (req, res) -> {
            throw new IllegalStateException("boom");
        };
        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), failing))
                .isInstanceOf(IllegalStateException.class);
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
    }

    /** 스레드에 남아 있던 이전 값은 덮어쓰고, 끝나면 남기지 않는다 — 다음 요청으로 새지 않는다. */
    @Test
    void 이전_요청의_값이_남아_있어도_새_값을_쓰고_끝나면_지운다() throws Exception {
        MDC.put(TraceIdFilter.MDC_KEY, "stale");
        AtomicReference<String> seen = new AtomicReference<>();
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (req, res) -> seen.set(MDC.get(TraceIdFilter.MDC_KEY)));
        assertThat(seen.get()).isNotEqualTo("stale");
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
    }

    /** 클라이언트가 보낸 X-Trace-Id 를 믿지 않는다 — 로그 위조·충돌을 막는다. */
    @Test
    void 클라이언트가_보낸_traceId_는_쓰지_않는다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.TRACE_ID_HEADER, "0123456789abcdef0123456789abcdef");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> { });
        assertThat(response.getHeader(TraceIdFilter.TRACE_ID_HEADER)).isNotEqualTo("0123456789abcdef0123456789abcdef");
    }
}
