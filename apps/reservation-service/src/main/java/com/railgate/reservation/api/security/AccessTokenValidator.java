package com.railgate.reservation.api.security;

import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 액세스 토큰의 claim 규칙 (ADR-015 §3). 서명·알고리즘·{@code typ} 는 디코더가 먼저 확인한다 (ADR-016).
 *
 * <ul>
 *   <li>{@code iss} = 설정한 issuer, {@code aud} 에 설정한 audience 포함, {@code token_use} = {@code access}</li>
 *   <li>{@code sub} — <b>문자열</b>이고 앞자리 0 없는 양수 {@code long} 10진수</li>
 *   <li>{@code jti} — 비어 있지 않은 문자열</li>
 *   <li>타입 검사는 <b>원래 JSON 의 값</b>으로 한다. Nimbus 는 숫자 {@code sub} 를 문자열로 정규화해 넘기므로, 서명 검증을
 *       마친 토큰의 페이로드를 같은 라이브러리로 다시 읽어 원래 타입을 본다 (직접 파싱하지 않는다)</li>
 *   <li>{@code iat}·{@code exp} 필수. {@code iat ≤ now + skew}, {@code iat < exp}, {@code exp − iat ≤ maxLifetime},
 *       {@code now ≤ exp + skew}. {@code nbf} 가 있으면 {@code nbf ≤ now + skew}</li>
 *   <li>{@code nbf} 는 선택이지만 <b>있으면 원래 JSON 값</b>이 정수 숫자(초)이고 {@link Instant} 범위 안이어야 한다. 잘못된 값을
 *       없음으로 취급하지 않는다 — 라이브러리 변환이 범위 밖 값을 넘침으로 다른 시각으로 바꾸거나 소수를 잘라 받아들이기 때문이다</li>
 * </ul>
 *
 * <p>시각은 주입한 {@link Clock} 으로 판단한다. 토큰은 DB 를 거치지 않고 검증해야 하므로 규칙 7(DB {@code NOW(3)})의
 * 대상이 아니다 (ADR-015 §3). 실패 사유는 로그·응답에 내보내지 않는다 — 진입점이 고정 문구로 응답한다.
 */
public final class AccessTokenValidator implements OAuth2TokenValidator<Jwt> {

    static final String TOKEN_USE = "token_use";
    static final String ACCESS = "access";

    /** 앞자리 0 없는 1~19자리 10진수. 범위는 {@link Long#parseLong} 이 마저 확인한다. */
    private static final Pattern POSITIVE_DECIMAL = Pattern.compile("[1-9][0-9]{0,18}");

    private static final OAuth2TokenValidatorResult INVALID =
            OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));

    private final String issuer;
    private final String audience;
    private final Duration clockSkew;
    private final Duration maxLifetime;
    private final Clock clock;

    public AccessTokenValidator(
            String issuer, String audience, Duration clockSkew, Duration maxLifetime, Clock clock) {
        this.issuer = requireText(issuer, "issuer");
        this.audience = requireText(audience, "audience");
        this.clockSkew = requireNonNegative(clockSkew, "clockSkew");
        this.maxLifetime = requirePositive(maxLifetime, "maxLifetime");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        return isValid(jwt) ? OAuth2TokenValidatorResult.success() : INVALID;
    }

    private boolean isValid(Jwt jwt) {
        Map<String, Object> raw = rawClaims(jwt);
        return raw != null
                && issuer.equals(jwt.getClaims().get("iss"))
                && hasAudience(jwt.getClaims().get("aud"))
                && ACCESS.equals(raw.get(TOKEN_USE))
                && isUserId(raw.get("sub"))
                && isNonBlankString(raw.get("jti"))
                && hasValidTimes(jwt, raw);
    }

    /** 서명 검증을 마친 토큰의 원래 claim. 다시 읽지 못하면 {@code null} — 거부한다. */
    private static Map<String, Object> rawClaims(Jwt jwt) {
        try {
            return SignedJWT.parse(jwt.getTokenValue()).getPayload().toJSONObject();
        } catch (ParseException | RuntimeException unreadable) {
            return null;
        }
    }

    private boolean hasAudience(Object aud) {
        return aud instanceof List<?> values && values.contains(audience);
    }

    static boolean isUserId(Object sub) {
        if (!(sub instanceof String text) || !POSITIVE_DECIMAL.matcher(text).matches()) {
            return false;
        }
        try {
            return Long.parseLong(text) > 0;
        } catch (NumberFormatException overflow) {
            return false;
        }
    }

    private static boolean isNonBlankString(Object value) {
        return value instanceof String text && !text.isBlank();
    }

    private boolean hasValidTimes(Jwt jwt, Map<String, Object> raw) {
        Instant iat = jwt.getIssuedAt();
        Instant exp = jwt.getExpiresAt();
        if (iat == null || exp == null) {
            return false;
        }
        // nbf 는 선택이지만 "없음"과 "잘못된 값"은 다르다. 라이브러리 변환은 범위 밖 값을 다른 시각으로 바꾸거나(초×1000 넘침)
        // 소수를 잘라 받아들이므로 원래 JSON 값으로 판정한다.
        Instant nbf = null;
        if (raw.containsKey("nbf")) {
            nbf = epochSeconds(raw.get("nbf"));
            if (nbf == null) {
                return false;
            }
        }
        Instant now = clock.instant();
        return !iat.isAfter(now.plus(clockSkew))
                && iat.isBefore(exp)
                && !Duration.between(iat, exp).minus(maxLifetime).isPositive()
                && !now.isAfter(exp.plus(clockSkew))
                && (nbf == null || !nbf.isAfter(now.plus(clockSkew)));
    }

    /**
     * 정수 JSON 숫자(초)이고 {@link Instant} 표현 범위 안이면 그 시각, 아니면 {@code null}. 문자열·소수·그 밖의 타입은 받지 않는다.
     * 범위는 변환 전에 비교한다 — 예외를 잡지 않는다.
     */
    static Instant epochSeconds(Object value) {
        long seconds;
        if (value instanceof Long l) {
            seconds = l;
        } else if (value instanceof Integer i) {
            seconds = i;
        } else {
            return null;
        }
        if (seconds < Instant.MIN.getEpochSecond() || seconds > Instant.MAX.getEpochSecond()) {
            return null;
        }
        return Instant.ofEpochSecond(seconds);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 는 비어 있을 수 없다");
        }
        return value;
    }

    private static Duration requireNonNegative(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " 는 음수일 수 없다");
        }
        return value;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " 는 양수여야 한다");
        }
        return value;
    }
}
