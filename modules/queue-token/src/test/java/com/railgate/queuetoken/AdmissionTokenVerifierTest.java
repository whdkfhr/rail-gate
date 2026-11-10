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
}
