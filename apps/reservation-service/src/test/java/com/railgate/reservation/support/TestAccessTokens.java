package com.railgate.reservation.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * <b>테스트 전용</b> 액세스 토큰 발급 도우미. 운영 소스에는 발급기가 없다 (ADR-015 §6 — 우회 경로를 두지 않는다).
 *
 * <p>키는 테스트만을 위한 고정값이다. 운영 키가 아니며 운영 설정에 쓰지 않는다.
 * 기본 claim 은 ADR-015 §3 의 정상 액세스 토큰이고, 테스트는 {@link Spec} 을 바꿔 하나씩 어긋나게 만든다.
 */
public final class TestAccessTokens {

    /** 테스트 전용 액세스 토큰 키 (32바이트, Base64). */
    public static final String ACCESS_SECRET_BASE64 = "MLWBrSmySFpUloaWxjK/uUuLdq7mrDs3NRXq51wG3yE=";

    /** 입장 토큰 키 자리에 쓰는 다른 테스트 키 — 용도별 키 분리 확인용. */
    public static final String OTHER_SECRET_BASE64 = "4Z1Fd3f097EqGbOfCBdHZeMVS/k25l6FUKi5I2j8o7U=";

    private TestAccessTokens() {
    }

    /** 토큰의 모든 구성. 기본값은 정상 액세스 토큰이다. */
    public static final class Spec {
        public String algorithm = "HS256";
        public String typ = "at+jwt";
        public String secretBase64 = ACCESS_SECRET_BASE64;
        /** {@code null} 이면 claim 을 넣지 않는다. */
        public final Map<String, Object> claims = new LinkedHashMap<>();

        Spec(Instant now, long userId) {
            claims.put("iss", "railgate-auth");
            claims.put("aud", List.of("railgate-api"));
            claims.put("sub", Long.toString(userId));
            claims.put("iat", Date.from(now));
            claims.put("exp", Date.from(now.plus(Duration.ofMinutes(10))));
            claims.put("jti", UUID.randomUUID().toString());
            claims.put("token_use", "access");
        }

        public Spec claim(String name, Object value) {
            claims.put(name, value);
            return this;
        }

        public Spec without(String name) {
            claims.remove(name);
            return this;
        }
    }

    public static String access(Instant now, long userId) {
        return access(now, userId, spec -> { });
    }

    public static String access(Instant now, long userId, Consumer<Spec> customizer) {
        Spec spec = new Spec(now, userId);
        customizer.accept(spec);
        return sign(spec);
    }

    /** {@code alg=none} 의 서명 없는 토큰. */
    public static String unsigned(Instant now, long userId) {
        Spec spec = new Spec(now, userId);
        String header = b64("{\"alg\":\"none\",\"typ\":\"at+jwt\"}");
        String payload = b64(toClaims(spec).toString());
        return header + "." + payload + ".";
    }

    /** 서명 부분의 한 글자를 바꾼 토큰. */
    public static String tampered(String token) {
        int last = token.length() - 2;
        char c = token.charAt(last);
        return token.substring(0, last) + (c == 'A' ? 'B' : 'A') + token.substring(last + 1);
    }

    private static String sign(Spec spec) {
        try {
            JWSAlgorithm alg = JWSAlgorithm.parse(spec.algorithm);
            JWSHeader.Builder header = new JWSHeader.Builder(alg);
            if (spec.typ != null) {
                header.type(new JOSEObjectType(spec.typ));
            }
            SignedJWT jwt = new SignedJWT(header.build(), toClaims(spec));
            if (JWSAlgorithm.Family.RSA.contains(alg)) {
                RSAKey rsa = new RSAKeyGenerator(2048).generate();
                jwt.sign(new RSASSASigner(rsa));
            } else {
                byte[] key = Base64.getDecoder().decode(spec.secretBase64);
                if (alg.equals(JWSAlgorithm.HS512) && key.length < 64) {
                    key = concat(key, key); // HS512 은 64바이트 이상 키가 필요하다
                }
                jwt.sign(new MACSigner(key));
            }
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JWTClaimsSet toClaims(Spec spec) {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder();
        spec.claims.forEach(builder::claim);
        return builder.build();
    }

    private static String b64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
