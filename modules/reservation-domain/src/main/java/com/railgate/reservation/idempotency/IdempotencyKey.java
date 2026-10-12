package com.railgate.reservation.idempotency;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 클라이언트가 보낸 {@code Idempotency-Key} 값.
 *
 * <p><b>바이트 단위로 같아야 같은 키다.</b> 대소문자를 구분하고, 앞뒤 공백을 다듬지 않으며,
 * 유니코드 정규화도 하지 않는다. 서버가 값을 "고쳐서" 같게 만들면 클라이언트가 서로 다르다고 믿는
 * 두 요청이 결과를 공유한다. 그래서 허용 문자를 ASCII 영숫자·{@code -}·{@code _} 로 좁혀 정규화가
 * 필요한 입력 자체를 거부한다. 저장 열도 {@code ascii_bin} 이다 ({@code V7__idempotency_key.sql}).
 *
 * <p>길이 8~64 는 UUID(36자)를 담고 우연한 충돌이 쉬운 짧은 키를 막기 위한 <b>가정</b>이다
 * (IDEMPOTENCY_CONTRACT.md §1).
 */
public record IdempotencyKey(String value) {

    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    public IdempotencyKey {
        Objects.requireNonNull(value, "idempotency key 는 null 일 수 없다 (누락은 400)");
        if (!ALLOWED.matcher(value).matches()) {
            throw new IllegalArgumentException("idempotency key 는 ASCII 영숫자·'-'·'_' 8~64자여야 한다");
        }
    }
}
