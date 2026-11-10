package com.railgate.reservation.api.security;

import com.railgate.queuetoken.AdmissionClaims;
import com.railgate.queuetoken.AdmissionTokenVerifier;
import com.railgate.queuetoken.AdmissionVerification;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 입장 토큰 검증과 두 토큰의 사용자 대조 (ADR-015 §4·§5, Task 2J-C).
 *
 * <h2>적용 범위</h2>
 *
 * <ul>
 *   <li><b>경로</b> — {@value #PROTECTED_METHOD} {@value #PROTECTED_PATH} 하나. 판매 회차 입장 권한이 필요한 경로만이다.
 *       health·입장이 필요 없는 API 에는 요구하지 않는다</li>
 *   <li><b>순서</b> — SecurityFilterChain 의 인가 필터 <b>뒤</b>다. 액세스 토큰이 없거나 틀리면 이 필터에 오기 전에
 *       {@code 401 AUTHENTICATION_REQUIRED} 로 끝난다 — 액세스 오류가 입장 오류보다 먼저다</li>
 *   <li><b>디스패치</b> — REQUEST 만. 오류·비동기 디스패치는 건너뛴다 ({@link OncePerRequestFilter} 기본)</li>
 *   <li><b>등록</b> — 빈이 아니라 보안 체인에만 추가한다. 서블릿 컨테이너에 따로 등록되지 않는다</li>
 * </ul>
 *
 * <h2>판정</h2>
 *
 * <ul>
 *   <li>{@value #HEADER} 헤더 필드가 정확히 하나, 값이 비어 있지 않고 쉼표·공백이 없어야 한다. 아니면 401</li>
 *   <li>검증기가 거부(형식·서명·알고리즘·용도·claim·시각)하면 <b>401 {@code ADMISSION_TOKEN_INVALID}</b></li>
 *   <li>두 토큰이 모두 유효한데 {@code sub} 가 다르면 <b>403 {@code ADMISSION_USER_MISMATCH}</b> (I-6)</li>
 *   <li>통과하면 {@link AdmissionContext} 를 요청 속성에 둔다. 로그인 principal 은 바꾸지 않는다</li>
 * </ul>
 *
 * <p>거부 사유는 응답에 내보내지 않고 저카디널리티 메트릭 {@value #METRIC}{@code {outcome, reason}} 으로만 남긴다.
 * 토큰 원문·사용자 id 는 응답·로그·메트릭 어디에도 두지 않는다. 검증은 네트워크 없이 끝난다 (queue-service·Redis 미호출).
 */
public final class AdmissionTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "Admission-Token";
    static final String PROTECTED_METHOD = "POST";
    static final String PROTECTED_PATH = "/api/reservations/holds";
    static final String METRIC = "railgate.auth.admission";

    private final AdmissionTokenVerifier verifier;
    private final MeterRegistry meters;

    public AdmissionTokenFilter(AdmissionTokenVerifier verifier, MeterRegistry meters) {
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.meters = Objects.requireNonNull(meters, "meters");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !(PROTECTED_METHOD.equals(request.getMethod()) && PROTECTED_PATH.equals(path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof AccessTokenAuthentication access)) {
            // 인가 필터 뒤라 오지 않는 경로다. 오더라도 통과시키지 않는다 (fail closed).
            new JsonAuthenticationEntryPoint().commence(request, response, null);
            return;
        }

        List<String> values = Collections.list(request.getHeaders(HEADER));
        if (values.isEmpty()) {
            rejectInvalid(request, response, "missing");
            return;
        }
        if (values.size() != 1 || !isSingleToken(values.getFirst())) {
            rejectInvalid(request, response, "header");
            return;
        }

        AdmissionVerification verification = verifier.verify(values.getFirst());
        if (verification instanceof AdmissionVerification.Rejected rejected) {
            rejectInvalid(request, response, rejected.reason().name().toLowerCase(Locale.ROOT));
            return;
        }
        AdmissionClaims claims = ((AdmissionVerification.Accepted) verification).claims();
        if (claims.userId() != access.getPrincipal().userId().value()) {
            count("user_mismatch", "none");
            ErrorResponses.write(request, response, HttpServletResponse.SC_FORBIDDEN,
                    ErrorResponses.ADMISSION_USER_MISMATCH_CODE, ErrorResponses.ADMISSION_USER_MISMATCH_MESSAGE);
            return;
        }

        request.setAttribute(AdmissionContext.REQUEST_ATTRIBUTE,
                new AdmissionContext(claims.userId(), claims.saleEventId()));
        count("accepted", "none");
        chain.doFilter(request, response);
    }

    /** 비어 있지 않고, 쉼표(헤더 결합)·공백이 없는 값 하나. */
    private static boolean isSingleToken(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ',' || Character.isWhitespace(c)) {
                return false;
            }
        }
        return true;
    }

    private void rejectInvalid(HttpServletRequest request, HttpServletResponse response, String reason)
            throws IOException {
        count("rejected", reason);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Admission-Token");
        ErrorResponses.write(request, response, HttpServletResponse.SC_UNAUTHORIZED,
                ErrorResponses.ADMISSION_TOKEN_INVALID_CODE, ErrorResponses.ADMISSION_TOKEN_INVALID_MESSAGE);
    }

    private void count(String outcome, String reason) {
        meters.counter(METRIC, "outcome", outcome, "reason", reason).increment();
    }
}
