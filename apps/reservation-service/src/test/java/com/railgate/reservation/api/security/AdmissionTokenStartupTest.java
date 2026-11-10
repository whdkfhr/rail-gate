package com.railgate.reservation.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.railgate.queuetoken.AdmissionTokenVerifier;
import com.railgate.reservation.config.AccessTokenDecoderConfig;
import com.railgate.reservation.config.AdmissionTokenConfig;
import com.railgate.reservation.support.TestAccessTokens;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Task 2J-C — 입장 토큰 설정은 <b>기동 시</b> 검증한다. 실제 프로퍼티 바인딩·컨텍스트 시작으로 확인하고, 실패 메시지에 키 값이
 * 없는지 본다.
 */
@DisplayName("Task 2J-C — 입장 토큰 설정 기동 검증")
class AdmissionTokenStartupTest {

    private static final String ACCESS = "railgate.auth.access-token.secret=" + TestAccessTokens.ACCESS_SECRET_BASE64;
    private static final String ADMISSION = "railgate.auth.admission-token.secret=";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AccessTokenDecoderConfig.class, AdmissionTokenConfig.class);

    private static void assertFailed(org.springframework.boot.test.context.assertj.AssertableApplicationContext context,
                                     String messagePart, String secretValue) {
        assertThat(context).hasFailed();
        assertThat(context.getStartupFailure()).rootCause().hasMessageContaining(messagePart);
        if (secretValue != null && !secretValue.isBlank()) {
            assertThat(context.getStartupFailure()).rootCause().message().doesNotContain(secretValue.trim());
        }
    }

    @Test
    void 올바른_두_키면_검증기가_만들어진다() {
        runner.withPropertyValues(ACCESS, ADMISSION + TestAccessTokens.OTHER_SECRET_BASE64)
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(AdmissionTokenVerifier.class));
    }

    @Test
    void 입장_키가_없으면_기동이_실패한다() {
        runner.withPropertyValues(ACCESS)
                .run(context -> assertFailed(context, "railgate.auth.admission-token.secret", null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"   ", "not base64!!", "c2hvcnQ="})
    void 입장_키_형식이_틀리거나_짧으면_기동이_실패한다(String secret) {
        runner.withPropertyValues(ACCESS, ADMISSION + secret)
                .run(context -> assertFailed(context, "railgate.auth.admission-token.secret", secret));
    }

    @Test
    void 한_바이트만_반복한_입장_키는_거부한다() {
        String weak = Base64.getEncoder().encodeToString(new byte[32]);
        runner.withPropertyValues(ACCESS, ADMISSION + weak)
                .run(context -> assertFailed(context, "railgate.auth.admission-token.secret", weak));
    }

    /** ★ K_access 와 K_admission 이 같으면 두 토큰을 키로 구별할 수 없다 — 기동하지 않는다. */
    @Test
    void 두_키가_같으면_기동이_실패한다() {
        runner.withPropertyValues(ACCESS, ADMISSION + TestAccessTokens.ACCESS_SECRET_BASE64)
                .run(context -> assertFailed(context, "같다", TestAccessTokens.ACCESS_SECRET_BASE64));
    }

    /** Base64 표기만 다르고 디코딩한 바이트가 같아도 같은 키다. */
    @Test
    void 표기가_달라도_디코딩한_키가_같으면_기동이_실패한다() {
        runner.withPropertyValues(ACCESS, ADMISSION + "  " + TestAccessTokens.ACCESS_SECRET_BASE64 + "  ")
                .run(context -> assertFailed(context, "같다", TestAccessTokens.ACCESS_SECRET_BASE64));
    }

    @ParameterizedTest
    @ValueSource(strings = {"railgate.auth.admission-token.clock-skew=-1s", "railgate.auth.admission-token.max-lifetime=0s",
            "railgate.auth.admission-token.issuer= ", "railgate.auth.admission-token.audience= "})
    void 잘못된_정책_설정이면_기동이_실패한다(String property) {
        runner.withPropertyValues(ACCESS, ADMISSION + TestAccessTokens.OTHER_SECRET_BASE64, property)
                .run(context -> assertFailed(context, "railgate.auth.admission-token", null));
    }
}
