package com.railgate.queuetoken;

/**
 * 입장 토큰을 거부한 사유. <b>저카디널리티 메트릭 태그</b>로만 쓰고 클라이언트 응답에는 내보내지 않는다 —
 * 응답은 사유와 무관하게 같은 401 이다.
 */
public enum AdmissionRejection {
    /** JWS 형식이 아니거나 페이로드가 JSON 객체가 아니다. */
    MALFORMED,
    /** {@code alg} 가 HS256 이 아니다. */
    ALGORITHM,
    /** {@code typ} 가 {@code admission+jwt} 가 아니다. */
    TYPE,
    /** 서명이 맞지 않는다. */
    SIGNATURE,
    /** {@code iss}·{@code aud}·{@code token_use} 가 입장 토큰의 것이 아니다. */
    PURPOSE,
    /** 필수 claim 이 없거나 원래 JSON 타입·범위가 틀렸다. */
    CLAIMS,
    /** {@code exp} 가 지났다 (시계 오차 포함). */
    EXPIRED,
    /** {@code iat}·{@code nbf} 가 미래다 (시계 오차 포함). */
    NOT_YET_VALID,
    /** {@code iat ≥ exp} 이거나 수명이 최대 수명을 넘는다. */
    LIFETIME
}
