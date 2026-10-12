package com.railgate.reservation.idempotency;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 같은 키에 실린 요청이 최초 요청과 같은지 판별하는 값. SHA-256 소문자 16진수 64자다.
 *
 * <p><b>계산은 이 타입의 책임이 아니다.</b> 무엇을 정규화해 넣을지(선점이면 정렬한 좌석 id)는
 * 요청을 아는 애플리케이션 계층이 정한다 (IDEMPOTENCY_CONTRACT.md §2). 저장소는 값을 비교 없이
 * 저장하고 돌려줄 뿐이며, 불일치 판정도 호출자가 한다.
 */
public record RequestFingerprint(String value) {

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    public RequestFingerprint {
        Objects.requireNonNull(value, "fingerprint");
        if (!SHA256_HEX.matcher(value).matches()) {
            throw new IllegalArgumentException("fingerprint 는 SHA-256 소문자 16진수 64자여야 한다");
        }
    }
}
