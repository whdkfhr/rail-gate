package com.railgate.queuetoken;

import java.security.MessageDigest;
import java.util.Base64;
import java.util.Objects;

/**
 * 입장 토큰 서명키 {@code K_admission} (ADR-015 §4). Base64 로 받은 HS256 키다. <b>기본값이 없다.</b>
 *
 * <p>품질 기준은 액세스 토큰 키와 같다 — 비어 있지 않고, Base64 이며, 32바이트(256비트) 이상이고, 한 바이트만 반복하지 않는다.
 * 어기면 {@link IllegalArgumentException} 이며 메시지에 키 값을 넣지 않는다.
 * 액세스 토큰 키와 같은 바이트인지는 {@link #sameBytesAs} 로 호출자(앱 설정)가 확인한다.
 */
public final class AdmissionTokenKey {

    static final int MIN_KEY_BYTES = 32;

    private final byte[] bytes;

    private AdmissionTokenKey(byte[] bytes) {
        this.bytes = bytes;
    }

    /**
     * @param propertyName 오류 메시지에 쓸 설정 이름 (키 값은 쓰지 않는다)
     */
    public static AdmissionTokenKey fromBase64(String propertyName, String secret) {
        Objects.requireNonNull(propertyName, "propertyName");
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException(propertyName + " 가 설정되지 않았다 — 외부 설정으로 HS256 키를 주어야 한다");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(secret.trim());
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalArgumentException(propertyName + " 는 Base64 여야 한다");
        }
        if (key.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException(propertyName + " 는 " + MIN_KEY_BYTES + "바이트 이상이어야 한다");
        }
        if (isSingleByteRepeated(key)) {
            throw new IllegalArgumentException(propertyName + " 는 한 바이트만 반복한 약한 키일 수 없다");
        }
        return new AdmissionTokenKey(key);
    }

    /** 다른 키(예: 액세스 토큰 키)와 바이트가 같은가. 상수 시간 비교다. */
    public boolean sameBytesAs(byte[] other) {
        return other != null && MessageDigest.isEqual(bytes, other);
    }

    byte[] bytes() {
        return bytes.clone();
    }

    private static boolean isSingleByteRepeated(byte[] key) {
        for (byte b : key) {
            if (b != key[0]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return "AdmissionTokenKey[****]";
    }
}
