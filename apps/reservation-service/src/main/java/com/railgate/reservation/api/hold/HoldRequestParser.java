package com.railgate.reservation.api.hold;

import com.railgate.reservation.idempotency.IdempotencyKey;
import com.railgate.reservation.seat.SeatId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 선점 요청의 헤더·본문 검증 (HOLD_HTTP_CONTRACT §4·§5). 통과하지 못한 요청은 키를 확보하기 전에 거부된다 — DB 를 건드리지 않는다.
 *
 * <ul>
 *   <li><b>{@code Idempotency-Key}</b> — 헤더 필드가 정확히 하나이고 {@link IdempotencyKey} 형식이어야 한다. 다듬거나 정규화하지 않는다</li>
 *   <li><b>본문</b> — 객체 하나에 {@code seatIds} 필드 하나. 요소는 1 이상 {@code long} 범위의 <b>JSON 정수</b>
 *       1~4개이고 중복이 없어야 한다. 문자열·소수·지수 표기 숫자, 알려지지 않은 필드, 같은 이름의 필드 반복, 뒤따르는 토큰을 거부한다.
 *       라이브러리의 타입 변환(문자열·소수 → 정수)을 쓰지 않으려고 트리로 읽어 직접 검사한다</li>
 *   <li><b>크기</b> — {@value #MAX_BODY_BYTES} 바이트를 넘는 본문은 읽지 않고 거부한다 [제안 수치]</li>
 * </ul>
 *
 * <p>좌석 수 상한(4)과 중복 금지는 {@code HoldSeatsCommand}·{@code SeatCount} 도 검사한다 — 여기서는 400 을 내기 위해 먼저 본다.
 */
final class HoldRequestParser {

    static final int MAX_BODY_BYTES = 4096;
    static final String SEAT_IDS = "seatIds";
    private static final int MAX_SEATS = 4;

    private static final JsonMapper STRICT = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private HoldRequestParser() {
    }

    /** 헤더 값 목록(같은 이름의 필드마다 하나)에서 멱등키를 만든다. 없거나 둘 이상이거나 형식이 틀리면 빈 값. */
    static Optional<IdempotencyKey> idempotencyKey(List<String> headerValues) {
        if (headerValues == null || headerValues.size() != 1) {
            return Optional.empty();
        }
        try {
            return Optional.of(new IdempotencyKey(headerValues.getFirst()));
        } catch (IllegalArgumentException | NullPointerException invalid) {
            return Optional.empty();
        }
    }

    /** {@code application/json} (매개변수는 무시) 이면 참. 와일드카드·다른 하위 타입·{@code +json} 은 받지 않는다. */
    static boolean isJson(String contentType) {
        if (contentType == null) {
            return false;
        }
        int semicolon = contentType.indexOf(';');
        String mediaType = (semicolon < 0 ? contentType : contentType.substring(0, semicolon)).trim();
        return mediaType.toLowerCase(Locale.ROOT).equals("application/json");
    }

    /** 본문에서 좌석 id 를 요청 순서대로 읽는다. 계약 위반이면 빈 값. */
    static Optional<List<SeatId>> seatIds(byte[] body) {
        if (body == null || body.length == 0 || body.length > MAX_BODY_BYTES) {
            return Optional.empty();
        }
        JsonNode root;
        try {
            root = STRICT.readTree(body);
        } catch (JacksonException malformed) {
            return Optional.empty();
        }
        if (root == null || !root.isObject() || root.size() != 1) {
            return Optional.empty();
        }
        JsonNode array = root.get(SEAT_IDS);
        if (array == null || !array.isArray() || array.isEmpty() || array.size() > MAX_SEATS) {
            return Optional.empty();
        }
        List<SeatId> seats = new ArrayList<>(array.size());
        Set<Long> seen = new HashSet<>();
        for (JsonNode element : array) {
            if (!element.isIntegralNumber() || !element.canConvertToLong()) {
                return Optional.empty();
            }
            long value = element.longValue();
            if (value < 1 || !seen.add(value)) {
                return Optional.empty();
            }
            seats.add(new SeatId(value));
        }
        return Optional.of(List.copyOf(seats));
    }
}
