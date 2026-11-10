package com.railgate.reservation.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.railgate.reservation.api.trace.TraceIdFilter;
import com.railgate.reservation.support.MutableClock;
import com.railgate.reservation.support.SecuredWebTestSupport;
import com.railgate.reservation.support.TestAccessTokens;
import com.railgate.testprobe.AuthProbeController;
import com.railgate.testprobe.HoldProbeController;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.security.web.FilterChainProxy;

/**
 * ★ Task 2J-C — <b>실제 SecurityFilterChain</b>(내장 서버 + 실제 필터 등록)에서 입장 토큰 검증과 두 토큰의 사용자 대조를 확인한다
 * (ADR-015 §4·§5). 선점 경로의 확인용 컨트롤러는 테스트 전용이며 통과하면 실제 조정자를 부른다 — 거부 시 키·좌석·quota 가
 * 그대로라는 것이 필터가 막았다는 근거다.
 */
@DisplayName("Task 2J-C — 입장 토큰 필터 (SecurityFilterChain)")
class AdmissionTokenFilterTest extends SecuredWebTestSupport {

    private static final long USER = 4242L;
    private static final long OTHER_USER = 4343L;
    private static final long SCHEDULE = 8_800L;
    private static final String INVALID_MESSAGE = "입장 권한을 확인할 수 없습니다.";

    @LocalServerPort
    private int port;

    @Autowired
    private MutableClock clock;

    @Autowired
    private HoldProbeController probe;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private MeterRegistry meters;

    private final HttpClient http = HttpClient.newHttpClient();
    private long seat;
    private int keySeq;

    @BeforeEach
    void reset() {
        clock.set(NOW);
        jdbc().execute("DELETE FROM idempotency_key");
        seat = insertAvailableSeat(SCHEDULE, "1A");
    }

    // ------------------------------------------------------------------ 픽스처

    private String access() {
        return TestAccessTokens.access(NOW, USER);
    }

    private String admission() {
        return TestAccessTokens.admission(NOW, USER, FIXTURE_SALE_EVENT_ID);
    }

    private HttpResponse<String> post(String bearer, String... admissionHeaders) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + HoldProbeController.PATH + "?seatId=" + seat))
                .POST(HttpRequest.BodyPublishers.noBody())
                .header("Idempotency-Key", "adm-key-" + (keySeq++) + "-xx");
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        for (String value : admissionHeaders) {
            request.header(AdmissionTokenFilter.HEADER, value);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String bearer) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, Object> json(HttpResponse<String> response) {
        return JsonParserFactory.getJsonParser().parseMap(response.body());
    }

    private long count(String sql) {
        return jdbc().queryForObject(sql, Long.class);
    }

    /** 거부된 요청은 확인용 컨트롤러(=조정자)에 닿지 않고 키·좌석·quota 를 바꾸지 않는다. */
    private void assertNothingChanged(int invocationsBefore) {
        assertThat(probe.invocations()).as("조정자 미호출").isEqualTo(invocationsBefore);
        assertThat(count("SELECT COUNT(*) FROM idempotency_key")).as("멱등키 불변").isZero();
        assertThat(statusOf(seat)).as("좌석 불변").isEqualTo("AVAILABLE");
        assertThat(quotaRowCount()).as("quota 불변").isZero();
    }

    private void assertAdmissionInvalid(HttpResponse<String> response, String... sentTokens) {
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().allValues("WWW-Authenticate")).containsExactly("Admission-Token");
        String traceId = response.headers().firstValue(TraceIdFilter.TRACE_ID_HEADER).orElseThrow();
        assertThat(json(response)).containsOnlyKeys("code", "message", "traceId")
                .containsEntry("code", "ADMISSION_TOKEN_INVALID")
                .containsEntry("message", INVALID_MESSAGE)
                .containsEntry("traceId", traceId);
        for (String token : sentTokens) {
            assertThat(response.body()).doesNotContain(token);
        }
    }

    private void assertAuthenticationRequired(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().allValues("WWW-Authenticate")).containsExactly("Bearer");
        assertThat(json(response)).containsEntry("code", "AUTHENTICATION_REQUIRED");
    }

    // ------------------------------------------------------------------ §1 정상

    @Nested
    @DisplayName("§1 정상 — 입장 정보 전달, 로그인 principal 유지")
    class 정상 {

        @Test
        void 두_토큰이_유효하고_사용자가_같으면_통과해_조정자에_닿는다() throws Exception {
            HttpResponse<String> response = post(access(), admission());

            assertThat(response.statusCode()).isEqualTo(200);
            Map<String, Object> body = json(response);
            assertThat(((Number) body.get("principalUserId")).longValue()).as("로그인 principal 그대로").isEqualTo(USER);
            assertThat(((Number) body.get("admissionUserId")).longValue()).isEqualTo(USER);
            assertThat(((Number) body.get("admissionSaleEventId")).longValue()).isEqualTo(FIXTURE_SALE_EVENT_ID);
            assertThat(body.get("result")).isEqualTo("Executed");
            assertThat(statusOf(seat)).as("통과하면 실제로 선점된다 — 거부 테스트의 대조군").isEqualTo("HELD");
        }

        @Test
        void 입장이_필요_없는_API_와_health_는_입장_토큰을_요구하지_않는다() throws Exception {
            assertThat(get(AuthProbeController.PATH, access()).statusCode()).isEqualTo(200);
            assertThat(get("/actuator/health", null).statusCode()).isEqualTo(200);
        }
    }

    // ------------------------------------------------------------------ §2 헤더

    @Nested
    @DisplayName("§2 ★ Admission-Token 헤더 — 하나만, 비어 있지 않게")
    class 헤더 {

        @Test
        void 없으면_401() throws Exception {
            int before = probe.invocations();
            assertAdmissionInvalid(post(access()));
            assertNothingChanged(before);
        }

        @Test
        void 두_번_오면_401() throws Exception {
            int before = probe.invocations();
            assertAdmissionInvalid(post(access(), admission(), admission()));
            assertNothingChanged(before);
        }

        @Test
        void 쉼표로_이으면_401() throws Exception {
            String token = admission();
            assertAdmissionInvalid(post(access(), token + "," + token), token);
        }

        @Test
        void 비어_있거나_공백이면_401() throws Exception {
            assertAdmissionInvalid(post(access(), ""));
            assertAdmissionInvalid(post(access(), " "));
        }
    }

    // ------------------------------------------------------------------ §3 토큰

    @Nested
    @DisplayName("§3 ★ 서명·알고리즘·용도·claim·시각")
    class 토큰 {

        @Test
        void 서명_변조와_다른_키는_401() throws Exception {
            String tampered = TestAccessTokens.tampered(admission());
            assertAdmissionInvalid(post(access(), tampered), tampered);
            String accessKey = TestAccessTokens.admission(NOW, USER, FIXTURE_SALE_EVENT_ID,
                    s -> s.secretBase64 = TestAccessTokens.ACCESS_SECRET_BASE64);
            assertAdmissionInvalid(post(access(), accessKey), accessKey);
        }

        @Test
        void alg_none_과_HS256_이_아닌_알고리즘은_401() throws Exception {
            int before = probe.invocations();
            assertAdmissionInvalid(post(access(), TestAccessTokens.unsigned(NOW, USER)));
            assertAdmissionInvalid(post(access(), TestAccessTokens.admission(NOW, USER, FIXTURE_SALE_EVENT_ID,
                    s -> s.algorithm = "HS512")));
            assertNothingChanged(before);
        }

        /** ★ 액세스 토큰을 입장 토큰 자리에 넣으면 거부한다. */
        @Test
        void 액세스_토큰을_입장_토큰으로_받지_않는다() throws Exception {
            String accessToken = access();
            assertAdmissionInvalid(post(accessToken, accessToken), accessToken);
        }

        /** 같은 키로 서명했지만 용도가 다른 토큰 — 입장 키로 서명한 액세스 토큰 모양. */
        @Test
        void 같은_입장_키로_서명한_다른_용도_토큰은_401() throws Exception {
            String accessShaped = TestAccessTokens.access(NOW, USER, s -> s.secretBase64 = TestAccessTokens.OTHER_SECRET_BASE64);
            assertAdmissionInvalid(post(access(), accessShaped), accessShaped);
            String wrongUse = TestAccessTokens.admission(NOW, USER, FIXTURE_SALE_EVENT_ID,
                    s -> s.claim("token_use", "access"));
            assertAdmissionInvalid(post(access(), wrongUse), wrongUse);
        }

        @Test
        void issuer_audience_typ_가_다르면_401() throws Exception {
            assertAdmissionInvalid(post(access(), TestAccessTokens.admission(NOW, USER, FIXTURE_SALE_EVENT_ID,
                    s -> s.claim("iss", "railgate-auth"))));
            assertAdmissionInvalid(post(access(), TestAccessTokens.admission(NOW, USER, FIXTURE_SALE_EVENT_ID,
                    s -> s.claim("aud", List.of("railgate-api")))));
            assertAdmissionInvalid(post(access(), TestAccessTokens.admission(NOW, USER, FIXTURE_SALE_EVENT_ID,
                    s -> s.typ = "at+jwt")));
        }

        @Test
        void 필수_claim_누락과_잘못된_타입은_401() throws Exception {
            int before = probe.invocations();
            for (String claim : List.of("sub", "evt", "iat", "exp", "jti")) {
                assertAdmissionInvalid(post(access(), TestAccessTokens.admission(NOW, USER, FIXTURE_SALE_EVENT_ID,
                        s -> s.without(claim))));
            }
            assertAdmissionInvalid(post(access(), TestAccessTokens.admission(NOW, USER, FIXTURE_SALE_EVENT_ID,
                    s -> s.claim("evt", FIXTURE_SALE_EVENT_ID))));
            assertAdmissionInvalid(post(access(), TestAccessTokens.admission(NOW, USER, FIXTURE_SALE_EVENT_ID,
                    s -> s.claim("sub", USER))));
            assertAdmissionInvalid(post(access(), TestAccessTokens.admission(NOW, USER, FIXTURE_SALE_EVENT_ID,
                    s -> s.claim("evt", "0"))));
            assertNothingChanged(before);
        }

        @Test
        void 만료_경계와_최대_수명() throws Exception {
            String token = TestAccessTokens.admission(NOW, USER, FIXTURE_SALE_EVENT_ID,
                    s -> s.claim("exp", Date.from(NOW.plusSeconds(60))));
            clock.set(NOW.plusSeconds(91));
            String freshAccess = TestAccessTokens.access(NOW.plusSeconds(91), USER);
            assertAdmissionInvalid(post(freshAccess, token), token);
            clock.set(NOW);
            assertAdmissionInvalid(post(access(), TestAccessTokens.admission(NOW, USER, FIXTURE_SALE_EVENT_ID,
                    s -> s.claim("exp", Date.from(NOW.plus(Duration.ofMinutes(10)).plusSeconds(1))))));
        }
    }

    // ------------------------------------------------------------------ §4 사용자 대조와 우선순위

    @Nested
    @DisplayName("§4 ★ 두 토큰의 사용자 대조와 오류 우선순위")
    class 대조 {

        @Test
        void 사용자가_다르면_403() throws Exception {
            int before = probe.invocations();
            HttpResponse<String> response = post(access(), TestAccessTokens.admission(NOW, OTHER_USER, FIXTURE_SALE_EVENT_ID));

            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(json(response)).containsEntry("code", "ADMISSION_USER_MISMATCH")
                    .containsEntry("traceId", response.headers().firstValue(TraceIdFilter.TRACE_ID_HEADER).orElseThrow());
            assertThat(response.body()).doesNotContain(Long.toString(OTHER_USER)).doesNotContain(Long.toString(USER));
            assertNothingChanged(before);
        }

        /** ★ 액세스 오류가 입장 오류보다 먼저다 — 로그인 검증을 통과하지 못하면 입장 토큰은 보지 않는다. */
        @Test
        void 액세스_토큰이_없거나_틀리면_입장_토큰과_무관하게_AUTHENTICATION_REQUIRED() throws Exception {
            int before = probe.invocations();
            assertAuthenticationRequired(post(null, admission()));
            assertAuthenticationRequired(post(null));
            assertAuthenticationRequired(post(TestAccessTokens.tampered(access()), "garbage"));
            assertAuthenticationRequired(post(admission(), admission()));
            assertNothingChanged(before);
        }
    }

    // ------------------------------------------------------------------ §5 구조와 관측

    @Nested
    @DisplayName("§5 필터 등록·메트릭·네트워크 무의존")
    class 구조 {

        /** 입장 필터는 SecurityFilterChain 에 한 번만 있고 서블릿 컨테이너에 따로 등록되지 않는다. */
        @Test
        void 입장_필터는_보안_체인에만_한_번_등록된다() {
            assertThat(context.getBeansOfType(AdmissionTokenFilter.class)).isEmpty();
            assertThat(context.getBeansOfType(FilterRegistrationBean.class).values().stream()
                    .filter(r -> r.getFilter() instanceof AdmissionTokenFilter)).isEmpty();
            FilterChainProxy proxy = context.getBean(FilterChainProxy.class);
            long occurrences = proxy.getFilterChains().stream()
                    .flatMap(chain -> chain.getFilters().stream())
                    .filter(AdmissionTokenFilter.class::isInstance)
                    .count();
            assertThat(occurrences).isEqualTo(1);
        }

        @Test
        void 메트릭은_고정_태그만_쓴다() throws Exception {
            post(access(), admission());
            post(access());
            post(access(), TestAccessTokens.admission(NOW, OTHER_USER, FIXTURE_SALE_EVENT_ID));

            List<Meter> admissionMeters = meters.getMeters().stream()
                    .filter(m -> m.getId().getName().equals("railgate.auth.admission")).toList();
            assertThat(admissionMeters).isNotEmpty();
            Set<String> keys = admissionMeters.stream().flatMap(m -> m.getId().getTags().stream())
                    .map(Tag::getKey).collect(Collectors.toSet());
            assertThat(keys).isSubsetOf("outcome", "reason");
            String values = admissionMeters.stream().flatMap(m -> m.getId().getTags().stream())
                    .map(Tag::getValue).collect(Collectors.joining(","));
            assertThat(values).doesNotContain(Long.toString(USER)).doesNotContain(Long.toString(OTHER_USER))
                    .doesNotContain(Long.toString(FIXTURE_SALE_EVENT_ID));
            assertThat(meters.counter("railgate.auth.admission", "outcome", "accepted", "reason", "none").count())
                    .isGreaterThanOrEqualTo(1);
            assertThat(meters.counter("railgate.auth.admission", "outcome", "user_mismatch", "reason", "none").count())
                    .isGreaterThanOrEqualTo(1);
        }

        /** 검증 경로에 queue-service·Redis 호출 수단이 없다 — 앱 클래스패스에 Redis 클라이언트가 없다. */
        @Test
        void 레디스_클라이언트가_클래스패스에_없다() {
            for (String type : List.of("io.lettuce.core.RedisClient", "redis.clients.jedis.Jedis",
                    "org.springframework.data.redis.core.RedisTemplate")) {
                assertThat(isPresent(type)).as(type).isFalse();
            }
        }

        private boolean isPresent(String type) {
            try {
                Class.forName(type);
                return true;
            } catch (ClassNotFoundException absent) {
                return false;
            }
        }
    }
}
