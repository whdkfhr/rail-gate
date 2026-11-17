package com.railgate.queuetoken;

import java.time.Duration;
import java.util.Objects;

/**
 * 입장 토큰 검증 규칙 (ADR-015 §4). 값이 잘못되면 생성 시 거부한다 — 앱은 기동 단계에서 실패한다.
 *
 * <p>{@link #DEFAULT_CLOCK_SKEW}(30초)와 {@link #DEFAULT_MAX_LIFETIME}(10분)은 <b>측정 근거가 없는 가정값</b>이다
 * (ADR-015 D-A4 미결정). 최대 수명은 P-7 의 Active 세션 TTL(수령 후 10분)을 넘지 않게 잡았다.
 */
public record AdmissionTokenPolicy(String issuer, String audience, Duration clockSkew, Duration maxLifetime) {

    public static final String DEFAULT_ISSUER = "railgate-queue";
    public static final String DEFAULT_AUDIENCE = "railgate-reservation";
    public static final Duration DEFAULT_CLOCK_SKEW = Duration.ofSeconds(30);
    public static final Duration DEFAULT_MAX_LIFETIME = Duration.ofMinutes(10);

    public AdmissionTokenPolicy {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("issuer 는 비어 있을 수 없다");
        }
        if (audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("audience 는 비어 있을 수 없다");
        }
        Objects.requireNonNull(clockSkew, "clockSkew");
        Objects.requireNonNull(maxLifetime, "maxLifetime");
        if (clockSkew.isNegative()) {
            throw new IllegalArgumentException("clockSkew 는 음수일 수 없다");
        }
        if (maxLifetime.isNegative() || maxLifetime.isZero()) {
            throw new IllegalArgumentException("maxLifetime 은 양수여야 한다");
        }
    }

    public static AdmissionTokenPolicy defaults() {
        return new AdmissionTokenPolicy(DEFAULT_ISSUER, DEFAULT_AUDIENCE, DEFAULT_CLOCK_SKEW, DEFAULT_MAX_LIFETIME);
    }
}
