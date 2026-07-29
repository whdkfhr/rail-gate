package com.railgate.reservation;

/**
 * 예매 사용자 식별자.
 *
 * <p>식별자를 컨텍스트 루트에 두는 이유: {@code seat} 와 {@code hold} 가 서로를 참조하면
 * 패키지 순환이 생긴다. 여러 하위 패키지가 함께 쓰는 식별자만 여기에 둔다.
 */
public record UserId(long value) {

    public UserId {
        if (value <= 0) {
            throw new IllegalArgumentException("userId 는 양수여야 한다: " + value);
        }
    }
}
