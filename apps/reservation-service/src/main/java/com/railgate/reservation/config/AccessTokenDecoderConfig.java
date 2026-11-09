package com.railgate.reservation.config;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.SecurityContext;
import com.railgate.reservation.api.security.AccessTokenProperties;
import com.railgate.reservation.api.security.AccessTokenValidator;
import java.time.Clock;
import java.util.Base64;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * 로그인 액세스 토큰 디코더 (ADR-015 §3, ADR-016).
 *
 * <ul>
 *   <li><b>로컬 대칭키 검증만</b> 한다. {@code issuer-uri}·{@code jwk-set-uri} 를 쓰지 않으므로 요청·기동 중 원격 조회가 없다</li>
 *   <li>알고리즘은 HS256 하나로 고정한다 ({@code alg=none}·HS512·RS256 등은 서명 단계에서 거부)</li>
 *   <li>헤더 {@code typ} 는 {@code at+jwt} 만 받는다 — 입장 토큰({@code admission+jwt})·일반 {@code JWT} 를 거부한다</li>
 *   <li>{@code sub}·{@code jti} 의 타입은 검증기가 원래 JSON 값으로 확인한다 — 라이브러리의 문자열 정규화가 타입 오류를 가리지 않게</li>
 * </ul>
 *
 * <p><b>서명키는 기동 시 검증한다</b> — 없거나, Base64 가 아니거나, 32바이트(256비트) 미만이거나, 한 바이트만 반복하면
 * 컨텍스트가 뜨지 않는다. 오류 메시지에 키 값을 넣지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AccessTokenProperties.class)
public class AccessTokenDecoderConfig {

    static final String SECRET_PROPERTY = "railgate.auth.access-token.secret";
    private static final int MIN_KEY_BYTES = 32;

    /** 토큰 시각 판단용 시계. 테스트는 {@code @Primary} 시계로 바꾼다. */
    @Bean
    public Clock accessTokenClock() {
        return Clock.systemUTC();
    }

    @Bean
    public JwtDecoder accessTokenDecoder(AccessTokenProperties properties, Clock clock) {
        byte[] key = decodeKey(properties.getSecret());
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withSecretKey(new SecretKeySpec(key, "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256)
                .jwtProcessorCustomizer(processor -> processor.setJWSTypeVerifier(
                        new DefaultJOSEObjectTypeVerifier<SecurityContext>(new JOSEObjectType("at+jwt"))))
                .build();
        decoder.setJwtValidator(new AccessTokenValidator(
                properties.getIssuer(), properties.getAudience(),
                properties.getClockSkew(), properties.getMaxLifetime(), clock));
        return decoder;
    }

    static byte[] decodeKey(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(SECRET_PROPERTY + " 가 설정되지 않았다 — 외부 설정으로 HS256 키를 주어야 한다");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(secret.trim());
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalStateException(SECRET_PROPERTY + " 는 Base64 여야 한다");
        }
        if (key.length < MIN_KEY_BYTES) {
            throw new IllegalStateException(SECRET_PROPERTY + " 는 " + MIN_KEY_BYTES + "바이트 이상이어야 한다");
        }
        if (isSingleByteRepeated(key)) {
            throw new IllegalStateException(SECRET_PROPERTY + " 는 한 바이트만 반복한 약한 키일 수 없다");
        }
        return key;
    }

    private static boolean isSingleByteRepeated(byte[] key) {
        for (byte b : key) {
            if (b != key[0]) {
                return false;
            }
        }
        return true;
    }
}
