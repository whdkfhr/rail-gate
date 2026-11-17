package com.railgate.reservation.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.railgate.reservation.api.trace.TraceIdFilter;
import com.railgate.reservation.support.MutableClock;
import com.railgate.reservation.support.SecuredWebTestSupport;
import com.railgate.reservation.support.TestAccessTokens;
import com.railgate.testprobe.AuthProbeController;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
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

    // ------------------------------------------------------------------ §5-1 nbf 의 원래 값 (TASK-002J-C §8 후속)

    @Nested
    @DisplayName("§5-1 ★ 선택 claim nbf — 없음과 잘못된 값을 구분한다")
    class Nbf_원래_값 {

        private static final long MAX_EPOCH = Instant.MAX.getEpochSecond();
        private static final long MIN_EPOCH = Instant.MIN.getEpochSecond();

        private static String payload(String token) {
            return new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
        }

        /** nbf 값을 Date 로 바꾸지 않고 그대로 넣고, 페이로드 원문에 그 표현이 있는지 확인한 토큰. */
        private String nbf(Object value, String expectedJson) {
            String token = token(s -> s.claim("nbf", value));
            assertThat(payload(token)).as("생성 과정에서 값이 바뀌지 않았다").contains("\"nbf\":" + expectedJson);
            return token;
        }

        @Test
        void nbf_가_없으면_통과한다() throws Exception {
            String token = token(s -> s.without("nbf"));
            assertThat(payload(token)).doesNotContain("nbf");
            assertThat(probe(token).statusCode()).isEqualTo(200);
        }

        @Test
        void 정상_nbf_와_시계_오차_경계는_그대로다() throws Exception {
            assertThat(probe(nbf(NOW.getEpochSecond(), Long.toString(NOW.getEpochSecond()))).statusCode()).isEqualTo(200);
            long edge = NOW.plusSeconds(30).getEpochSecond();
            assertThat(probe(nbf(edge, Long.toString(edge))).statusCode()).as("now + 30초").isEqualTo(200);
            long over = NOW.plusSeconds(31).getEpochSecond();
            String token = nbf(over, Long.toString(over));
            assertUnauthorized(probe(token), token);
        }

        /** ★ RED 대상 — 수정 전에는 변환 중 nbf 가 빠져 "없음" 처럼 통과했다 (200). */
        @ParameterizedTest
        @ValueSource(longs = {Long.MAX_VALUE, Long.MIN_VALUE})
        void long_극단값_nbf_는_401(long value) throws Exception {
            String token = nbf(value, Long.toString(value));
            assertUnauthorized(probe(token), token);
        }

        @Test
        void Instant_범위_바로_바깥의_nbf_는_401() throws Exception {
            for (long value : new long[] {MAX_EPOCH + 1, MIN_EPOCH - 1}) {
                String token = nbf(value, Long.toString(value));
                assertUnauthorized(probe(token), token);
            }
        }

        @Test
        void long_을_넘는_정수_nbf_는_401() throws Exception {
            for (String huge : new String[] {"9223372036854775808", "-9223372036854775809"}) {
                String token = nbf(new BigInteger(huge), huge);
                assertUnauthorized(probe(token), token);
            }
        }

        /** Instant 범위 안의 끝값은 변환되므로 기존 규칙(nbf ≤ now + 30초)대로 판정한다. */
        @Test
        void Instant_범위_안의_끝값은_기존_규칙으로_판정한다() throws Exception {
            assertThat(probe(nbf(MIN_EPOCH, Long.toString(MIN_EPOCH))).statusCode()).isEqualTo(200);
            String future = nbf(MAX_EPOCH, Long.toString(MAX_EPOCH));
            assertUnauthorized(probe(future), future);
        }

        /** nbf 는 정수 JSON 숫자(초)만 받는다 — 문자열·소수·불리언·배열·객체·null 은 잘못된 값이다. */
        @Test
        void 정수_숫자가_아닌_nbf_는_401() throws Exception {
            long past = NOW.minusSeconds(60).getEpochSecond();
            Object[][] cases = {
                    {"now", "\"now\""},
                    {Long.toString(past), "\"" + past + "\""},
                    {NOW.minusSeconds(60).toString(), "\"" + NOW.minusSeconds(60) + "\""},
                    {past + 0.5, Double.toString(past + 0.5)},
                    {true, "true"},
                    {List.of(past), "[" + past + "]"},
                    {Map.of("v", past), "{\"v\":" + past + "}"},
            };
            for (Object[] c : cases) {
                String token = nbf(c[0], (String) c[1]);
                assertThat(probe(token).statusCode()).as("nbf=" + c[1]).isEqualTo(401);
            }
        }

        /** 회귀 — iat·exp 의 극단값은 이전처럼 401 이다. */
        @ParameterizedTest
        @ValueSource(strings = {"iat", "exp"})
        void iat_exp_극단값은_이전처럼_401(String claim) throws Exception {
            for (long value : new long[] {Long.MAX_VALUE, Long.MIN_VALUE}) {
                String token = token(s -> s.claim(claim, value));
                assertThat(payload(token)).contains("\"" + claim + "\":" + value);
                assertUnauthorized(probe(token), token);
            }
        }
    }

    // ------------------------------------------------------------------ §5-2 iat·exp 의 원래 값

    @Nested
    @DisplayName("§5-2 ★ iat·exp 는 원래 JSON 의 정수 초로 판정한다 (필수)")
    class 시각_원래_값 {

        private static final long MAX_EPOCH = Instant.MAX.getEpochSecond();
        private static final long MIN_EPOCH = Instant.MIN.getEpochSecond();
        private static final BigInteger TWO_64 = BigInteger.ONE.shiftLeft(64);

        /** 정상 액세스 토큰의 claim 을 JSON 조각으로 들고, 바꾸거나 지운 뒤 원문 그대로 서명한다. */
        private String raw(Map<String, String> overrides, String... removed) {
            Map<String, String> claims = new java.util.LinkedHashMap<>();
            claims.put("iss", "\"railgate-auth\"");
            claims.put("aud", "[\"railgate-api\"]");
            claims.put("sub", "\"" + USER + "\"");
            claims.put("iat", Long.toString(NOW.getEpochSecond()));
            claims.put("exp", Long.toString(NOW.plusSeconds(600).getEpochSecond()));
            claims.put("jti", "\"" + java.util.UUID.randomUUID() + "\"");
            claims.put("token_use", "\"access\"");
            claims.putAll(overrides);
            for (String name : removed) {
                claims.remove(name);
            }
            String json = claims.entrySet().stream().map(e -> "\"" + e.getKey() + "\":" + e.getValue())
                    .collect(Collectors.joining(",", "{", "}"));
            String token = TestAccessTokens.signedRaw(json);
            assertThat(TestAccessTokens.payloadJson(token)).as("서명 전후 페이로드 원문이 같다").isEqualTo(json);
            return token;
        }

        private void assertRejected(String token) throws Exception {
            assertUnauthorized(probe(token), token);
        }

        /**
         * 라이브러리는 초를 {@code 초 × 1000} 밀리초로 바꾸며 넘친다. {@code target} 밀리초로 감기는 초 값 —
         * 원래 값은 약 {@code 1.8×10^16} 초(Instant 범위 안의 먼 미래)다.
         */
        private static long wrapsTo(Instant target) {
            long targetMillis = target.toEpochMilli();
            long remainder = TWO_64.add(BigInteger.valueOf(targetMillis)).mod(BigInteger.valueOf(1000)).longValue();
            BigInteger millis = TWO_64.add(BigInteger.valueOf(targetMillis - remainder));
            long seconds = millis.divide(BigInteger.valueOf(1000)).longValueExact();
            assertThat(seconds).isGreaterThan(Long.MAX_VALUE / 1000).isLessThanOrEqualTo(MAX_EPOCH);
            assertThat(seconds * 1000).as("곱셈이 넘쳐 target 근처로 감긴다")
                    .isBetween(targetMillis - 1000, targetMillis);
            return seconds;
        }

        @Test
        void 정상_원문_토큰은_통과한다() throws Exception {
            assertThat(probe(raw(Map.of())).statusCode()).isEqualTo(200);
        }

        /** ★ RED 대상 — 먼 미래의 exp 가 넘침으로 "10분 뒤" 로 바뀌어 최대 수명 검사를 통과했다. */
        @Test
        void 넘침으로_가까운_시각이_되는_exp_는_401() throws Exception {
            long exp = wrapsTo(NOW.plusSeconds(600));
            assertRejected(raw(Map.of("exp", Long.toString(exp))));
        }

        /** ★ RED 대상 — 먼 미래의 iat 가 넘침으로 "지금" 으로 바뀌어 미래 발급 검사를 통과했다. */
        @Test
        void 넘침으로_가까운_시각이_되는_iat_는_401() throws Exception {
            long iat = wrapsTo(NOW);
            assertRejected(raw(Map.of("iat", Long.toString(iat))));
        }

        /** ★ RED 대상 — 소수 초는 잘려 정수로 받아들여졌다. */
        @ParameterizedTest
        @ValueSource(strings = {"iat", "exp"})
        void 소수_초는_401(String claim) throws Exception {
            long base = claim.equals("iat") ? NOW.getEpochSecond() : NOW.plusSeconds(600).getEpochSecond();
            assertRejected(raw(Map.of(claim, base + ".5")));
        }

        @ParameterizedTest
        @ValueSource(strings = {"iat", "exp"})
        void 없으면_401(String claim) throws Exception {
            assertRejected(raw(Map.of(), claim));
        }

        @ParameterizedTest
        @ValueSource(strings = {"iat", "exp"})
        void 정수_숫자가_아닌_타입은_401(String claim) throws Exception {
            long base = claim.equals("iat") ? NOW.getEpochSecond() : NOW.plusSeconds(600).getEpochSecond();
            for (String value : new String[] {"null", "\"" + base + "\"", "\"" + Instant.ofEpochSecond(base) + "\"",
                    "true", "[" + base + "]", "{\"v\":" + base + "}"}) {
                assertRejected(raw(Map.of(claim, value)));
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {"iat", "exp"})
        void 시각_표현_범위_밖은_401(String claim) throws Exception {
            for (String value : new String[] {Long.toString(MAX_EPOCH + 1), Long.toString(MIN_EPOCH - 1),
                    Long.toString(Long.MAX_VALUE), Long.toString(Long.MIN_VALUE),
                    "9223372036854775808", "-9223372036854775809"}) {
                assertRejected(raw(Map.of(claim, value)));
            }
        }

        /** 기존 경계 유지 — 발급 now+30초 허용·+31초 거부, 수명 30분 허용·+1초 거부, exp ≤ iat 거부, 만료 +30초 허용·+31초 거부. */
        @Test
        void 기존_시각_경계는_그대로다() throws Exception {
            long now = NOW.getEpochSecond();
            assertThat(probe(raw(Map.of("iat", Long.toString(now + 30)))).statusCode()).isEqualTo(200);
            assertRejected(raw(Map.of("iat", Long.toString(now + 31))));
            assertThat(probe(raw(Map.of("exp", Long.toString(now + 1800)))).statusCode()).isEqualTo(200);
            assertRejected(raw(Map.of("exp", Long.toString(now + 1801))));
            assertRejected(raw(Map.of("exp", Long.toString(now))));
            String exp60 = raw(Map.of("exp", Long.toString(now + 60)));
            clock.set(NOW.plusSeconds(90));
            assertThat(probe(exp60).statusCode()).isEqualTo(200);
            clock.set(NOW.plusSeconds(91));
            assertRejected(exp60);
        }

        /** nbf — 없으면 통과, 명시적 null 은 거부 (원문에 null 이 남아 있다). */
        @Test
        void nbf_누락은_허용하고_명시적_null_은_401() throws Exception {
            String absent = raw(Map.of());
            assertThat(TestAccessTokens.payloadJson(absent)).doesNotContain("nbf");
            assertThat(probe(absent).statusCode()).isEqualTo(200);
            String explicitNull = raw(Map.of("nbf", "null"));
            assertThat(TestAccessTokens.payloadJson(explicitNull)).contains("\"nbf\":null");
            assertRejected(explicitNull);
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
