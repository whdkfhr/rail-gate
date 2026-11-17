package com.railgate.reservation.api.hold;

import static org.assertj.core.api.Assertions.assertThat;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.application.hold.AdmissionEventMismatchException;
import com.railgate.reservation.application.idempotency.IdempotentHoldResult;
import com.railgate.reservation.idempotency.HoldRejectionCode;
import com.railgate.reservation.idempotency.IdempotencyKey;
import com.railgate.reservation.idempotency.StoredHoldOutcome;
import com.railgate.reservation.saleevent.SaleEventId;
import com.railgate.reservation.seat.SeatId;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

/**
 * Task 2J-D — 선점 HTTP 의 요청 검증과 응답 매핑 (HOLD_HTTP_CONTRACT §4~§7). DB·Spring 없이 규칙만 확인한다.
 * 실제 필터 체인·MySQL 로 같은 계약을 다시 확인하는 것은 {@code HoldControllerIntegrationTest} 다.
 */
@DisplayName("Task 2J-D — 선점 HTTP 요청 검증·응답 매핑")
class HoldHttpContractTest {

    private static final String TRACE = "0af7651916cd43dd8448eb211c80319c";

    private static Optional<List<SeatId>> seats(String json) {
        return HoldRequestParser.seatIds(json.getBytes(StandardCharsets.UTF_8));
    }

    private static List<SeatId> ids(long... values) {
        return Arrays.stream(values).mapToObj(SeatId::new).toList();
    }

    // ------------------------------------------------------------------ §4 Idempotency-Key

    @Nested
    @DisplayName("§4 Idempotency-Key — 정확히 하나, 다듬지 않는다")
    class 멱등키 {

        @Test
        void 형식에_맞는_값_하나만_받는다() {
            assertThat(HoldRequestParser.idempotencyKey(List.of("abc-DEF_12"))).contains(new IdempotencyKey("abc-DEF_12"));
            assertThat(HoldRequestParser.idempotencyKey(List.of("a".repeat(64)))).isPresent();
        }

        @Test
        void 없거나_둘_이상이면_거부한다() {
            assertThat(HoldRequestParser.idempotencyKey(List.of())).isEmpty();
            assertThat(HoldRequestParser.idempotencyKey(List.of("key-00001", "key-00001"))).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "short-7", " key-00001", "key-00001 ", "key 00001", "key-0001,key-0002", "키-00000001",
                "key.00001", "key/00001"})
        void 형식_위반은_다듬지_않고_거부한다(String value) {
            assertThat(HoldRequestParser.idempotencyKey(List.of(value))).isEmpty();
        }

        @Test
        void 길이_경계는_8자와_64자다() {
            assertThat(HoldRequestParser.idempotencyKey(List.of("a".repeat(8)))).isPresent();
            assertThat(HoldRequestParser.idempotencyKey(List.of("a".repeat(65)))).isEmpty();
        }

        @Test
        void 대소문자만_다른_키는_다른_키다() {
            assertThat(HoldRequestParser.idempotencyKey(List.of("Key-00001")))
                    .isNotEqualTo(HoldRequestParser.idempotencyKey(List.of("key-00001")));
        }
    }

    // ------------------------------------------------------------------ §5 본문

    @Nested
    @DisplayName("§5 본문 — seatIds 하나, 양수 long 정수 1~4개, 중복 없음")
    class 본문 {

        @Test
        void 정상_본문은_요청_순서대로_좌석을_돌려준다() {
            assertThat(seats("{\"seatIds\":[102,101]}")).contains(ids(102, 101));
            assertThat(seats(" { \"seatIds\" : [ 1 , 2 , 3 , 4 ] } ")).contains(ids(1, 2, 3, 4));
            assertThat(seats("{\"seatIds\":[9223372036854775807]}")).contains(ids(Long.MAX_VALUE));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "", " ", "null", "[]", "[1]", "\"x\"", "1", "{", "{\"seatIds\":[1]", "{\"seatIds\":[1]} x",
                "{}", "{\"seatIds\":null}", "{\"seatIds\":1}", "{\"seatIds\":\"1\"}", "{\"seatIds\":{}}",
                "{\"seatIds\":[]}", "{\"seatIds\":[1,2,3,4,5]}", "{\"seatIds\":[1,1]}",
                "{\"seatIds\":[0]}", "{\"seatIds\":[-1]}", "{\"seatIds\":[\"1\"]}", "{\"seatIds\":[1.0]}",
                "{\"seatIds\":[1.5]}", "{\"seatIds\":[1e2]}", "{\"seatIds\":[9223372036854775808]}",
                "{\"seatIds\":[null]}", "{\"seatIds\":[true]}", "{\"seatIds\":[[1]]}",
                "{\"seatIds\":[1],\"userId\":7}", "{\"seatIds\":[1],\"saleEventId\":9000000}",
                "{\"seatIds\":[1],\"seatIds\":[2]}", "{\"SeatIds\":[1]}", "{\"seatIds\":[1],}", "{'seatIds':[1]}",
                "{\"seatIds\":[1]}{\"seatIds\":[2]}"})
        void 잘못된_본문은_거부한다(String json) {
            assertThat(seats(json)).as(json).isEmpty();
        }

        @Test
        void 상한을_넘는_본문은_파싱하지_않고_거부한다() {
            String padded = "{\"seatIds\":[1]}" + " ".repeat(HoldRequestParser.MAX_BODY_BYTES);
            assertThat(seats(padded)).isEmpty();
            assertThat(HoldRequestParser.seatIds(null)).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"application/json", "application/json;charset=UTF-8", "Application/JSON; charset=utf-8"})
        void JSON_Content_Type_은_받는다(String contentType) {
            assertThat(HoldRequestParser.isJson(contentType)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "text/plain", "application/x-www-form-urlencoded", "application/*", "*/*",
                "application/problem+json", "application/jsonx", "multipart/form-data; boundary=x", "garbage;;"})
        void JSON_이_아닌_Content_Type_은_거부한다(String contentType) {
            assertThat(HoldRequestParser.isJson(contentType)).isFalse();
            assertThat(HoldRequestParser.isJson(null)).isFalse();
        }
    }

    // ------------------------------------------------------------------ §6·§7 응답

    @Nested
    @DisplayName("§6·§7 결과 → HTTP — 재생 대상은 저장 결과에서만, traceId 는 본문에 없다")
    class 응답 {

        private final HoldId holdId = new HoldId(UUID.fromString("7f3c2a1e-0b4d-4c55-9a8e-2f6d1c0b9e11"));
        private final StoredHoldOutcome.Succeeded succeeded =
                new StoredHoldOutcome.Succeeded(holdId, new SaleEventId(9_000_000L), 2);

        @Test
        void 성공은_201_과_저장_결과의_세_필드다() {
            HoldHttpResponse response = HoldHttpResponses.from(new IdempotentHoldResult.Executed(succeeded), TRACE);

            assertThat(response.status()).isEqualTo(201);
            assertThat(response.body()).isEqualTo(
                    "{\"holdId\":\"7f3c2a1e-0b4d-4c55-9a8e-2f6d1c0b9e11\",\"saleEventId\":9000000,\"seatCount\":2}");
            assertThat(response.location()).isEqualTo("/api/reservations/holds/7f3c2a1e-0b4d-4c55-9a8e-2f6d1c0b9e11");
            assertThat(response.retryAfter()).isNull();
            assertThat(response.category()).isEqualTo(HoldErrorCode.Category.NORMAL);
        }

        @Test
        void 실행과_재생은_상태_본문_Location_이_같고_traceId_를_담지_않는다() {
            HoldHttpResponse executed = HoldHttpResponses.from(new IdempotentHoldResult.Executed(succeeded), TRACE);
            HoldHttpResponse replayed = HoldHttpResponses.from(new IdempotentHoldResult.Replayed(succeeded), "other-trace");

            assertThat(replayed).isEqualTo(executed);
            for (HoldRejectionCode code : HoldRejectionCode.values()) {
                StoredHoldOutcome.Rejected rejected = new StoredHoldOutcome.Rejected(code);
                HoldHttpResponse first = HoldHttpResponses.from(new IdempotentHoldResult.Executed(rejected), TRACE);
                HoldHttpResponse again = HoldHttpResponses.from(new IdempotentHoldResult.Replayed(rejected), "other-trace");
                assertThat(again).isEqualTo(first);
                assertThat(first.body()).doesNotContain("traceId").doesNotContain(TRACE);
                assertThat(first.location()).isNull();
            }
        }

        @Test
        void 업무_실패는_계약의_상태와_고정_본문이다() {
            assertThat(rejected(HoldRejectionCode.SEAT_UNAVAILABLE).status()).isEqualTo(409);
            assertThat(rejected(HoldRejectionCode.SEAT_UNAVAILABLE).body())
                    .isEqualTo("{\"code\":\"SEAT_UNAVAILABLE\",\"message\":\"선택한 좌석 중 이미 선점된 좌석이 있습니다.\"}");
            assertThat(rejected(HoldRejectionCode.QUOTA_EXCEEDED).status()).isEqualTo(409);
            assertThat(rejected(HoldRejectionCode.QUOTA_EXCEEDED).retryAfter()).as("D3 — 강제하지 않는다").isNull();
            assertThat(rejected(HoldRejectionCode.SALE_EVENT_SCOPE_REJECTED).status()).isEqualTo(422);
            assertThat(rejected(HoldRejectionCode.SEAT_UNAVAILABLE).category()).isEqualTo(HoldErrorCode.Category.NORMAL);
            assertThat(rejected(HoldRejectionCode.SALE_EVENT_SCOPE_REJECTED).category())
                    .isEqualTo(HoldErrorCode.Category.CLIENT_ERROR);
        }

        private HoldHttpResponse rejected(HoldRejectionCode code) {
            return HoldHttpResponses.from(new IdempotentHoldResult.Executed(new StoredHoldOutcome.Rejected(code)), TRACE);
        }

        @Test
        void 진행_중과_소유권_상실은_409_와_Retry_After_다() {
            for (IdempotentHoldResult result : List.of(new IdempotentHoldResult.InProgress(),
                    new IdempotentHoldResult.OwnershipLost())) {
                HoldHttpResponse response = HoldHttpResponses.from(result, TRACE);
                assertThat(response.status()).isEqualTo(409);
                assertThat(response.code()).isEqualTo(HoldErrorCode.IDEMPOTENCY_REQUEST_IN_PROGRESS);
                assertThat(response.retryAfter()).isEqualTo(1);
                assertThat(response.body()).isEqualTo("{\"code\":\"IDEMPOTENCY_REQUEST_IN_PROGRESS\",\"message\":"
                        + "\"같은 요청을 처리 중입니다. 잠시 후 같은 키로 다시 시도하세요.\",\"traceId\":\"" + TRACE + "\"}");
                assertThat(response.category()).isEqualTo(HoldErrorCode.Category.NORMAL);
            }
        }

        @Test
        void fingerprint_불일치는_422_IDEMPOTENCY_KEY_REUSED_다() {
            HoldHttpResponse response = HoldHttpResponses.from(new IdempotentHoldResult.FingerprintMismatch(), TRACE);
            assertThat(response.status()).isEqualTo(422);
            assertThat(response.code()).isEqualTo(HoldErrorCode.IDEMPOTENCY_KEY_REUSED);
            assertThat(response.body()).contains("\"traceId\":\"" + TRACE + "\"");
        }

        @Test
        void 재생되지_않는_오류는_본문에_현재_traceId_를_담는다() {
            HoldHttpResponse response = HoldHttpResponses.error(HoldErrorCode.INVALID_REQUEST, TRACE);
            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body()).isEqualTo(
                    "{\"code\":\"INVALID_REQUEST\",\"message\":\"요청 본문이 올바르지 않습니다.\",\"traceId\":\"" + TRACE + "\"}");
            assertThat(HoldHttpResponses.error(HoldErrorCode.UNSUPPORTED_MEDIA_TYPE, TRACE).status()).isEqualTo(415);
            assertThat(HoldHttpResponses.error(HoldErrorCode.IDEMPOTENCY_KEY_INVALID, TRACE).status()).isEqualTo(400);
            assertThat(HoldHttpResponses.error(HoldErrorCode.ADMISSION_EVENT_MISMATCH, TRACE).status()).isEqualTo(403);
        }

        /**
         * ★ PR #37 리뷰 — 사전 조회의 422 는 <b>현재 요청을 거절하는 응답</b>이다. 저장되지 않으므로 재생 대상이 아니고 규칙 24 대로
         * 본문에 현재 traceId 를 담는다. 코드·상태·문구는 저장된 422 와 같다.
         */
        @Test
        void 사전_조회_422_는_현재_traceId_를_담고_저장된_422_와_다르다() {
            HoldHttpResponse preCheck = HoldHttpResponses.error(HoldErrorCode.SALE_EVENT_SCOPE_REJECTED, TRACE);

            assertThat(preCheck.status()).isEqualTo(422);
            assertThat(preCheck.body()).isEqualTo("{\"code\":\"SALE_EVENT_SCOPE_REJECTED\",\"message\":"
                    + "\"선택한 좌석의 판매 회차를 하나로 확인할 수 없습니다.\",\"traceId\":\"" + TRACE + "\"}");
            assertThat(preCheck.body()).isNotEqualTo(rejected(HoldRejectionCode.SALE_EVENT_SCOPE_REJECTED).body());
        }

        /**
         * ★ 저장된 업무 실패의 응답 바이트를 <b>고정 리터럴</b>로 비교한다 — 구현의 문구 상수를 읽지 않는다. 문구·직렬화가 바뀌면 여기서
         * 깨진다 (§7.3 배포 간 응답 호환 조건).
         */
        @Test
        void 저장된_업무_실패의_본문_바이트는_고정이다() {
            assertThat(rejected(HoldRejectionCode.SALE_EVENT_SCOPE_REJECTED).body()).isEqualTo("{\"code\":\"SALE_EVENT_SCOPE_REJECTED\",\"message\":\"선택한 좌석의 판매 회차를 하나로 확인할 수 없습니다.\"}");
            assertThat(rejected(HoldRejectionCode.SEAT_UNAVAILABLE).body())
                    .isEqualTo("{\"code\":\"SEAT_UNAVAILABLE\",\"message\":\"선택한 좌석 중 이미 선점된 좌석이 있습니다.\"}");
            assertThat(rejected(HoldRejectionCode.QUOTA_EXCEEDED).body())
                    .isEqualTo("{\"code\":\"QUOTA_EXCEEDED\",\"message\":\"1인당 선점할 수 있는 좌석 수를 넘었습니다.\"}");
            for (HoldRejectionCode code : HoldRejectionCode.values()) {
                assertThat(HoldHttpResponses.from(new IdempotentHoldResult.Replayed(new StoredHoldOutcome.Rejected(code)),
                        "replay-trace").body()).isEqualTo(rejected(code).body());
            }
        }

        @Test
        void D4_회차_불일치는_403_이고_서버_장애가_아니다() {
            HoldHttpResponse response = HoldHttpResponses.failure(new AdmissionEventMismatchException(), TRACE);
            assertThat(response.status()).isEqualTo(403);
            assertThat(response.code()).isEqualTo(HoldErrorCode.ADMISSION_EVENT_MISMATCH);
            assertThat(response.category()).isEqualTo(HoldErrorCode.Category.CLIENT_ERROR);
        }

        @Test
        void 재시도_가능한_장애는_503_과_Retry_After_다() {
            SQLException cause = new SQLException("Lock wait timeout exceeded", "HY000", 1205);
            for (RuntimeException failure : List.of(new CannotAcquireLockException("x", cause),
                    new QueryTimeoutException("x"), new CannotGetJdbcConnectionException("x", cause),
                    new CannotCreateTransactionException("x", cause))) {
                HoldHttpResponse response = HoldHttpResponses.failure(failure, TRACE);
                assertThat(response.status()).as(failure.getClass().getSimpleName()).isEqualTo(503);
                assertThat(response.retryAfter()).isEqualTo(1);
                assertThat(response.category()).isEqualTo(HoldErrorCode.Category.SERVER_ERROR);
                assertThat(response.body()).doesNotContain("Lock wait").doesNotContain("SQL");
            }
        }

        @Test
        void 그_밖의_장애와_커밋_결과_불명은_500_이다() {
            for (RuntimeException failure : List.of(new TransactionSystemException("commit failed"),
                    new DataIntegrityViolationException("x"), new IllegalStateException("bug"))) {
                HoldHttpResponse response = HoldHttpResponses.failure(failure, TRACE);
                assertThat(response.status()).as(failure.getClass().getSimpleName()).isEqualTo(500);
                assertThat(response.code()).isEqualTo(HoldErrorCode.INTERNAL_ERROR);
                assertThat(response.retryAfter()).isNull();
                assertThat(response.body()).doesNotContain("commit failed").doesNotContain("bug");
            }
        }
    }
}
