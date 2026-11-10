package com.railgate.reservation.api.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.Optional;

/**
 * 검증을 마친 입장 토큰에서 얻은 <b>요청 범위의</b> 입장 정보 (ADR-015 §4). {@link AdmissionTokenFilter} 가 요청 속성에 둔다.
 *
 * <p>로그인 principal({@link AuthenticatedUser})을 바꾸지 않는다 — 신원은 액세스 토큰, 회차 권한은 이 값이다.
 * {@code userId} 는 필터가 이미 principal 과 같은지 확인했다. {@code saleEventId} 와 요청 좌석의 회차 대조는
 * 아직 하지 않는다 (Task 2J-D).
 */
public record AdmissionContext(long userId, long saleEventId) {

    static final String REQUEST_ATTRIBUTE = AdmissionContext.class.getName();

    public AdmissionContext {
        if (userId <= 0 || saleEventId <= 0) {
            throw new IllegalArgumentException("userId·saleEventId 는 양수다");
        }
    }

    /** 이 요청에서 검증된 입장 정보. 입장 검증을 거치지 않은 경로면 빈 값이다. */
    public static Optional<AdmissionContext> from(HttpServletRequest request) {
        Objects.requireNonNull(request, "request");
        return request.getAttribute(REQUEST_ATTRIBUTE) instanceof AdmissionContext context
                ? Optional.of(context) : Optional.empty();
    }
}
