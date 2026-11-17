package com.railgate.queuetoken;

import com.nimbusds.jose.Algorithm;
import com.nimbusds.jose.Header;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jose.util.Base64URL;
import java.text.ParseException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 입장 토큰 검증기 (ADR-015 §4). <b>네트워크 없이</b> 서명만으로 판정한다 — queue-service·Redis 를 부르지 않는다
 * (절대 규칙 4, I-7). Spring·Servlet 에 의존하지 않는다. 서명 검증과 JSON 파싱은 Nimbus JOSE+JWT 가 한다.
 *
 * <h2>검사 순서 (먼저 걸린 사유 하나만 돌려준다)</h2>
 *
 * <ol>
 *   <li>JWS 형식 ({@link AdmissionRejection#MALFORMED}) — 서명 없는 {@code alg=none} 은 {@link AdmissionRejection#ALGORITHM}</li>
 *   <li>{@code alg = HS256} 만 ({@link AdmissionRejection#ALGORITHM})</li>
 *   <li>{@code typ = admission+jwt} ({@link AdmissionRejection#TYPE})</li>
 *   <li>서명 — {@code K_admission} ({@link AdmissionRejection#SIGNATURE})</li>
 *   <li>{@code iss}·{@code aud}·{@code token_use} ({@link AdmissionRejection#PURPOSE})</li>
 *   <li>필수 claim 의 존재와 <b>원래 JSON 타입</b> ({@link AdmissionRejection#CLAIMS})</li>
 *   <li>시각 — 수명·미래·만료</li>
 * </ol>
 *
 * <h2>claim 의 타입 계약</h2>
 *
 * <ul>
 *   <li>{@code sub}·{@code evt} — <b>문자열</b>, 앞자리 0 없는 양수 {@code long} 10진수. 숫자 타입은 받지 않는다
 *       ({@code evt} 표현의 근거는 TASK-002J-C §2)</li>
 *   <li>{@code jti} — 비어 있지 않은 문자열</li>
 *   <li>{@code iat}·{@code exp}·{@code nbf} — 정수 JSON 숫자(초)이고 {@link Instant} 표현 범위 안. 소수·문자열·범위 밖 값은
 *       {@link AdmissionRejection#CLAIMS}. {@code nbf} 는 선택</li>
 *   <li>{@code aud} — 문자열 하나 또는 문자열 배열 (JWT 규격)</li>
 * </ul>
 *
 * <p>시각은 주입한 {@link Clock} 으로 판단한다. 토큰은 DB 를 거치지 않고 검증하므로 규칙 7 의 대상이 아니다 (ADR-015 §3).
 */
public final class AdmissionTokenVerifier {

    public static final String TOKEN_TYPE = "admission+jwt";
    public static final String TOKEN_USE = "admission";

    private static final JOSEObjectType TYPE = new JOSEObjectType(TOKEN_TYPE);
    private static final Pattern POSITIVE_DECIMAL = Pattern.compile("[1-9][0-9]{0,18}");
    private static final long MIN_EPOCH_SECOND = Instant.MIN.getEpochSecond();
    private static final long MAX_EPOCH_SECOND = Instant.MAX.getEpochSecond();

    private final MACVerifier signature;
    private final AdmissionTokenPolicy policy;
    private final Clock clock;

    public AdmissionTokenVerifier(AdmissionTokenKey key, AdmissionTokenPolicy policy, Clock clock) {
        Objects.requireNonNull(key, "key");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
        try {
            this.signature = new MACVerifier(key.bytes());
        } catch (JOSEException e) {
            throw new IllegalArgumentException("입장 토큰 키로 HS256 검증기를 만들 수 없다");
        }
        requireSkewApplicable(clock.instant(), policy.clockSkew());
    }

    /**
     * 설정 검증 — 현재 시각에 시계 오차를 더하고 뺄 수 있어야 한다. 그렇지 않은 설정은 모든 요청에서 예외를 내므로
     * 토큰 오류가 아니라 <b>기동 실패</b>로 드러낸다. 토큰 입력과 무관한 검사다.
     */
    private static void requireSkewApplicable(Instant now, Duration clockSkew) {
        try {
            now.plus(clockSkew);
            now.minus(clockSkew);
        } catch (DateTimeException | ArithmeticException outOfRange) {
            throw new IllegalArgumentException("clockSkew 가 시각 표현 범위를 넘는다");
        }
    }

    public AdmissionVerification verify(String token) {
        if (token == null || token.isBlank()) {
            return reject(AdmissionRejection.MALFORMED);
        }
        JWSObject jws;
        try {
            jws = JWSObject.parse(token);
        } catch (ParseException notJws) {
            return reject(isUnsecured(token) ? AdmissionRejection.ALGORITHM : AdmissionRejection.MALFORMED);
        }
        if (!JWSAlgorithm.HS256.equals(jws.getHeader().getAlgorithm())) {
            return reject(AdmissionRejection.ALGORITHM);
        }
        if (!TYPE.equals(jws.getHeader().getType())) {
            return reject(AdmissionRejection.TYPE);
        }
        try {
            if (!jws.verify(signature)) {
                return reject(AdmissionRejection.SIGNATURE);
            }
        } catch (JOSEException | IllegalStateException unverifiable) {
            return reject(AdmissionRejection.SIGNATURE);
        }
        Map<String, Object> claims = jws.getPayload().toJSONObject();
        if (claims == null) {
            return reject(AdmissionRejection.MALFORMED);
        }
        return verifyClaims(claims);
    }

    private AdmissionVerification verifyClaims(Map<String, Object> claims) {
        if (!policy.issuer().equals(claims.get("iss"))
                || !hasAudience(claims.get("aud"))
                || !TOKEN_USE.equals(claims.get("token_use"))) {
            return reject(AdmissionRejection.PURPOSE);
        }
        Long userId = positiveDecimal(claims.get("sub"));
        Long saleEventId = positiveDecimal(claims.get("evt"));
        Object jti = claims.get("jti");
        Instant issuedAt = epochSeconds(claims.get("iat"));
        Instant expiresAt = epochSeconds(claims.get("exp"));
        boolean hasNbf = claims.containsKey("nbf");
        Instant notBefore = hasNbf ? epochSeconds(claims.get("nbf")) : null;
        if (userId == null || saleEventId == null || !(jti instanceof String id) || id.isBlank()
                || issuedAt == null || expiresAt == null || (hasNbf && notBefore == null)) {
            return reject(AdmissionRejection.CLAIMS);
        }

        // 시각 연산에서 토큰 값에는 덧셈·뺄셈을 하지 않는다 — 시계 오차는 현재 시각 쪽에 적용한다 (생성 시 적용 가능함을 확인).
        // Duration.between 은 Instant 범위 안의 두 값이면 넘치지 않는다.
        if (!issuedAt.isBefore(expiresAt) || Duration.between(issuedAt, expiresAt).compareTo(policy.maxLifetime()) > 0) {
            return reject(AdmissionRejection.LIFETIME);
        }
        Instant now = clock.instant();
        Instant latestAcceptableStart = now.plus(policy.clockSkew());
        if (issuedAt.isAfter(latestAcceptableStart) || (notBefore != null && notBefore.isAfter(latestAcceptableStart))) {
            return reject(AdmissionRejection.NOT_YET_VALID);
        }
        // now > exp + skew  ⇔  now - skew > exp (Instant 는 정확한 값이라 두 비교가 같다)
        if (now.minus(policy.clockSkew()).isAfter(expiresAt)) {
            return reject(AdmissionRejection.EXPIRED);
        }
        return new AdmissionVerification.Accepted(new AdmissionClaims(userId, saleEventId, issuedAt, expiresAt, id));
    }

    private boolean hasAudience(Object aud) {
        if (aud instanceof String single) {
            return policy.audience().equals(single);
        }
        if (aud instanceof List<?> values) {
            return values.stream().allMatch(String.class::isInstance) && values.contains(policy.audience());
        }
        return false;
    }

    /** 앞자리 0 없는 양수 long 10진 <b>문자열</b>이면 그 값, 아니면 {@code null}. 숫자 타입은 받지 않는다. */
    private static Long positiveDecimal(Object value) {
        if (!(value instanceof String text) || !POSITIVE_DECIMAL.matcher(text).matches()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException overflow) {
            return null;
        }
    }

    /**
     * 정수 JSON 숫자(초)이고 {@link Instant} 표현 범위 안이면 그 시각, 아니면 {@code null}. 소수·문자열은 받지 않는다.
     * 범위는 변환 전에 비교한다 — {@code Instant.ofEpochSecond} 의 예외를 잡지 않는다.
     */
    private static Instant epochSeconds(Object value) {
        long seconds;
        if (value instanceof Long l) {
            seconds = l;
        } else if (value instanceof Integer i) {
            seconds = i;
        } else {
            return null;
        }
        if (seconds < MIN_EPOCH_SECOND || seconds > MAX_EPOCH_SECOND) {
            return null;
        }
        return Instant.ofEpochSecond(seconds);
    }

    /** {@code alg=none} 의 서명 없는 JWT 인가 — 헤더만 해석한다. */
    private static boolean isUnsecured(String token) {
        int dot = token.indexOf('.');
        if (dot <= 0) {
            return false;
        }
        try {
            return Algorithm.NONE.equals(Header.parse(new Base64URL(token.substring(0, dot))).getAlgorithm());
        } catch (ParseException | RuntimeException notAHeader) {
            return false;
        }
    }

    private static AdmissionVerification reject(AdmissionRejection reason) {
        return new AdmissionVerification.Rejected(reason);
    }
}
