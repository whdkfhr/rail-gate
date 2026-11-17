package com.railgate.queuetoken;

import java.time.Instant;
import java.util.Objects;

/**
 * 검증을 마친 입장 토큰의 내용 (ADR-015 §4). 이 값이 있다는 것 자체가 서명·용도·시각 검증을 통과했다는 뜻이다.
 *
 * @param userId      {@code sub} — 입장한 사용자. 로그인 액세스 토큰의 사용자와 대조해야 한다 (ADR-015 §5)
 * @param saleEventId {@code evt} — 입장한 판매 회차. {@code sale_event.id} 와 같은 식별자 공간이다 (D-A3)
 * @param issuedAt    {@code iat}
 * @param expiresAt   {@code exp}
 * @param tokenId     {@code jti}
 */
public record AdmissionClaims(long userId, long saleEventId, Instant issuedAt, Instant expiresAt, String tokenId) {

    public AdmissionClaims {
        if (userId <= 0 || saleEventId <= 0) {
            throw new IllegalArgumentException("userId·saleEventId 는 양수다");
        }
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(tokenId, "tokenId");
    }
}
