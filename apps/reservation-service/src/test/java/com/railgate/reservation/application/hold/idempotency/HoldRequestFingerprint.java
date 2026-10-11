package com.railgate.reservation.application.hold.idempotency;

import com.railgate.reservation.application.hold.HoldSeatsCommand;
import com.railgate.reservation.seat.SeatId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 선점 요청의 fingerprint. 같은 키로 <b>다른 요청</b>이 왔는지 판별한다.
 *
 * <h2>포함하는 것</h2>
 *
 * <ul>
 *   <li><b>버전 접두사</b>({@code hold-seats/v1}) — 정규화 규칙이 바뀌면 옛 기록과 섞이지 않게</li>
 *   <li><b>좌석 id 집합</b> — <b>오름차순으로 정렬</b>한다. 선점 결과는 순서에 의존하지 않고
 *       저장소도 PK 오름차순으로 잠그므로 {@code [2,1]} 과 {@code [1,2]} 는 같은 요청이다</li>
 * </ul>
 *
 * <h2>포함하지 않는 것</h2>
 *
 * <ul>
 *   <li><b>사용자</b> — 이미 {@link IdempotencyScope} 의 일부다</li>
 *   <li><b>판매 회차</b> — 클라이언트 입력이 아니다. 서버가 좌석에서 해석한다</li>
 * </ul>
 *
 * <p><b>중복 좌석</b>은 정규화(중복 제거)하지 않는다. {@link HoldSeatsCommand} 가 만들어지지
 * 않으므로 키를 확보하기 전에 400 으로 끝난다 — 키를 소비하지 않는다.
 */
final class HoldRequestFingerprint {

    static final String VERSION = "hold-seats/v1";

    private HoldRequestFingerprint() {
    }

    /** SHA-256 16진수 64자. */
    static String of(HoldSeatsCommand command) {
        Objects.requireNonNull(command, "command");
        String canonical = VERSION + ":" + command.seatIds().stream()
                .sorted()
                .map(SeatId::value)
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 은 모든 JDK 가 제공해야 한다", e);
        }
    }
}
