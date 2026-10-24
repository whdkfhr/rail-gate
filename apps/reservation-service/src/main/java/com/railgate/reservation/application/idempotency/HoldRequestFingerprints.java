package com.railgate.reservation.application.idempotency;

import com.railgate.reservation.application.hold.HoldSeatsCommand;
import com.railgate.reservation.idempotency.RequestFingerprint;
import com.railgate.reservation.seat.SeatId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 선점 요청의 fingerprint 계산 (IDEMPOTENCY_CONTRACT.md §2).
 *
 * <pre>
 *   SHA-256( "hold-seats/v1:" + 오름차순 정렬한 seatId 를 ',' 로 이은 문자열 )
 * </pre>
 *
 * <ul>
 *   <li><b>좌석 순서를 정규화한다</b> — 숫자 오름차순. 선점 결과는 순서와 무관하고 저장소도 PK 순으로 잠근다</li>
 *   <li><b>중복 좌석은 여기서 다루지 않는다</b> — {@link HoldSeatsCommand} 가 만들어지지 않으므로 키를 확보하기
 *       전에 거부된다. 키를 소비하지 않는다</li>
 *   <li><b>사용자·판매 회차는 넣지 않는다</b> — 사용자는 키 범위의 일부이고, 회차는 서버가 좌석에서 해석한다</li>
 * </ul>
 *
 * <p>정규화 규칙을 바꾸면 접두사의 버전을 올린다. 옛 fingerprint 와 섞이지 않게 하기 위해서다.
 */
final class HoldRequestFingerprints {

    static final String VERSION = "hold-seats/v1";

    private HoldRequestFingerprints() {
    }

    static RequestFingerprint of(HoldSeatsCommand command) {
        Objects.requireNonNull(command, "command");
        String canonical = VERSION + ":" + command.seatIds().stream()
                .sorted()
                .map(SeatId::value)
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.US_ASCII));
            return new RequestFingerprint(HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 은 모든 JDK 가 제공해야 한다", e);
        }
    }
}
