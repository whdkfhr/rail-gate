package com.railgate.reservation.api.hold;

import static org.assertj.core.api.Assertions.assertThat;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.api.trace.TraceIdFilter;
import com.railgate.reservation.application.hold.ReleaseHoldCommand;
import com.railgate.reservation.application.hold.ReleaseHoldService;
import com.railgate.reservation.support.SecuredWebTestSupport;
import com.railgate.reservation.support.TestAccessTokens;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * ★ Task 2J-D — <b>운영</b> {@code POST /api/reservations/holds} 를 내장 서버·실제 SecurityFilterChain·Micrometer·MySQL 로
 * 확인한다 (HOLD_HTTP_CONTRACT §9). 거절은 키·좌석·quota 를 바꾸지 않고, 재생은 상태·본문 <b>바이트</b>·{@code Location}·
 * {@code Content-Type} 이 같다. 바이트 비교를 JSON 객체 동등성으로 대신하지 않는다.
 *
 * <p>TX1 안의 회차 불일치(D4)와 커밋 결과 불명은 HTTP 요청만으로 주입할 수 없어 {@code HoldControllerFaultTest} 가 같은 컨트롤러를
 * 실제 MySQL 빈과 함께 직접 호출해 확인한다.
 */
@DisplayName("Task 2J-D — 선점 HTTP (SecurityFilterChain·MySQL)")
class HoldControllerIntegrationTest extends SecuredWebTestSupport {

    private static final String HOLDS = "/api/reservations/holds";
    private static final long USER = 5151L;
    private static final long OTHER_USER = 5252L;
    private static final long SCHEDULE = 8_900L;
    private static final long OTHER_EVENT = 9_800_000L;
    private static final long OTHER_SCHEDULE = 8_901L;

    @LocalServerPort
    private int port;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private ReleaseHoldService releaseHold;

    private final HttpClient http = HttpClient.newHttpClient();
    private ExecutorService pool;
    private int keySeq;

    @BeforeEach
    void reset() {
        jdbc().execute("DELETE FROM idempotency_key");
        pool = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    // ------------------------------------------------------------------ 픽스처

    /** 요청 하나의 구성. 기본값은 정상 요청이다. */
    private final class Req {
        String access = TestAccessTokens.access(NOW, USER);
        String admission = TestAccessTokens.admission(NOW, USER, FIXTURE_SALE_EVENT_ID);
        List<String> keys = List.of(nextKey());
        String contentType = "application/json";
        String body;

        Req seats(long... ids) {
            body = "{\"seatIds\":[" + java.util.Arrays.stream(ids).mapToObj(Long::toString)
                    .collect(Collectors.joining(",")) + "]}";
            return this;
        }

        Req key(String... values) {
            keys = List.of(values);
            return this;
        }

        Req user(long userId, long saleEventId) {
            access = TestAccessTokens.access(NOW, userId);
            admission = TestAccessTokens.admission(NOW, userId, saleEventId);
            return this;
        }

        HttpResponse<byte[]> send() throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + HOLDS))
                    .POST(body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (access != null) {
                request.header("Authorization", "Bearer " + access);
            }
            if (admission != null) {
                request.header("Admission-Token", admission);
            }
            for (String key : keys) {
                request.header("Idempotency-Key", key);
            }
            if (contentType != null) {
                request.header("Content-Type", contentType);
            }
            return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        }
    }

    private Req req() {
        return new Req();
    }

    private String nextKey() {
        return "http-key-" + (keySeq++) + "-xxxx";
    }

    private static String text(HttpResponse<byte[]> response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private static Map<String, Object> json(HttpResponse<byte[]> response) {
        return JsonParserFactory.getJsonParser().parseMap(text(response));
    }

    private static String traceId(HttpResponse<byte[]> response) {
        List<String> values = response.headers().allValues(TraceIdFilter.TRACE_ID_HEADER);
        assertThat(values).as("X-Trace-Id 하나").hasSize(1);
        return values.getFirst();
    }

    private long keyRows() {
        return jdbc().queryForObject("SELECT COUNT(*) FROM idempotency_key", Long.class);
    }

    private int coordinatorCalls() {
        return (int) meters.find("railgate.idempotency.hold.calls").counters().stream().mapToDouble(Counter::count).sum();
    }

    private double responses(String status, String code) {
        Counter counter = meters.find("railgate.hold.http.responses").tags("status", status, "code", code).counter();
        return counter == null ? 0 : counter.count();
    }

    private double serverErrors() {
        return meters.find("railgate.hold.http.responses").tags("category", "server_error").counters().stream()
                .mapToDouble(Counter::count).sum();
    }

    /** 재생되지 않는 오류의 고정 형식 — 본문 {code, message, traceId}, 본문 traceId = 헤더. */
    private static void assertError(HttpResponse<byte[]> response, int status, String code) {
        assertThat(response.statusCode()).as(text(response)).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).hasValue(HoldHttpResponse.CONTENT_TYPE);
        Map<String, Object> body = JsonParserFactory.getJsonParser().parseMap(text(response));
        assertThat(body).containsOnlyKeys("code", "message", "traceId")
                .containsEntry("code", code)
                .containsEntry("message", HoldErrorCode.valueOf(code).message)
                .containsEntry("traceId", traceId(response));
    }

    /** 거절은 조정자에 닿지 않고 키·좌석·quota 를 바꾸지 않는다. */
    private void assertUntouched(int callsBefore, long... seats) {
        assertThat(coordinatorCalls()).as("조정자 미호출").isEqualTo(callsBefore);
        assertThat(keyRows()).as("키 행 없음").isZero();
        for (long seat : seats) {
            assertThat(statusOf(seat)).as("좌석 불변").isEqualTo("AVAILABLE");
        }
        assertThat(quotaRowCount()).as("quota 불변").isZero();
    }

    /** 재생 범위(D1) — 상태·본문 바이트·Location·Content-Type 이 같고, X-Trace-Id 는 요청마다 다르다. */
    private static void assertSameReplay(HttpResponse<byte[]> first, HttpResponse<byte[]> again) {
        assertThat(again.statusCode()).isEqualTo(first.statusCode());
        assertThat(again.body()).as("본문 바이트 동일").isEqualTo(first.body());
        assertThat(again.headers().allValues("Location")).isEqualTo(first.headers().allValues("Location"));
        assertThat(again.headers().allValues("Content-Type")).isEqualTo(first.headers().allValues("Content-Type"));
        assertThat(traceId(again)).isNotEqualTo(traceId(first));
        assertThat(text(first)).doesNotContain("traceId").doesNotContain(traceId(first));
    }

    // ------------------------------------------------------------------ §1 성공과 재생

    @Nested
    @DisplayName("§1 ★ 성공·재생 (I1·I3·I6·T1·T3)")
    class 성공과_재생 {

        @Test
        void 성공은_201_저장_결과_본문_Location_이다() throws Exception {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long b = insertAvailableSeat(SCHEDULE, "1B");

            HttpResponse<byte[]> response = req().seats(b, a).send();

            assertThat(response.statusCode()).as(text(response)).isEqualTo(201);
            Map<String, Object> body = json(response);
            assertThat(body).containsOnlyKeys("holdId", "saleEventId", "seatCount")
                    .containsEntry("seatCount", 2);
            assertThat(((Number) body.get("saleEventId")).longValue()).isEqualTo(FIXTURE_SALE_EVENT_ID);
            String holdId = (String) body.get("holdId");
            assertThat(response.headers().firstValue("Location")).hasValue(HOLDS + "/" + holdId);
            assertThat(response.headers().firstValue("Content-Type")).hasValue(HoldHttpResponse.CONTENT_TYPE);
            assertThat(holdIdOf(a)).isEqualTo(holdId);
            assertThat(heldByOf(a)).as("선점 주체 = 액세스 토큰 사용자").isEqualTo(USER);
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER)).isEqualTo(2);
            assertThat(text(response)).doesNotContain("expiresAt");
        }

        /** I1·T3 — 같은 키·같은 좌석(순서만 다름)은 같은 바이트로 재생하고 선점은 한 번이다. */
        @Test
        void 같은_키_재요청은_같은_바이트로_재생하고_선점은_한_번이다() throws Exception {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long b = insertAvailableSeat(SCHEDULE, "1B");
            String key = nextKey();

            HttpResponse<byte[]> first = req().key(key).seats(a, b).send();
            HttpResponse<byte[]> again = req().key(key).seats(b, a).send();

            assertThat(first.statusCode()).isEqualTo(201);
            assertSameReplay(first, again);
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER)).as("선점 1회").isEqualTo(2);
            assertThat(heldSeatCount()).isEqualTo(2);
        }

        /** I6 — 성공 뒤 홀드를 해제해도 같은 키는 재선점하지 않고 최초 결과를 재생한다 (재생은 현재 상태가 아니다). */
        @Test
        void 해제_뒤에도_재선점하지_않고_재생한다() throws Exception {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            String key = nextKey();
            HttpResponse<byte[]> first = req().key(key).seats(a).send();
            releaseHold.release(new ReleaseHoldCommand(HoldId.of((String) json(first).get("holdId")), new UserId(USER)));
            assertThat(statusOf(a)).isEqualTo("AVAILABLE");

            HttpResponse<byte[]> again = req().key(key).seats(a).send();

            assertSameReplay(first, again);
            assertThat(statusOf(a)).as("재선점 없음").isEqualTo("AVAILABLE");
        }

        /** I3 — 다른 사용자의 같은 키는 별개 실행이다. */
        @Test
        void 다른_사용자의_같은_키는_별개다() throws Exception {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long b = insertAvailableSeat(SCHEDULE, "1B");
            String key = nextKey();

            assertThat(req().key(key).seats(a).send().statusCode()).isEqualTo(201);
            assertThat(req().user(OTHER_USER, FIXTURE_SALE_EVENT_ID).key(key).seats(b).send().statusCode()).isEqualTo(201);
            assertThat(heldByOf(b)).isEqualTo(OTHER_USER);
        }

        /** I2 — 같은 키·다른 좌석은 422 이고 실행하지 않는다. */
        @Test
        void 같은_키_다른_좌석은_422_IDEMPOTENCY_KEY_REUSED() throws Exception {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long b = insertAvailableSeat(SCHEDULE, "1B");
            String key = nextKey();
            req().key(key).seats(a).send();

            assertError(req().key(key).seats(b).send(), 422, "IDEMPOTENCY_KEY_REUSED");
            assertThat(statusOf(b)).isEqualTo("AVAILABLE");
        }
    }

    // ------------------------------------------------------------------ §2 저장된 업무 실패의 재생

    @Nested
    @DisplayName("§2 ★ 저장된 업무 실패의 재생 (I4·F3·F4)")
    class 업무_실패_재생 {

        /** I4 — 경합 409 뒤 좌석이 비어도 같은 키는 409 를 같은 바이트로 재생한다. 서버 오류율에 넣지 않는다. */
        @Test
        void 경합_409_는_좌석이_비어도_재생된다() throws Exception {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            double serverErrorsBefore = serverErrors();
            HttpResponse<byte[]> taken = req().user(OTHER_USER, FIXTURE_SALE_EVENT_ID).seats(a).send();
            String key = nextKey();

            HttpResponse<byte[]> first = req().key(key).seats(a).send();
            assertThat(first.statusCode()).isEqualTo(409);
            assertThat(text(first)).isEqualTo(
                    "{\"code\":\"SEAT_UNAVAILABLE\",\"message\":\"" + HoldErrorCode.SEAT_UNAVAILABLE.message + "\"}");
            assertThat(first.headers().firstValue("Retry-After")).isEmpty();

            releaseHold.release(new ReleaseHoldCommand(HoldId.of((String) json(taken).get("holdId")), new UserId(OTHER_USER)));
            HttpResponse<byte[]> again = req().key(key).seats(a).send();

            assertSameReplay(first, again);
            assertThat(statusOf(a)).as("재실행 없음").isEqualTo("AVAILABLE");
            assertThat(serverErrors()).as("409 는 서버 오류가 아니다").isEqualTo(serverErrorsBefore);
            assertThat(meters.find("railgate.hold.http.responses").tags("status", "409", "code", "SEAT_UNAVAILABLE",
                    "category", "normal").counter()).isNotNull();
        }

        /** F4 — 상한 초과 409 는 홀드가 풀려도 같은 키로 재생되고, 새 키로는 새로 실행된다 (D3). */
        @Test
        void 상한_초과_409_는_재생되고_새_키로는_실행된다() throws Exception {
            long[] four = {insertAvailableSeat(SCHEDULE, "1A"), insertAvailableSeat(SCHEDULE, "1B"),
                    insertAvailableSeat(SCHEDULE, "1C"), insertAvailableSeat(SCHEDULE, "1D")};
            long e = insertAvailableSeat(SCHEDULE, "1E");
            HttpResponse<byte[]> held = req().seats(four).send();
            assertThat(held.statusCode()).isEqualTo(201);
            String key = nextKey();

            HttpResponse<byte[]> first = req().key(key).seats(e).send();
            assertThat(first.statusCode()).isEqualTo(409);
            assertThat(json(first)).containsEntry("code", "QUOTA_EXCEEDED");

            releaseHold.release(new ReleaseHoldCommand(HoldId.of((String) json(held).get("holdId")), new UserId(USER)));
            assertSameReplay(first, req().key(key).seats(e).send());
            assertThat(statusOf(e)).isEqualTo("AVAILABLE");

            assertThat(req().seats(e).send().statusCode()).as("새 키").isEqualTo(201);
        }
    }

    // ------------------------------------------------------------------ §3 권한

    @Nested
    @DisplayName("§3 ★ 인증·입장 회차 (A1~A5)")
    class 권한 {

        @Test
        void 인증이_없으면_본문_검증보다_먼저_401() throws Exception {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            int before = coordinatorCalls();
            Req request = req();
            request.access = null;
            request.body = "not json";

            HttpResponse<byte[]> response = request.send();

            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(json(response)).containsEntry("code", "AUTHENTICATION_REQUIRED");
            assertUntouched(before, a);
        }

        /** A4 — 회차 E2 입장 토큰으로 회차 E1 좌석을 요청하면 403 이고 키 행이 없다. */
        @Test
        void 다른_회차의_입장_토큰은_403_ADMISSION_EVENT_MISMATCH() throws Exception {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            insertAvailableSeat(OTHER_SCHEDULE, "1A", OTHER_EVENT);
            int before = coordinatorCalls();
            Req request = req().seats(a);
            request.admission = TestAccessTokens.admission(NOW, USER, OTHER_EVENT);

            HttpResponse<byte[]> response = request.send();

            assertError(response, 403, "ADMISSION_EVENT_MISMATCH");
            assertUntouched(before, a);
            assertThat(text(response)).doesNotContain(Long.toString(OTHER_EVENT)).doesNotContain(Long.toString(USER));
        }

        /** ★ 재생도 사전 회차 대조를 먼저 거친다 — 다른 회차의 입장 토큰으로 저장된 결과를 받을 수 없다. */
        @Test
        void 다른_회차_토큰으로는_저장된_성공을_재생받을_수_없다() throws Exception {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            String key = nextKey();
            HttpResponse<byte[]> first = req().key(key).seats(a).send();
            assertThat(first.statusCode()).isEqualTo(201);
            int before = coordinatorCalls();

            Req wrongEvent = req().key(key).seats(a);
            wrongEvent.admission = TestAccessTokens.admission(NOW, USER, OTHER_EVENT);
            HttpResponse<byte[]> rejected = wrongEvent.send();

            assertError(rejected, 403, "ADMISSION_EVENT_MISMATCH");
            assertThat(text(rejected)).doesNotContain((String) json(first).get("holdId"));
            assertThat(coordinatorCalls()).as("조정자에 닿지 않는다").isEqualTo(before);
            assertSameReplay(first, req().key(key).seats(a).send());
        }

        @Test
        void 사용자가_다른_입장_토큰은_403_ADMISSION_USER_MISMATCH() throws Exception {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            int before = coordinatorCalls();
            Req request = req().seats(a);
            request.admission = TestAccessTokens.admission(NOW, OTHER_USER, FIXTURE_SALE_EVENT_ID);

            assertThat(json(request.send())).containsEntry("code", "ADMISSION_USER_MISMATCH");
            assertUntouched(before, a);
        }

        /** A5 — 본문의 userId·saleEventId 는 알려지지 않은 필드다. */
        @ParameterizedTest
        @ValueSource(strings = {"userId", "saleEventId"})
        void 본문의_사용자_회차_필드는_400(String field) throws Exception {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            int before = coordinatorCalls();
            Req request = req();
            request.body = "{\"seatIds\":[" + a + "],\"" + field + "\":" + OTHER_USER + "}";

            assertError(request.send(), 400, "INVALID_REQUEST");
            assertUntouched(before, a);
        }
    }

    // ------------------------------------------------------------------ §4 입력

    @Nested
    @DisplayName("§4 ★ 입력 검증 — 키 확보 전 거부 (V1~V4)")
    class 입력 {

        @Test
        void 멱등키_없음_형식_위반_중복_헤더는_400() throws Exception {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            int before = coordinatorCalls();
            List<List<String>> cases = List.of(List.of(), List.of("short-7"), List.of("k".repeat(65)),
                    List.of("key 00001x"), List.of("key-00001,key-00002"), List.of("key-00001x", "key-00001x"),
                    List.of("key-00001x", "key-00002x"));
            for (List<String> keys : cases) {
                Req request = req().seats(a);
                request.keys = keys;
                assertError(request.send(), 400, "IDEMPOTENCY_KEY_INVALID");
            }
            assertUntouched(before, a);
        }

        /** 키 검증이 본문 검증보다 먼저다 (§3 단계 4 → 5). */
        @Test
        void 키와_본문이_둘_다_틀리면_키_오류가_먼저다() throws Exception {
            Req request = req();
            request.keys = List.of();
            request.body = "garbage";
            request.contentType = "text/plain";

            assertError(request.send(), 400, "IDEMPOTENCY_KEY_INVALID");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "garbage", "{}", "{\"seatIds\":[]}", "{\"seatIds\":[1,1]}", "{\"seatIds\":[1,2,3,4,5]}",
                "{\"seatIds\":[0]}", "{\"seatIds\":[-1]}", "{\"seatIds\":[\"1\"]}", "{\"seatIds\":[1.5]}",
                "{\"seatIds\":[1],\"seatIds\":[2]}", "{\"seatIds\":[9223372036854775808]}"})
        void 잘못된_본문은_400_INVALID_REQUEST(String body) throws Exception {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            int before = coordinatorCalls();
            Req request = req();
            request.body = body;

            HttpResponse<byte[]> response = request.send();

            assertError(response, 400, "INVALID_REQUEST");
            assertThat(text(response)).doesNotContain(body.isEmpty() ? "\u0000" : body);
            assertUntouched(before, a);
        }

        @Test
        void JSON_이_아니면_415() throws Exception {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            int before = coordinatorCalls();
            for (String contentType : new String[] {"text/plain", "application/x-www-form-urlencoded", null}) {
                Req request = req().seats(a);
                request.contentType = contentType;
                assertError(request.send(), 415, "UNSUPPORTED_MEDIA_TYPE");
            }
            assertUntouched(before, a);
        }

        /**
         * V3 — 없는 좌석·회차 혼합은 사전 조회에서 422 이고 키 행이 없다. 저장되지 않는 <b>현재 요청 거절</b>이므로 본문에 현재 traceId 를
         * 담는다 (PR #37 리뷰 — 저장된 422 의 {code, message} 와 다르다).
         */
        @Test
        void 없는_좌석과_회차_혼합은_422_이고_키를_소비하지_않는다() throws Exception {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            long other = insertAvailableSeat(OTHER_SCHEDULE, "1A", OTHER_EVENT);
            int before = coordinatorCalls();

            for (HttpResponse<byte[]> response : List.of(req().seats(a, 987_654_321L).send(), req().seats(a, other).send())) {
                assertError(response, 422, "SALE_EVENT_SCOPE_REJECTED");
                assertThat(text(response)).isEqualTo("{\"code\":\"SALE_EVENT_SCOPE_REJECTED\",\"message\":"
                        + "\"선택한 좌석의 판매 회차를 하나로 확인할 수 없습니다.\",\"traceId\":\"" + traceId(response) + "\"}");
                assertThat(response.headers().firstValue("Location")).isEmpty();
            }
            assertThat(coordinatorCalls()).isEqualTo(before);
            assertThat(keyRows()).isZero();
            assertThat(statusOf(a)).isEqualTo("AVAILABLE");
            assertThat(statusOf(other)).isEqualTo("AVAILABLE");
            assertThat(quotaRowCount()).isZero();
        }

        /** V4 — 오류 본문에 입력값·키·사용자 id 가 없다. */
        @Test
        void 오류_본문에_입력값과_식별자가_없다() throws Exception {
            String key = "leak-check-0001";
            Req request = req().key(key);
            request.body = "{\"seatIds\":[\"secret-value\"]}";

            HttpResponse<byte[]> response = request.send();

            assertThat(text(response)).doesNotContain("secret-value").doesNotContain(key)
                    .doesNotContain(Long.toString(USER)).doesNotContain("Exception");
        }
    }

    // ------------------------------------------------------------------ §4-1 multipart

    @Nested
    @DisplayName("§4-1 ★ multipart 본문은 컨트롤러 전에 파싱하지 않는다 (PR #37 리뷰)")
    class Multipart {

        private static final String BOUNDARY = "railgate-boundary";
        private static final String MULTIPART = "multipart/form-data; boundary=" + BOUNDARY;
        /** 형식이 맞는 multipart 본문. */
        private static final String WELL_FORMED = "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"seatIds\"\r\n\r\n1\r\n--"
                + BOUNDARY + "--\r\n";
        /** 경계 선언과 맞지 않는 깨진 multipart 본문 — 파싱을 시도하면 실패한다. */
        private static final String MALFORMED = "--other-boundary\r\ngarbage-without-headers";

        private Req multipart(String body) {
            Req request = req();
            request.contentType = MULTIPART;
            request.body = body;
            return request;
        }

        @Test
        void 인증이_없으면_401_이_먼저다() throws Exception {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            int before = coordinatorCalls();
            for (String body : List.of(WELL_FORMED, MALFORMED)) {
                Req request = multipart(body);
                request.access = null;
                HttpResponse<byte[]> response = request.send();
                assertThat(response.statusCode()).isEqualTo(401);
                assertThat(json(response)).containsEntry("code", "AUTHENTICATION_REQUIRED");
            }
            assertUntouched(before, a);
        }

        @Test
        void 인증_후_키가_없으면_400_IDEMPOTENCY_KEY_INVALID() throws Exception {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            int before = coordinatorCalls();
            for (String body : List.of(WELL_FORMED, MALFORMED)) {
                Req request = multipart(body);
                request.keys = List.of();
                assertError(request.send(), 400, "IDEMPOTENCY_KEY_INVALID");
            }
            assertUntouched(before, a);
        }

        @Test
        void 두_토큰과_키가_유효한_multipart_는_415_UNSUPPORTED_MEDIA_TYPE() throws Exception {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            int before = coordinatorCalls();
            for (String body : List.of(WELL_FORMED, MALFORMED)) {
                HttpResponse<byte[]> response = multipart(body).send();
                assertError(response, 415, "UNSUPPORTED_MEDIA_TYPE");
                assertThat(text(response)).isEqualTo("{\"code\":\"UNSUPPORTED_MEDIA_TYPE\",\"message\":"
                        + "\"요청 본문은 application/json 이어야 합니다.\",\"traceId\":\"" + traceId(response) + "\"}");
            }
            assertUntouched(before, a);
        }

        /**
         * ★ 컨트롤러 전에 multipart 를 파싱하면 기본 파일 크기 상한(1MB)을 넘는 파트가 MVC 의 multipart 처리에서 먼저 실패해
         * 계약 밖의 응답이 나간다. 파싱하지 않으면 컨트롤러가 415 로 거절한다 — 본문은 상한(4,096 바이트)만큼만 읽는다.
         */
        @Test
        void 큰_파트의_multipart_도_컨트롤러가_415_로_거절한다() throws Exception {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            int before = coordinatorCalls();
            String big = "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.bin\"\r\n"
                    + "Content-Type: application/octet-stream\r\n\r\n" + "x".repeat(2 * 1024 * 1024) + "\r\n--" + BOUNDARY + "--\r\n";

            assertError(multipart(big).send(), 415, "UNSUPPORTED_MEDIA_TYPE");
            assertUntouched(before, a);
        }

        /** 정상 JSON 선점과 본문 크기 상한은 그대로다. */
        @Test
        void 정상_JSON_과_본문_상한은_그대로다() throws Exception {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            Req oversized = req();
            oversized.body = "{\"seatIds\":[" + a + "]}" + " ".repeat(HoldRequestParser.MAX_BODY_BYTES);
            assertError(oversized.send(), 400, "INVALID_REQUEST");
            assertThat(keyRows()).isZero();

            assertThat(req().seats(a).send().statusCode()).isEqualTo(201);
        }
    }

    // ------------------------------------------------------------------ §5 동시성과 장애

    @Nested
    @DisplayName("§5 ★ 같은 키 경합·잠금 대기 장애 (I5·F1)")
    class 경합과_장애 {

        /** ★ I5 — 같은 키 동시 요청 N 개: 실제 선점은 한 번, 나머지는 재생 201 또는 409 진행 중 + Retry-After, 5xx 0. */
        @Test
        void 같은_키_동시_요청은_선점_한_번이고_5xx_가_없다() throws Exception {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            String key = nextKey();
            int n = 8;
            CountDownLatch ready = new CountDownLatch(n);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<HttpResponse<byte[]>>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Req request = req().key(key).seats(a);
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return request.send();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            List<HttpResponse<byte[]>> responses = new ArrayList<>();
            for (Future<HttpResponse<byte[]>> future : futures) {
                responses.add(future.get(30, TimeUnit.SECONDS));
            }
            Set<Integer> statuses = responses.stream().map(HttpResponse::statusCode).collect(Collectors.toSet());
            assertThat(statuses).isSubsetOf(201, 409);
            List<HttpResponse<byte[]>> created = responses.stream().filter(r -> r.statusCode() == 201).toList();
            assertThat(created).isNotEmpty();
            for (HttpResponse<byte[]> ok : created) {
                assertThat(ok.body()).isEqualTo(created.getFirst().body());
            }
            for (HttpResponse<byte[]> busy : responses.stream().filter(r -> r.statusCode() == 409).toList()) {
                assertThat(json(busy)).containsEntry("code", "IDEMPOTENCY_REQUEST_IN_PROGRESS");
                assertThat(busy.headers().firstValue("Retry-After")).hasValue("1");
            }
            assertThat(heldSeatCount()).as("실제 선점 1회").isEqualTo(1);
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER)).isEqualTo(1);
        }

        /**
         * F1 — TX1 이 좌석 행 잠금을 기다리다 잠금 대기 초과(innodb_lock_wait_timeout=3)로 실패하면 503 + Retry-After 이고
         * 좌석·quota 는 롤백된다. 잠금이 풀린 뒤 같은 키의 즉시 재시도는 포기된 리스를 인수해 실행한다.
         */
        @Test
        void 잠금_대기_초과는_503_이고_같은_키_재시도는_인수해_실행한다() throws Exception {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            String key = nextKey();
            double serverErrorsBefore = responses("503", "SERVICE_UNAVAILABLE");

            HttpResponse<byte[]> failed;
            try (Connection holder = dataSource().getConnection()) {
                holder.setAutoCommit(false);
                try (Statement st = holder.createStatement()) {
                    st.executeQuery("SELECT id FROM seat_inventory WHERE id = " + a + " FOR UPDATE").close();
                }
                failed = req().key(key).seats(a).send();
                holder.rollback();
            }

            assertError(failed, 503, "SERVICE_UNAVAILABLE");
            assertThat(failed.headers().firstValue("Retry-After")).hasValue("1");
            assertThat(statusOf(a)).isEqualTo("AVAILABLE");
            assertThat(quotaOf(FIXTURE_SALE_EVENT_ID, USER)).as("quota 증가 롤백").isIn(-1, 0);
            assertThat(responses("503", "SERVICE_UNAVAILABLE")).isEqualTo(serverErrorsBefore + 1);

            HttpResponse<byte[]> retried = req().key(key).seats(a).send();
            assertThat(retried.statusCode()).as(text(retried)).isEqualTo(201);
            assertThat(statusOf(a)).isEqualTo("HELD");
        }
    }

    // ------------------------------------------------------------------ §6 관측

    @Nested
    @DisplayName("§6 관측 — 고정 태그만")
    class 관측 {

        @Test
        void 응답_메트릭은_고정_태그만_쓴다() throws Exception {
            long a = insertAvailableSeat(SCHEDULE, "1A");
            String key = nextKey();
            req().key(key).seats(a).send();
            req().key(key).seats(a).send();
            Req bad = req();
            bad.body = "{}";
            bad.send();

            List<Tag> tags = meters.find("railgate.hold.http.responses").counters().stream()
                    .flatMap(c -> c.getId().getTags().stream()).toList();
            assertThat(tags).extracting(Tag::getKey).containsOnly("status", "code", "category");
            Set<String> codes = tags.stream().filter(t -> t.getKey().equals("code")).map(Tag::getValue)
                    .collect(Collectors.toSet());
            Set<String> allowed = java.util.Arrays.stream(HoldErrorCode.values()).map(Enum::name)
                    .collect(Collectors.toSet());
            allowed.add("none");
            assertThat(codes).isSubsetOf(allowed);
            assertThat(tags).extracting(Tag::getValue).doesNotContain(key, Long.toString(USER), Long.toString(a));
            assertThat(responses("201", "none")).isGreaterThanOrEqualTo(2);
        }
    }
}
