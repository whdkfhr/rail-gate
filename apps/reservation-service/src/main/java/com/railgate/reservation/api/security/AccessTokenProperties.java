package com.railgate.reservation.api.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 액세스 토큰 검증 설정 (`railgate.auth.access-token.*`, ADR-015 §3).
 *
 * <h2>★ 서명키에 기본값이 없다</h2>
 *
 * <p>{@code secret} 은 Base64 로 인코딩한 HS256 키(32바이트 이상)다. <b>저장소·설정 파일에 운영 키를 두지 않고</b>
 * 환경 변수 등 외부 설정으로만 받는다. 없거나 형식이 틀리거나 약하면 기동이 실패한다.
 *
 * <h2>가정값</h2>
 *
 * <p>{@code clockSkew}(30초)와 {@code maxLifetime}(30분)은 <b>측정 근거가 없는 가정값</b>이다 (ADR-015 D-A4 미결정).
 */
@ConfigurationProperties(prefix = "railgate.auth.access-token")
public class AccessTokenProperties {

    /** Base64 HS256 키. 기본값 없음. */
    private String secret;

    private String issuer = "railgate-auth";

    private String audience = "railgate-api";

    /** 시계 오차 허용 (가정값). */
    private Duration clockSkew = Duration.ofSeconds(30);

    /** 허용하는 최대 수명 {@code exp − iat} (가정값). */
    private Duration maxLifetime = Duration.ofMinutes(30);

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
        return "AccessTokenProperties[issuer=" + issuer + ", audience=" + audience + ", secret=****]";
    }
}
