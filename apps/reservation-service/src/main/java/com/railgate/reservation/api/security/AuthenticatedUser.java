package com.railgate.reservation.api.security;

import com.railgate.reservation.UserId;
import java.util.Objects;

/**
 * 검증된 로그인 액세스 토큰의 사용자 (ADR-015 §3). HTTP 계층은 {@code @AuthenticationPrincipal} 로 이 값을 받는다.
 *
 * <p><b>사용자 id 의 유일한 출처다.</b> 요청 본문·쿼리의 사용자 id 를 믿지 않는다. 토큰 원문은 담지 않는다 (규칙 34).
 * 이 값은 신원만 증명한다 — 판매 회차 입장 권한(입장 토큰)은 별개이며 아직 검증하지 않는다.
 */
public record AuthenticatedUser(UserId userId) {

    public AuthenticatedUser {
        Objects.requireNonNull(userId, "userId");
    }
}
