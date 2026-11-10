package com.railgate.reservation.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.railgate.reservation.api.trace.TraceIdFilter;
import com.railgate.reservation.support.MutableClock;
import com.railgate.reservation.support.SecuredWebTestSupport;
import com.railgate.reservation.support.TestAccessTokens;
import com.railgate.testprobe.AuthProbeController;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

/**
 * ★ Task 2J-B — <b>실제 SecurityFilterChain</b>(내장 서버 + 실제 필터 등록)으로 액세스 토큰 검증을 확인한다 (ADR-015 §3).
 *
 * <p>확인용 컨트롤러와 시계는 이 테스트의 {@link TestConfiguration} 에만 있다 — 운영 경로가 아니다.
 * 컨텍스트 구성은 {@link SecuredWebTestSupport} 와 공유한다 (같은 구성 = 같은 컨텍스트).
 * 시간 경계는 {@link MutableClock} 으로 옮기고 {@code Thread.sleep} 을 쓰지 않는다.
 */
@DisplayName("Task 2J-B — 액세스 토큰 검증 (SecurityFilterChain)")
class AccessTokenAuthenticationTest extends SecuredWebTestSupport {

    private static final long USER = 4242L;
    private static final String PROBE = AuthProbeController.PATH;
    private static final String FIXED_401_MESSAGE = "인증이 필요합니다.";

    @LocalServerPort
    private int port;

    @Autowired
    private MutableClock clock;

    @Autowired
    private ApplicationContext context;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void resetClock() {
        clock.set(NOW);
    }

    // ------------------------------------------------------------------ 픽스처

    private HttpResponse<String> get(String path, String bearer) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> probe(String token) throws IOException, InterruptedException {
        return get(PROBE, token);
    }

    private static String token(Consumer<TestAccessTokens.Spec> customizer) {
        return TestAccessTokens.access(NOW, USER, customizer);
    }

    private static Map<String, Object> json(HttpResponse<String> response) {
        return JsonParserFactory.getJsonParser().parseMap(response.body());
    }

    /** 401 의 고정 형식 — 헤더·본문·traceId 일치, 토큰·예외 메시지 미노출. */
    private static void assertUnauthorized(HttpResponse<String> response, String sentToken) {
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().allValues("WWW-Authenticate")).containsExactly("Bearer");
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                ct -> assertThat(ct).startsWith("application/json"));
        List<String> traceIds = response.headers().allValues(TraceIdFilter.TRACE_ID_HEADER);
        assertThat(traceIds).hasSize(1);
        Map<String, Object> body = json(response);
        assertThat(body).containsOnlyKeys("code", "message", "traceId")
                .containsEntry("code", "AUTHENTICATION_REQUIRED")
                .containsEntry("message", FIXED_401_MESSAGE)
                .containsEntry("traceId", traceIds.getFirst());
        if (sentToken != null) {
            assertThat(response.body()).doesNotContain(sentToken);
        }
        assertThat(response.body()).doesNotContainIgnoringCase("jwt").doesNotContainIgnoringCase("exception");
    }

    // ------------------------------------------------------------------ §1 정상

    @Nested
    @DisplayName("§1 정상 토큰")
    class 정상 {

        @Test
        void 사용자_id_가_인증_주체로_연결되고_traceId_가_헤더와_MDC_에_같다() throws Exception {
            HttpResponse<String> response = probe(TestAccessTokens.access(NOW, USER));

            assertThat(response.statusCode()).isEqualTo(200);
            Map<String, Object> body = json(response);
            assertThat(((Number) body.get("userId")).longValue()).isEqualTo(USER);
            List<String> traceIds = response.headers().allValues(TraceIdFilter.TRACE_ID_HEADER);
            assertThat(traceIds).hasSize(1);
            assertThat(traceIds.getFirst()).matches("[0-9a-f]{32}");
            assertThat(body.get("mdcTraceId")).isEqualTo(traceIds.getFirst());
        }

        @Test
        void 요청마다_traceId_가_다르다() throws Exception {
            String token = TestAccessTokens.access(NOW, USER);
            assertThat(probe(token).headers().firstValue(TraceIdFilter.TRACE_ID_HEADER))
                    .isNotEqualTo(probe(token).headers().firstValue(TraceIdFilter.TRACE_ID_HEADER));
        }

        @Test
        void aud_배열에_우리_audience_가_있으면_통과한다() throws Exception {
            assertThat(probe(token(s -> s.claim("aud", List.of("other-api", "railgate-api")))).statusCode())
                    .isEqualTo(200);
        }
    }

    // ------------------------------------------------------------------ §2 누락·변조·알고리즘

    @Nested
    @DisplayName("§2 ★ 누락·변조·잘못된 알고리즘·키")
    class 서명 {

        @Test
        void 토큰이_없으면_401() throws Exception {
            assertUnauthorized(probe(null), null);
        }

        @Test
        void 서명이_변조되면_401() throws Exception {
            String bad = TestAccessTokens.tampered(TestAccessTokens.access(NOW, USER));
            assertUnauthorized(probe(bad), bad);
        }

        @Test
        void 다른_키로_서명되면_401() throws Exception {
            String bad = token(s -> s.secretBase64 = TestAccessTokens.OTHER_SECRET_BASE64);
            assertUnauthorized(probe(bad), bad);
        }

        @Test
        void alg_none_은_401() throws Exception {
            String bad = TestAccessTokens.unsigned(NOW, USER);
            assertUnauthorized(probe(bad), bad);
        }

        @ParameterizedTest
        @ValueSource(strings = {"HS512", "RS256"})
        void HS256_이_아니면_401(String algorithm) throws Exception {
            String bad = token(s -> s.algorithm = algorithm);
            assertUnauthorized(probe(bad), bad);
        }

        @Test
        void 형식이_아닌_문자열은_401() throws Exception {
            assertUnauthorized(probe("not-a-jwt"), "not-a-jwt");
        }

        @Test
        void 쿼리_문자열의_토큰은_받지_않는다() throws Exception {
            String good = TestAccessTokens.access(NOW, USER);
            assertUnauthorized(get(PROBE + "?access_token=" + good, null), good);
        }
    }

    // ------------------------------------------------------------------ §3 용도·발급자

    @Nested
    @DisplayName("§3 ★ issuer·audience·typ·token_use")
    class 용도 {

        @Test
        void issuer_가_다르면_401() throws Exception {
            String bad = token(s -> s.claim("iss", "railgate-queue"));
            assertUnauthorized(probe(bad), bad);
        }

        @Test
        void 개발_발급_issuer_도_운영_검증기는_거부한다() throws Exception {
            String bad = token(s -> s.claim("iss", "railgate-dev-auth"));
            assertUnauthorized(probe(bad), bad);
        }

        @Test
        void audience_가_없거나_다르면_401() throws Exception {
            assertUnauthorized(probe(token(s -> s.without("aud"))), null);
            assertUnauthorized(probe(token(s -> s.claim("aud", List.of("railgate-reservation")))), null);
        }

        @Test
        void typ_가_at_jwt_가_아니면_401() throws Exception {
            assertUnauthorized(probe(token(s -> s.typ = "JWT")), null);
            assertUnauthorized(probe(token(s -> s.typ = "admission+jwt")), null);
            assertUnauthorized(probe(token(s -> s.typ = null)), null);
        }

        @Test
        void token_use_가_access_가_아니면_401() throws Exception {
            assertUnauthorized(probe(token(s -> s.claim("token_use", "admission"))), null);
            assertUnauthorized(probe(token(s -> s.without("token_use"))), null);
        }

        /** ★ 입장 토큰은 액세스 토큰 자리에서 통과하지 않는다 — 키·iss·aud·typ·token_use 가 모두 다르다. */
        @Test
        void 입장_토큰을_액세스_토큰으로_받지_않는다() throws Exception {
            String admission = token(s -> {
                s.secretBase64 = TestAccessTokens.OTHER_SECRET_BASE64;
                s.typ = "admission+jwt";
                s.claim("iss", "railgate-queue").claim("aud", List.of("railgate-reservation"))
                        .claim("token_use", "admission").claim("evt", 9_000_000L);
            });
            assertUnauthorized(probe(admission), admission);
        }

        /** 키가 실수로 같게 설정돼도 용도 claim 이 두 번째 방어선이다. */
        @Test
        void 같은_키로_서명된_입장_토큰_형식도_거부한다() throws Exception {
            String admissionShaped = token(s -> {
                s.typ = "admission+jwt";
                s.claim("iss", "railgate-queue").claim("aud", List.of("railgate-reservation"))
                        .claim("token_use", "admission").claim("evt", 9_000_000L);
            });
            assertUnauthorized(probe(admissionShaped), admissionShaped);
        }
    }

    // ------------------------------------------------------------------ §4 필수 claim

    @Nested
    @DisplayName("§4 ★ 필수 claim 의 존재와 타입")
    class 필수_claim {

        @ParameterizedTest
        @ValueSource(strings = {"sub", "iat", "exp", "jti"})
        void 필수_claim_이_없으면_401(String claim) throws Exception {
            assertUnauthorized(probe(token(s -> s.without(claim))), null);
        }

        @ParameterizedTest
        @ValueSource(strings = {"abc", "0", "-1", "01", "1.5", " 7", "9223372036854775808"})
        void sub_가_양수_long_10진수_문자열이_아니면_401(String sub) throws Exception {
            assertUnauthorized(probe(token(s -> s.claim("sub", sub))), null);
        }

        @Test
        void sub_가_숫자_타입이면_401() throws Exception {
            assertUnauthorized(probe(token(s -> s.claim("sub", USER))), null);
        }

        @Test
        void jti_가_비었거나_문자열이_아니면_401() throws Exception {
            assertUnauthorized(probe(token(s -> s.claim("jti", ""))), null);
            assertUnauthorized(probe(token(s -> s.claim("jti", 123))), null);
        }

        @Test
        void 시각_claim_이_숫자가_아니면_401() throws Exception {
            assertUnauthorized(probe(token(s -> s.claim("iat", "yesterday"))), null);
            assertUnauthorized(probe(token(s -> s.claim("exp", "tomorrow"))), null);
        }
    }

    // ------------------------------------------------------------------ §5 시간

    @Nested
    @DisplayName("§5 ★ 만료·미래 시각·수명·시계 오차 (가정값: 오차 30초, 최대 수명 30분)")
    class 시간 {

        @Test
        void 만료_뒤_시계_오차_안이면_통과하고_넘으면_401() throws Exception {
            String token = token(s -> s.claim("exp", Date.from(NOW.plusSeconds(60))));
            clock.set(NOW.plusSeconds(60 + 30));
            assertThat(probe(token).statusCode()).as("exp + 30초 = 경계 안").isEqualTo(200);
            clock.set(NOW.plusSeconds(60 + 31));
            assertUnauthorized(probe(token), token);
        }

        @Test
        void 미래에_발급된_토큰은_시계_오차를_넘으면_401() throws Exception {
            assertThat(probe(token(s -> s.claim("iat", Date.from(NOW.plusSeconds(30)))
                    .claim("exp", Date.from(NOW.plusSeconds(600))))).statusCode()).isEqualTo(200);
            assertUnauthorized(probe(token(s -> s.claim("iat", Date.from(NOW.plusSeconds(31)))
                    .claim("exp", Date.from(NOW.plusSeconds(600))))), null);
        }

        @Test
        void nbf_가_있으면_검증한다() throws Exception {
            assertThat(probe(token(s -> s.claim("nbf", Date.from(NOW.minusSeconds(1))))).statusCode()).isEqualTo(200);
            assertUnauthorized(probe(token(s -> s.claim("nbf", Date.from(NOW.plusSeconds(31))))), null);
        }

        @Test
        void 수명이_30분을_넘거나_exp_가_iat_이전이면_401() throws Exception {
            assertThat(probe(token(s -> s.claim("exp", Date.from(NOW.plus(Duration.ofMinutes(30)))))).statusCode())
                    .isEqualTo(200);
            assertUnauthorized(probe(token(s -> s.claim("exp", Date.from(NOW.plus(Duration.ofMinutes(30)).plusSeconds(1))))), null);
            assertUnauthorized(probe(token(s -> s.claim("iat", Date.from(NOW))
                    .claim("exp", Date.from(NOW.minusSeconds(1))))), null);
        }
    }

    // ------------------------------------------------------------------ §6 경로와 필터

    @Nested
    @DisplayName("§6 ★ 보호 경로·기본 거부·필터 등록")
    class 경로 {

        @Test
        void health_만_토큰_없이_열린다() throws Exception {
            HttpResponse<String> health = get("/actuator/health", null);
            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(health.body()).doesNotContain("components").doesNotContain("db");
            assertThat(health.headers().allValues(TraceIdFilter.TRACE_ID_HEADER)).hasSize(1);
        }

        @ParameterizedTest
        @ValueSource(strings = {"/actuator/metrics", "/actuator/info", "/actuator", "/not-mapped", "/api/reservations/holds"})
        void 그_밖의_경로는_토큰_없이_401(String path) throws Exception {
            assertUnauthorized(get(path, null), null);
        }

        @ParameterizedTest
        @ValueSource(strings = {"/actuator/metrics", "/actuator/info", "/not-mapped"})
        void api_밖의_경로는_인증돼도_403(String path) throws Exception {
            HttpResponse<String> response = get(path, TestAccessTokens.access(NOW, USER));
            assertThat(response.statusCode()).isEqualTo(403);
            Map<String, Object> body = json(response);
            assertThat(body).containsEntry("code", "FORBIDDEN")
                    .containsEntry("traceId", response.headers().firstValue(TraceIdFilter.TRACE_ID_HEADER).orElseThrow());
        }

        /** traceId 필터는 등록 빈 하나로만 존재한다 — 같은 필터가 일반 Filter 빈으로 한 번 더 자동 등록되지 않는다. */
        @Test
        void traceId_필터는_한_번만_등록된다() {
            assertThat(context.getBeansOfType(TraceIdFilter.class)).isEmpty();
            assertThat(context.getBeansOfType(org.springframework.boot.web.servlet.FilterRegistrationBean.class)
                    .values().stream().filter(r -> r.getFilter() instanceof TraceIdFilter)).hasSize(1);
        }

        @Test
        void 테스트_시계가_주입된다() {
            assertThat(context.getBean(Clock.class)).isSameAs(clock);
        }
    }
}
