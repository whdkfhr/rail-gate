package com.railgate.reservation.config;

import com.railgate.queuetoken.AdmissionTokenKey;
import com.railgate.queuetoken.AdmissionTokenPolicy;
import com.railgate.queuetoken.AdmissionTokenVerifier;
import com.railgate.reservation.api.security.AccessTokenProperties;
import com.railgate.reservation.api.security.AdmissionTokenProperties;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 입장 토큰 검증기 (ADR-015 §4, Task 2J-C). 검증은 {@code modules/queue-token} 이 네트워크 없이 한다.
 *
 * <p><b>기동 시 거부</b> — {@code K_admission} 이 없거나 Base64 가 아니거나 32바이트 미만이거나 한 바이트만 반복하면,
 * 그리고 <b>디코딩한 {@code K_access} 와 같으면</b> 컨텍스트가 뜨지 않는다. 두 키가 같으면 용도 claim 외에는 두 토큰을
 * 구별할 수 없다 (ADR-015 §5 의 첫 번째 방어선). 정책 값이 잘못돼도 실패한다. 오류 메시지에 키 값을 넣지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AdmissionTokenProperties.class)
public class AdmissionTokenConfig {

    static final String PREFIX = "railgate.auth.admission-token";
    static final String SECRET_PROPERTY = PREFIX + ".secret";

    @Bean
    public AdmissionTokenVerifier admissionTokenVerifier(
            AdmissionTokenProperties properties, AccessTokenProperties accessProperties, Clock clock) {
        AdmissionTokenKey key;
        try {
            key = AdmissionTokenKey.fromBase64(SECRET_PROPERTY, properties.getSecret());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException(invalid.getMessage());
        }
        if (key.sameBytesAs(AccessTokenDecoderConfig.decodeKey(accessProperties.getSecret()))) {
            throw new IllegalStateException(SECRET_PROPERTY + " 와 railgate.auth.access-token.secret 가 같다 — 용도별로 다른 키여야 한다");
        }
        AdmissionTokenPolicy policy;
        try {
            policy = new AdmissionTokenPolicy(properties.getIssuer(), properties.getAudience(),
                    properties.getClockSkew(), properties.getMaxLifetime());
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new IllegalStateException(PREFIX + " 설정이 잘못됐다: " + invalid.getMessage());
        }
        try {
            return new AdmissionTokenVerifier(key, policy, clock);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException(PREFIX + " 설정이 잘못됐다: " + invalid.getMessage());
        }
    }
}
