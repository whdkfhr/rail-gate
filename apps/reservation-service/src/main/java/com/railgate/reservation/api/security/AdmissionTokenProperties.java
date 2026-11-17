package com.railgate.reservation.api.security;

import com.railgate.queuetoken.AdmissionTokenPolicy;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 입장 토큰 검증 설정 (`railgate.auth.admission-token.*`, ADR-015 §4).
 *
 * <p>{@code secret}({@code K_admission})은 Base64 HS256 키다. <b>기본값이 없고</b> 액세스 토큰 키와 달라야 한다.
 * 저장소·설정 파일에 운영 키를 두지 않는다. {@code clockSkew}(30초)·{@code maxLifetime}(10분)은 <b>가정값</b>이다
 * (ADR-015 D-A4 미결정). 잘못된 값이면 기동이 실패한다.
 */
@ConfigurationProperties(prefix = "railgate.auth.admission-token")
public class AdmissionTokenProperties {

    private String secret;
    private String issuer = AdmissionTokenPolicy.DEFAULT_ISSUER;
    private String audience = AdmissionTokenPolicy.DEFAULT_AUDIENCE;
    private Duration clockSkew = AdmissionTokenPolicy.DEFAULT_CLOCK_SKEW;
    private Duration maxLifetime = AdmissionTokenPolicy.DEFAULT_MAX_LIFETIME;

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    public Duration getClockSkew() {
        return clockSkew;
    }

    public void setClockSkew(Duration clockSkew) {
        this.clockSkew = clockSkew;
    }

    public Duration getMaxLifetime() {
        return maxLifetime;
    }

    public void setMaxLifetime(Duration maxLifetime) {
        this.maxLifetime = maxLifetime;
    }

    /** {@code toString} 에 키가 새지 않게 한다. */
    @Override
    public String toString() {
        return "AdmissionTokenProperties[issuer=" + issuer + ", audience=" + audience + ", secret=****]";
    }
}
