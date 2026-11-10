package com.railgate.queuetoken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Task 2J-C — 입장 토큰 검증기 (ADR-015 §4). 네트워크·Spring·Servlet 없이 서명·용도·claim·시각을 검증한다.
 * 시각은 고정 {@link Clock} 으로 옮긴다.
 */
@DisplayName("Task 2J-C — AdmissionTokenVerifier")
class AdmissionTokenVerifierTest {

    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final long USER = 4242L;
    private static final long EVENT = 9_000_000L;

    private static AdmissionTokenVerifier verifierAt(Instant now) {
        return new AdmissionTokenVerifier(
                AdmissionTokenKey.fromBase64("test.secret", TestAdmissionTokens.ADMISSION_SECRET),
                AdmissionTokenPolicy.defaults(), Clock.fixed(now, ZoneOffset.UTC));
    }

    private static AdmissionVerification verify(Consumer<TestAdmissionTokens.Spec> customizer) {
        return verifierAt(NOW).verify(TestAdmissionTokens.token(NOW, USER, EVENT, customizer));
    }

    private static AdmissionVerification rejected(AdmissionRejection reason) {
        return new AdmissionVerification.Rejected(reason);
    }

    // ------------------------------------------------------------------ 정상

    @Nested
    @DisplayName("§1 정상")
    class 정상 {

        @Test
        void 정상_토큰의_claim_을_돌려준다() {
            AdmissionVerification result = verify(s -> { });

            assertThat(result).isInstanceOf(AdmissionVerification.Accepted.class);
            AdmissionClaims claims = ((AdmissionVerification.Accepted) result).claims();
            assertThat(claims.userId()).isEqualTo(USER);
            assertThat(claims.saleEventId()).isEqualTo(EVENT);
            assertThat(claims.issuedAt()).isEqualTo(NOW);
            assertThat(claims.expiresAt()).isEqualTo(NOW.plusSeconds(300));
            assertThat(claims.tokenId()).isNotBlank();
        }

        /** {@code aud} 는 JWT 규격대로 문자열 하나 또는 문자열 배열이다. */
        @Test
        void aud_는_문자열_하나도_배열도_받는다() {
            assertThat(verify(s -> s.claim("aud", "railgate-reservation")))
                    .isInstanceOf(AdmissionVerification.Accepted.class);
            assertThat(verify(s -> s.claim("aud", List.of("other", "railgate-reservation"))))
                    .isInstanceOf(AdmissionVerification.Accepted.class);
        }
    }

    // ------------------------------------------------------------------ 서명·알고리즘·형식

    @Nested
    @DisplayName("§2 ★ 서명·알고리즘·형식")
    class 서명 {

        @Test
        void 서명이_변조되면_거부한다() {
            String token = TestAdmissionTokens.tampered(TestAdmissionTokens.token(NOW, USER, EVENT, s -> { }));
            assertThat(verifierAt(NOW).verify(token)).isEqualTo(rejected(AdmissionRejection.SIGNATURE));
        }

        /** 키만 다른 토큰 — 형식·용도가 모두 맞아도 서명에서 거부된다. */
        @Test
        void 키만_다르면_거부한다() {
            assertThat(verify(s -> s.secret = TestAdmissionTokens.OTHER_SECRET))
                    .isEqualTo(rejected(AdmissionRejection.SIGNATURE));
        }

        @Test
        void alg_none_은_거부한다() {
            assertThat(verifierAt(NOW).verify(TestAdmissionTokens.unsigned(NOW, USER, EVENT)))
                    .isEqualTo(rejected(AdmissionRejection.ALGORITHM));
        }

        @ParameterizedTest
        @ValueSource(strings = {"HS384", "HS512", "RS256"})
        void HS256_이_아니면_거부한다(String algorithm) {
            assertThat(verify(s -> s.algorithm = algorithm)).isEqualTo(rejected(AdmissionRejection.ALGORITHM));
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "not-a-jwt", "a.b.c"})
        void 형식이_아니면_거부한다(String token) {
            assertThat(verifierAt(NOW).verify(token)).isEqualTo(rejected(AdmissionRejection.MALFORMED));
        }

        @Test
        void null_은_형식_오류다() {
            assertThat(verifierAt(NOW).verify(null)).isEqualTo(rejected(AdmissionRejection.MALFORMED));
        }
    }

    // ------------------------------------------------------------------ 용도

    @Nested
    @DisplayName("§3 ★ typ·issuer·audience·token_use")
    class 용도 {

        @Test
        void typ_가_admission_jwt_가_아니면_거부한다() {
            assertThat(verify(s -> s.typ = "at+jwt")).isEqualTo(rejected(AdmissionRejection.TYPE));
            assertThat(verify(s -> s.typ = "JWT")).isEqualTo(rejected(AdmissionRejection.TYPE));
            assertThat(verify(s -> s.typ = null)).isEqualTo(rejected(AdmissionRejection.TYPE));
        }

        @Test
        void issuer_audience_token_use_가_다르면_거부한다() {
            assertThat(verify(s -> s.claim("iss", "railgate-auth"))).isEqualTo(rejected(AdmissionRejection.PURPOSE));
            assertThat(verify(s -> s.claim("aud", List.of("railgate-api")))).isEqualTo(rejected(AdmissionRejection.PURPOSE));
            assertThat(verify(s -> s.without("aud"))).isEqualTo(rejected(AdmissionRejection.PURPOSE));
            assertThat(verify(s -> s.claim("token_use", "access"))).isEqualTo(rejected(AdmissionRejection.PURPOSE));
            assertThat(verify(s -> s.without("token_use"))).isEqualTo(rejected(AdmissionRejection.PURPOSE));
        }

        /** ★ 같은 키로 서명된 다른 용도(액세스 토큰 모양)의 토큰 — 키가 같아도 용도 검사가 막는다. */
        @Test
        void 같은_키로_서명한_액세스_토큰_모양은_거부한다() {
            AdmissionVerification result = verify(s -> {
                s.typ = "at+jwt";
                s.claim("iss", "railgate-auth").claim("aud", List.of("railgate-api")).claim("token_use", "access")
                        .without("evt");
            });
            assertThat(result).isInstanceOf(AdmissionVerification.Rejected.class);
        }

        @Test
        void 같은_키_입장_모양이라도_token_use_가_access_면_거부한다() {
            assertThat(verify(s -> s.claim("token_use", "access"))).isEqualTo(rejected(AdmissionRejection.PURPOSE));
        }
    }

    // ------------------------------------------------------------------ claim

    @Nested
    @DisplayName("§4 ★ 필수 claim 의 존재·원래 JSON 타입·범위")
    class 클레임 {

        @ParameterizedTest
        @ValueSource(strings = {"sub", "evt", "iat", "exp", "jti"})
        void 필수_claim_이_없으면_거부한다(String claim) {
            assertThat(verify(s -> s.without(claim))).isEqualTo(rejected(AdmissionRejection.CLAIMS));
        }

        @ParameterizedTest
        @ValueSource(strings = {"abc", "0", "-1", "01", "1.5", " 7", "7 ", "9223372036854775808", ""})
        void sub_와_evt_는_양수_long_10진_문자열이어야_한다(String value) {
            assertThat(verify(s -> s.claim("sub", value))).isEqualTo(rejected(AdmissionRejection.CLAIMS));
            assertThat(verify(s -> s.claim("evt", value))).isEqualTo(rejected(AdmissionRejection.CLAIMS));
        }

        /** ★ 숫자 타입은 받지 않는다 — 문자열과 숫자를 함께 허용하지 않는다. */
        @Test
        void sub_와_evt_가_숫자_타입이면_거부한다() {
            assertThat(verify(s -> s.claim("sub", USER))).isEqualTo(rejected(AdmissionRejection.CLAIMS));
            assertThat(verify(s -> s.claim("evt", EVENT))).isEqualTo(rejected(AdmissionRejection.CLAIMS));
        }

        @Test
        void jti_가_비었거나_문자열이_아니면_거부한다() {
            assertThat(verify(s -> s.claim("jti", ""))).isEqualTo(rejected(AdmissionRejection.CLAIMS));
            assertThat(verify(s -> s.claim("jti", "  "))).isEqualTo(rejected(AdmissionRejection.CLAIMS));
            assertThat(verify(s -> s.claim("jti", 7))).isEqualTo(rejected(AdmissionRejection.CLAIMS));
        }

        @Test
        void 시각_claim_은_정수_숫자여야_한다() {
            assertThat(verify(s -> s.claim("iat", "yesterday"))).isEqualTo(rejected(AdmissionRejection.CLAIMS));
            assertThat(verify(s -> s.claim("exp", NOW.plusSeconds(60).getEpochSecond() + 0.5)))
                    .isEqualTo(rejected(AdmissionRejection.CLAIMS));
            assertThat(verify(s -> s.claim("nbf", "now"))).isEqualTo(rejected(AdmissionRejection.CLAIMS));
        }

        @Test
        void aud_가_문자열이_아니면_거부한다() {
            assertThat(verify(s -> s.claim("aud", 7))).isInstanceOf(AdmissionVerification.Rejected.class);
            assertThat(verify(s -> s.claim("aud", List.of(7)))).isInstanceOf(AdmissionVerification.Rejected.class);
        }
    }

    // ------------------------------------------------------------------ 시각

    @Nested
    @DisplayName("§5 ★ 시각 — 시계 오차 30초·최대 수명 10분 (가정값)")
    class 시각 {

        private AdmissionVerification at(Instant now, Consumer<TestAdmissionTokens.Spec> customizer) {
            return verifierAt(now).verify(TestAdmissionTokens.token(NOW, USER, EVENT, customizer));
        }

        @Test
        void 만료_뒤_30초까지는_받고_31초면_거부한다() {
            Consumer<TestAdmissionTokens.Spec> exp60 = s -> s.claim("exp", NOW.plusSeconds(60).getEpochSecond());
            assertThat(at(NOW.plusSeconds(90), exp60)).isInstanceOf(AdmissionVerification.Accepted.class);
            assertThat(at(NOW.plusSeconds(91), exp60)).isEqualTo(rejected(AdmissionRejection.EXPIRED));
        }

        @Test
        void 미래_발급은_30초까지_받고_31초면_거부한다() {
            assertThat(verify(s -> s.claim("iat", NOW.plusSeconds(30).getEpochSecond())))
                    .isInstanceOf(AdmissionVerification.Accepted.class);
            assertThat(verify(s -> s.claim("iat", NOW.plusSeconds(31).getEpochSecond())))
                    .isEqualTo(rejected(AdmissionRejection.NOT_YET_VALID));
        }

        @Test
        void nbf_는_있으면_검증한다() {
            assertThat(verify(s -> s.claim("nbf", NOW.minusSeconds(1).getEpochSecond())))
                    .isInstanceOf(AdmissionVerification.Accepted.class);
            assertThat(verify(s -> s.claim("nbf", NOW.plusSeconds(31).getEpochSecond())))
                    .isEqualTo(rejected(AdmissionRejection.NOT_YET_VALID));
        }

        @Test
        void 수명은_10분까지이고_exp_는_iat_보다_뒤여야_한다() {
            assertThat(verify(s -> s.claim("exp", NOW.plus(Duration.ofMinutes(10)).getEpochSecond())))
                    .isInstanceOf(AdmissionVerification.Accepted.class);
            assertThat(verify(s -> s.claim("exp", NOW.plus(Duration.ofMinutes(10)).plusSeconds(1).getEpochSecond())))
                    .isEqualTo(rejected(AdmissionRejection.LIFETIME));
            assertThat(verify(s -> s.claim("exp", NOW.getEpochSecond())))
                    .isEqualTo(rejected(AdmissionRejection.LIFETIME));
        }
    }

    // ------------------------------------------------------------------ 키·정책

    @Nested
    @DisplayName("§6 ★ 키 품질과 정책 설정")
    class 설정 {

        @ParameterizedTest
        @ValueSource(strings = {"", "   ", "not base64!!", "c2hvcnQ="})
        void 키가_없거나_형식이_틀리거나_짧으면_거부한다(String secret) {
            assertThatThrownBy(() -> AdmissionTokenKey.fromBase64("railgate.auth.admission-token.secret", secret))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("railgate.auth.admission-token.secret")
                    .satisfies(e -> {
                        if (!secret.isBlank()) {
                            assertThat(e.getMessage()).doesNotContain(secret.trim());
                        }
                    });
        }

        @Test
        void null_키와_한_바이트만_반복한_키는_거부한다() {
            assertThatThrownBy(() -> AdmissionTokenKey.fromBase64("p", null)).isInstanceOf(IllegalArgumentException.class);
            String weak = Base64.getEncoder().encodeToString(new byte[32]);
            assertThatThrownBy(() -> AdmissionTokenKey.fromBase64("p", weak))
                    .isInstanceOf(IllegalArgumentException.class)
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain(weak));
        }

        @Test
        void 같은_바이트인지_비교한다() {
            AdmissionTokenKey key = AdmissionTokenKey.fromBase64("p", TestAdmissionTokens.ADMISSION_SECRET);
            assertThat(key.sameBytesAs(Base64.getDecoder().decode(TestAdmissionTokens.ADMISSION_SECRET))).isTrue();
            assertThat(key.sameBytesAs(Base64.getDecoder().decode(TestAdmissionTokens.OTHER_SECRET))).isFalse();
        }

        @Test
        void 잘못된_정책은_거부한다() {
            assertThatThrownBy(() -> new AdmissionTokenPolicy(" ", "a", Duration.ZERO, Duration.ofMinutes(1)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new AdmissionTokenPolicy("i", "a", Duration.ofSeconds(-1), Duration.ofMinutes(1)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new AdmissionTokenPolicy("i", "a", Duration.ZERO, Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void 키_문자열이_toString_에_나오지_않는다() {
            AdmissionTokenKey key = AdmissionTokenKey.fromBase64("p", TestAdmissionTokens.ADMISSION_SECRET);
            assertThat(key.toString()).doesNotContain(TestAdmissionTokens.ADMISSION_SECRET);
        }
    }

    // ------------------------------------------------------------------ 시각 claim 의 표현 범위 (PR #33 리뷰)

    @Nested
    @DisplayName("§7 ★ 시각 claim 의 표현 범위 — 예외가 아니라 거부 (PR #33 리뷰)")
    class 시각_범위 {

        private static final long MAX_EPOCH = Instant.MAX.getEpochSecond();
        private static final long MIN_EPOCH = Instant.MIN.getEpochSecond();

        /** 올바른 키로 서명했고 {@code claim} 의 값이 페이로드에 <b>그 정수 그대로</b> 들어간 토큰. */
        private String tokenWith(String claim, long value) {
            String token = TestAdmissionTokens.token(NOW, USER, EVENT, s -> s.claim(claim, value));
            assertThat(TestAdmissionTokens.payloadJson(token)).as("생성 과정에서 값이 바뀌지 않았다")
                    .contains("\"" + claim + "\":" + value);
            return token;
        }

        /**
         * ★ RED 대상 — {@code Long.MAX_VALUE}·{@code Long.MIN_VALUE} 는 {@code Instant} 로 바꿀 수 없다. 수정 전에는
         * {@code Instant.ofEpochSecond} 의 {@code DateTimeException} 이 검증 결과 대신 전파됐다.
         */
        @ParameterizedTest
        @ValueSource(strings = {"iat", "exp", "nbf"})
        void long_극단값은_예외_없이_CLAIMS_로_거부한다(String claim) {
            for (long value : new long[] {Long.MAX_VALUE, Long.MIN_VALUE}) {
                String token = tokenWith(claim, value);
                assertThat(verifierAt(NOW).verify(token)).as(claim + "=" + value)
                        .isEqualTo(rejected(AdmissionRejection.CLAIMS));
            }
        }

        /** Instant 표현 범위의 바로 바깥 (±1초) 도 같은 거부다. */
        @ParameterizedTest
        @ValueSource(strings = {"iat", "exp", "nbf"})
        void Instant_범위_바로_바깥은_CLAIMS_로_거부한다(String claim) {
            assertThat(verifierAt(NOW).verify(tokenWith(claim, MAX_EPOCH + 1))).isEqualTo(rejected(AdmissionRejection.CLAIMS));
            assertThat(verifierAt(NOW).verify(tokenWith(claim, MIN_EPOCH - 1))).isEqualTo(rejected(AdmissionRejection.CLAIMS));
        }

        /**
         * Instant 범위 <b>안</b>의 끝값은 변환되므로 기존 시각 규칙이 그대로 판정한다 — 이번 수정이 바꾸지 않는 계약.
         * 이 입력들은 수명·미래 검사에서 먼저 거부되거나 통과할 뿐, 시계 오차 덧셈의 오버플로를 검증하지 않는다
         * (그 검증은 {@link #시계_오차를_더하면_범위를_넘는_exp_는_만료_비교에서_예외를_내지_않는다}).
         */
        @Test
        void Instant_범위의_끝값은_기존_시각_규칙으로_판정한다() {
            assertThat(verifierAt(NOW).verify(tokenWith("exp", MAX_EPOCH))).isEqualTo(rejected(AdmissionRejection.LIFETIME));
            assertThat(verifierAt(NOW).verify(tokenWith("iat", MIN_EPOCH))).isEqualTo(rejected(AdmissionRejection.LIFETIME));
            assertThat(verifierAt(NOW).verify(tokenWith("exp", MIN_EPOCH))).isEqualTo(rejected(AdmissionRejection.LIFETIME));
            assertThat(verifierAt(NOW).verify(tokenWith("iat", MAX_EPOCH))).isEqualTo(rejected(AdmissionRejection.LIFETIME));
            assertThat(verifierAt(NOW).verify(tokenWith("nbf", MAX_EPOCH))).isEqualTo(rejected(AdmissionRejection.NOT_YET_VALID));
            assertThat(verifierAt(NOW).verify(tokenWith("nbf", MIN_EPOCH))).isInstanceOf(AdmissionVerification.Accepted.class);
        }

        /**
         * ★ 변환은 되지만 {@code exp + 시계 오차} 가 Instant 범위를 넘는 입력이 <b>실제로 만료 비교에 도달</b>하는 경우.
         * 기본 정책(최대 수명 10분)에서는 수명 검사가 먼저 거부해 도달하지 않으므로, 최대 수명을 무제한에 가깝게 둔 정책으로
         * 수명·미래 검사를 통과시킨다. 결과가 {@code Accepted} 라는 것이 만료 비교까지 평가됐다는 근거다.
         * 수정 전에는 {@code expiresAt.plus(clockSkew)} 의 {@code DateTimeException} 이 전파됐다.
         */
        @ParameterizedTest
        @ValueSource(longs = {0, 1, 29})
        void 시계_오차를_더하면_범위를_넘는_exp_는_만료_비교에서_예외를_내지_않는다(long belowMax) {
            AdmissionTokenVerifier unlimitedLifetime = new AdmissionTokenVerifier(
                    AdmissionTokenKey.fromBase64("test.secret", TestAdmissionTokens.ADMISSION_SECRET),
                    new AdmissionTokenPolicy(AdmissionTokenPolicy.DEFAULT_ISSUER, AdmissionTokenPolicy.DEFAULT_AUDIENCE,
                            AdmissionTokenPolicy.DEFAULT_CLOCK_SKEW, Duration.ofSeconds(Long.MAX_VALUE)),
                    Clock.fixed(NOW, ZoneOffset.UTC));
            long exp = MAX_EPOCH - belowMax;

            assertThat(unlimitedLifetime.verify(tokenWith("exp", exp))).isInstanceOf(AdmissionVerification.Accepted.class);
        }

        /** 만료 비교를 바꿔도 30초 경계는 그대로다 — 기존 §5 와 같은 입력을 다른 정책 객체로 한 번 더 확인한다. */
        @Test
        void 만료_경계는_그대로다() {
            String exp60 = tokenWith("exp", NOW.plusSeconds(60).getEpochSecond());
            assertThat(verifierAt(NOW.plusSeconds(90)).verify(exp60)).isInstanceOf(AdmissionVerification.Accepted.class);
            assertThat(verifierAt(NOW.plusSeconds(90).plusNanos(1)).verify(exp60))
                    .isEqualTo(rejected(AdmissionRejection.EXPIRED));
        }

        /** {@code long} 을 넘는 JSON 정수는 정수 타입으로 파싱되지 않아 타입 검사에서 거부된다 (원문 페이로드로 확인). */
        @ParameterizedTest
        @ValueSource(strings = {"iat", "exp", "nbf"})
        void long_을_넘는_JSON_정수는_CLAIMS_로_거부한다(String claim) {
            for (String huge : new String[] {"9223372036854775808", "-9223372036854775809", "100000000000000000000000"}) {
                String payload = "{\"iss\":\"railgate-queue\",\"aud\":\"railgate-reservation\",\"token_use\":\"admission\","
                        + "\"sub\":\"" + USER + "\",\"evt\":\"" + EVENT + "\",\"jti\":\"j-1\","
                        + "\"iat\":" + NOW.getEpochSecond() + ",\"exp\":" + NOW.plusSeconds(300).getEpochSecond()
                        + ",\"" + claim + "\":" + huge + "}";
                String token = TestAdmissionTokens.signedRaw(payload.replace(
                        "\"" + claim + "\":" + (claim.equals("iat") ? NOW.getEpochSecond()
                                : NOW.plusSeconds(300).getEpochSecond()) + ",", ""));
                assertThat(TestAdmissionTokens.payloadJson(token)).contains("\"" + claim + "\":" + huge);
                assertThat(verifierAt(NOW).verify(token)).as(claim + "=" + huge)
                        .isEqualTo(rejected(AdmissionRejection.CLAIMS));
            }
        }

        /**
         * 설정 문제는 토큰 오류와 구분한다 — 시계 오차를 현재 시각에 더하거나 뺄 수 없을 만큼 큰 설정은 요청마다 예외를 내므로
         * <b>생성(기동) 시</b> 거부한다.
         */
        @Test
        void 현재_시각에_더할_수_없는_시계_오차_설정은_생성_시_거부한다() {
            AdmissionTokenPolicy huge = new AdmissionTokenPolicy(AdmissionTokenPolicy.DEFAULT_ISSUER,
                    AdmissionTokenPolicy.DEFAULT_AUDIENCE, Duration.ofSeconds(Long.MAX_VALUE), Duration.ofMinutes(10));
            assertThatThrownBy(() -> new AdmissionTokenVerifier(
                    AdmissionTokenKey.fromBase64("test.secret", TestAdmissionTokens.ADMISSION_SECRET), huge,
                    Clock.fixed(NOW, ZoneOffset.UTC)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("clockSkew");
        }
    }
}
