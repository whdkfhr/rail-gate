package com.railgate.reservation.api.hold;

import com.railgate.reservation.api.security.AdmissionContext;
import com.railgate.reservation.api.security.AuthenticatedUser;
import com.railgate.reservation.api.trace.TraceIdFilter;
import com.railgate.reservation.application.hold.HoldSeatsCommand;
import com.railgate.reservation.application.idempotency.IdempotentHoldResult;
import com.railgate.reservation.application.idempotency.IdempotentHoldService;
import com.railgate.reservation.idempotency.IdempotencyKey;
import com.railgate.reservation.idempotency.StoredHoldOutcome;
import com.railgate.reservation.saleevent.SaleEventId;
import com.railgate.reservation.saleevent.SaleEventScopeException;
import com.railgate.reservation.saleevent.SaleEventScopePort;
import com.railgate.reservation.seat.SeatId;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ★ 운영 좌석 선점 API — {@code POST /api/reservations/holds} (HOLD_HTTP_CONTRACT, Task 2J-D).
 *
 * <h2>처리 순서 (§3) — 1~7 은 아무것도 쓰지 않는다</h2>
 *
 * <ol>
 *   <li>액세스 토큰 — 보안 필터 (401 {@code AUTHENTICATION_REQUIRED})</li>
 *   <li>입장 토큰 — 입장 필터 (401 {@code ADMISSION_TOKEN_INVALID})</li>
 *   <li>두 토큰의 사용자 대조 — 입장 필터 (403 {@code ADMISSION_USER_MISMATCH})</li>
 *   <li>{@code Idempotency-Key} 헤더 (400 {@code IDEMPOTENCY_KEY_INVALID})</li>
 *   <li>{@code Content-Type}(415)·본문(400 {@code INVALID_REQUEST})</li>
 *   <li>좌석 → 판매 회차 <b>읽기 전용</b> 해석, 트랜잭션 밖 (422 {@code SALE_EVENT_SCOPE_REJECTED})</li>
 *   <li>입장 토큰 회차 = 해석한 회차 (403 {@code ADMISSION_EVENT_MISMATCH}) — <b>재생 요청도 이 대조를 먼저 거친다</b></li>
 *   <li>멱등 조정자 호출 → §6 매핑. 조정자를 바깥 트랜잭션으로 감싸지 않는다</li>
 * </ol>
 *
 * <h2>신원과 회차 권한의 출처</h2>
 *
 * <p>사용자는 {@link AuthenticatedUser}(액세스 토큰)에서만, 기대 회차는 {@link AdmissionContext}(입장 토큰)에서만 가져온다.
 * 요청 본문의 사용자·회차 필드는 받지 않는다. 입장 정보가 없으면 입장 필터 배선이 깨진 것이므로 <b>선점하지 않고</b> 500 으로 끝낸다.
 *
 * <h2>로그·지표 (§6.2)</h2>
 *
 * <p>500·503 만 ERROR 로 남긴다. 정상 409 는 DEBUG, 클라이언트 오류는 INFO 이며 서버 오류율에 넣지 않는다. 메트릭
 * {@value #RESPONSES}{@code {status, code, category}} 는 고정 값만 태그로 쓴다 — 사용자 id·키·좌석 id 를 넣지 않는다.
 * 로그에도 토큰·본문·키를 남기지 않는다 (규칙 34). MDC 에는 {@code userId} 와 성공·재생한 {@code holdId} 를 요청 동안만 둔다 (규칙 33).
 */
@RestController
public class HoldController {

    public static final String PATH = "/api/reservations/holds";
    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    static final String RESPONSES = "railgate.hold.http.responses";
    /** 규칙 33 — 이 요청의 로그에 사용자·홀드를 붙인다. traceId 는 {@link TraceIdFilter} 가 넣는다. */
    static final String MDC_USER_ID = "userId";
    static final String MDC_HOLD_ID = "holdId";

    private static final Logger log = LoggerFactory.getLogger(HoldController.class);

    private final IdempotentHoldService holds;
    private final SaleEventScopePort saleEventScope;
    private final MeterRegistry meters;

    public HoldController(IdempotentHoldService holds, SaleEventScopePort saleEventScope, MeterRegistry meters) {
        this.holds = Objects.requireNonNull(holds, "holds");
        this.saleEventScope = Objects.requireNonNull(saleEventScope, "saleEventScope");
        this.meters = Objects.requireNonNull(meters, "meters");
    }

    @PostMapping(PATH)
    public void hold(@AuthenticationPrincipal AuthenticatedUser user,
                     HttpServletRequest request,
                     HttpServletResponse response) throws IOException {
        String traceId = TraceIdFilter.currentTraceId(request);
        if (user != null) {
            MDC.put(MDC_USER_ID, Long.toString(user.userId().value()));
        }
        try {
            HoldHttpResponse result;
            try {
                result = handle(user, request, traceId);
            } catch (RuntimeException failure) {
                result = HoldHttpResponses.failure(failure, traceId);
                logFailure(result, failure);
            }
            write(result, response);
        } finally {
            MDC.remove(MDC_USER_ID);
            MDC.remove(MDC_HOLD_ID);
        }
    }

    private HoldHttpResponse handle(AuthenticatedUser user, HttpServletRequest request, String traceId) {
        Optional<AdmissionContext> admission = AdmissionContext.from(request);
        if (user == null || admission.isEmpty()) {
            // 보안·입장 필터가 막았어야 한다. 입장 권한 없이 선점하는 경로를 만들지 않는다.
            throw new IllegalStateException("선점 경로에 인증·입장 정보가 없다 — 필터 배선 오류");
        }

        // 4. Idempotency-Key — 같은 이름의 헤더 필드를 모두 본다. 다듬지 않는다.
        Optional<IdempotencyKey> key = HoldRequestParser.idempotencyKey(
                Collections.list(request.getHeaders(IDEMPOTENCY_KEY_HEADER)));
        if (key.isEmpty()) {
            return HoldHttpResponses.error(HoldErrorCode.IDEMPOTENCY_KEY_INVALID, traceId);
        }

        // 5. Content-Type 과 본문.
        if (!HoldRequestParser.isJson(request.getContentType())) {
            return HoldHttpResponses.error(HoldErrorCode.UNSUPPORTED_MEDIA_TYPE, traceId);
        }
        byte[] body;
        try {
            body = readBounded(request);
        } catch (IOException unreadable) {
            // 클라이언트가 본문을 다 보내지 않았다(중단·시간 초과). 서버 장애가 아니다.
            return HoldHttpResponses.error(HoldErrorCode.INVALID_REQUEST, traceId);
        }
        Optional<List<SeatId>> seatIds = HoldRequestParser.seatIds(body);
        if (seatIds.isEmpty()) {
            return HoldHttpResponses.error(HoldErrorCode.INVALID_REQUEST, traceId);
        }

        // 6. 읽기 전용 회차 해석 — 트랜잭션 밖, 기존 조회 포트. 키·좌석·quota 를 쓰지 않는다.
        SaleEventId resolved;
        try {
            resolved = saleEventScope.resolve(seatIds.get());
        } catch (SaleEventScopeException rejected) {
            return HoldHttpResponses.error(HoldErrorCode.SALE_EVENT_SCOPE_REJECTED, traceId);
        }

        // 7. 입장 회차 대조 — 재생 요청도 여기를 지난다. 다른 회차 토큰으로 저장된 결과를 받을 수 없다.
        SaleEventId admitted = new SaleEventId(admission.get().saleEventId());
        if (!resolved.equals(admitted)) {
            return HoldHttpResponses.error(HoldErrorCode.ADMISSION_EVENT_MISMATCH, traceId);
        }

        // 8. 조정자. 기대 회차는 입장 토큰의 값이다 — TX1 이 다시 대조한다 (D4).
        IdempotentHoldResult result = holds.hold(key.get(),
                new HoldSeatsCommand(user.userId(), seatIds.get(), admitted));
        holdIdOf(result).ifPresent(holdId -> MDC.put(MDC_HOLD_ID, holdId));
        return HoldHttpResponses.from(result, traceId);
    }

    private static Optional<String> holdIdOf(IdempotentHoldResult result) {
        StoredHoldOutcome outcome = switch (result) {
            case IdempotentHoldResult.Executed executed -> executed.outcome();
            case IdempotentHoldResult.Replayed replayed -> replayed.outcome();
            default -> null;
        };
        return outcome instanceof StoredHoldOutcome.Succeeded succeeded
                ? Optional.of(succeeded.holdId().asString()) : Optional.empty();
    }

    /** 상한보다 1바이트 더 읽어 넘으면 파싱하지 않는다 — 읽은 만큼만 버린다. */
    private static byte[] readBounded(HttpServletRequest request) throws IOException {
        try (InputStream in = request.getInputStream()) {
            return in.readNBytes(HoldRequestParser.MAX_BODY_BYTES + 1);
        }
    }

    private void write(HoldHttpResponse result, HttpServletResponse response) throws IOException {
        meters.counter(RESPONSES, "status", Integer.toString(result.status()), "code", result.codeTag(),
                "category", result.category().tag()).increment();
        if (result.code() != null && result.category() == HoldErrorCode.Category.CLIENT_ERROR) {
            log.info("선점 요청 거절 status={} code={}", result.status(), result.codeTag());
        } else if (result.code() != null && result.category() == HoldErrorCode.Category.NORMAL) {
            log.debug("선점 요청 정상 거절 status={} code={}", result.status(), result.codeTag());
        }
        response.setStatus(result.status());
        response.setContentType(HoldHttpResponse.CONTENT_TYPE);
        if (result.location() != null) {
            response.setHeader("Location", result.location());
        }
        if (result.retryAfter() != null) {
            response.setHeader("Retry-After", Integer.toString(result.retryAfter()));
        }
        byte[] body = result.body().getBytes(StandardCharsets.UTF_8);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    private static void logFailure(HoldHttpResponse result, RuntimeException failure) {
        if (result.category() == HoldErrorCode.Category.SERVER_ERROR) {
            log.error("선점 요청 처리 중 서버 장애 status={} code={}", result.status(), result.codeTag(), failure);
        }
    }
}
