package com.railgate.reservation.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.railgate.reservation.config.AccessTokenDecoderConfig;
import com.railgate.reservation.support.TestAccessTokens;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Task 2J-B — 액세스 토큰 서명키 설정 실패는 <b>기동 시</b> 거부한다. 실제 프로퍼티 바인딩·컨텍스트 시작으로 확인한다.
 * 실패 메시지에 키 값이 들어가지 않는지도 본다.
 */
@DisplayName("Task 2J-B — 액세스 토큰 서명키 기동 검증")
class AccessTokenKeyStartupTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AccessTokenDecoderConfig.class);

    @Test
    void 올바른_키면_디코더가_만들어진다() {
        runner.withPropertyValues("railgate.auth.access-token.secret=" + TestAccessTokens.ACCESS_SECRET_BASE64)
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(JwtDecoder.class));
    }

    @Test
    void 키가_없으면_기동이_실패한다() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("railgate.auth.access-token.secret");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "not base64!!", "c2hvcnQ="})
    void 형식이_틀리거나_32바이트보다_짧으면_기동이_실패한다(String secret) {
        runner.withPropertyValues("railgate.auth.access-token.secret=" + secret).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("railgate.auth.access-token.secret");
            if (!secret.isBlank()) {
                assertThat(context.getStartupFailure()).rootCause().message().doesNotContain(secret.trim());
            }
        });
    }

    /** 같은 바이트만 반복한 키는 길이가 맞아도 약한 키다. */
    @Test
    void 한_바이트만_반복한_키는_거부한다() {
        String weak = Base64.getEncoder().encodeToString(new byte[32]);
        runner.withPropertyValues("railgate.auth.access-token.secret=" + weak).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().message().doesNotContain(weak);
        });
    }
}
