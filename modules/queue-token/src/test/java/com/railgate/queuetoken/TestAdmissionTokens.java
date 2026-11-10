package com.railgate.queuetoken;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** 테스트 전용 입장 토큰 생성기. 운영 소스에는 발급기가 없다. */
final class TestAdmissionTokens {

    static final String ADMISSION_SECRET = "4Z1Fd3f097EqGbOfCBdHZeMVS/k25l6FUKi5I2j8o7U=";
    static final String OTHER_SECRET = "MLWBrSmySFpUloaWxjK/uUuLdq7mrDs3NRXq51wG3yE=";

    private TestAdmissionTokens() {
    }

    static final class Spec {
        String algorithm = "HS256";
        String typ = "admission+jwt";
        String secret = ADMISSION_SECRET;
        final Map<String, Object> claims = new LinkedHashMap<>();

        Spec(Instant now, long userId, long saleEventId) {
            claims.put("iss", "railgate-queue");
            claims.put("aud", List.of("railgate-reservation"));
            claims.put("sub", Long.toString(userId));
            claims.put("evt", Long.toString(saleEventId));
            claims.put("iat", now.getEpochSecond());
            claims.put("exp", now.plusSeconds(300).getEpochSecond());
            claims.put("jti", "jti-" + userId + "-" + now.getEpochSecond());
            claims.put("token_use", "admission");
        }

        Spec claim(String name, Object value) {
            claims.put(name, value);
            return this;
        }

        Spec without(String name) {
            claims.remove(name);
            return this;
        }
    }

    static String token(Instant now, long userId, long saleEventId, Consumer<Spec> customizer) {
        Spec spec = new Spec(now, userId, saleEventId);
        customizer.accept(spec);
        try {
            JWSAlgorithm alg = JWSAlgorithm.parse(spec.algorithm);
            JWSHeader.Builder header = new JWSHeader.Builder(alg);
            if (spec.typ != null) {
                header.type(new JOSEObjectType(spec.typ));
            }
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder();
            spec.claims.forEach(claims::claim);
            SignedJWT jwt = new SignedJWT(header.build(), claims.build());
            if (JWSAlgorithm.Family.RSA.contains(alg)) {
                jwt.sign(new RSASSASigner(new RSAKeyGenerator(2048).generate()));
            } else {
                byte[] key = Base64.getDecoder().decode(spec.secret);
                if (alg.equals(JWSAlgorithm.HS384) || alg.equals(JWSAlgorithm.HS512)) {
                    byte[] doubled = new byte[key.length * 2];
                    System.arraycopy(key, 0, doubled, 0, key.length);
                    System.arraycopy(key, 0, doubled, key.length, key.length);
                    key = doubled;
                }
                jwt.sign(new MACSigner(key));
            }
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code alg=none} 의 서명 없는 토큰. */
    static String unsigned(Instant now, long userId, long saleEventId) {
        Spec spec = new Spec(now, userId, saleEventId);
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder();
        spec.claims.forEach(claims::claim);
        return b64("{\"alg\":\"none\",\"typ\":\"admission+jwt\"}") + "." + b64(claims.build().toString()) + ".";
    }

    static String tampered(String token) {
        int last = token.length() - 2;
        char c = token.charAt(last);
        return token.substring(0, last) + (c == 'A' ? 'B' : 'A') + token.substring(last + 1);
    }

    private static String b64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
