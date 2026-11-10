package com.railgate.reservation.api.security;

import java.util.List;
import java.util.Objects;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/**
 * 검증을 마친 액세스 토큰의 인증 객체. 주체는 {@link AuthenticatedUser} 다.
 *
 * <p><b>자격 증명(토큰 원문)을 보관하지 않는다</b> — {@link #getCredentials()} 는 언제나 {@code null} 이다.
 * 권한(authority)은 아직 없다. 입장 권한은 별도 토큰(ADR-015 §4)이다.
 */
public final class AccessTokenAuthentication extends AbstractAuthenticationToken {

    private static final long serialVersionUID = 1L;

    /**
     * 직렬화하지 않는다. 필터 체인이 {@code STATELESS} 라 보안 컨텍스트가 세션에 저장되지 않는다.
     *
     * <p>{@code transient} 로 두지 않는 이유 — 나중에 세션 저장을 켜면 조용히 {@code null} 이 되는 대신
     * {@code NotSerializableException} 으로 즉시 드러나는 편이 안전하다. 그때는 주체 타입을 함께 다시 정한다.
     */
    @SuppressWarnings("serial")
    private final AuthenticatedUser user;

    public AccessTokenAuthentication(AuthenticatedUser user) {
        super(List.of());
        this.user = Objects.requireNonNull(user, "user");
        setAuthenticated(true);
    }

    @Override
    public AuthenticatedUser getPrincipal() {
        return user;
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public String getName() {
        return Long.toString(user.userId().value());
    }
}
