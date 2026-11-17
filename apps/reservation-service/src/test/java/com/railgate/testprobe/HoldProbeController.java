package com.railgate.testprobe;

import com.railgate.reservation.api.security.AdmissionContext;
import com.railgate.reservation.api.security.AuthenticatedUser;
import com.railgate.reservation.application.hold.HoldSeatsCommand;
import com.railgate.reservation.application.idempotency.IdempotentHoldResult;
import com.railgate.reservation.application.idempotency.IdempotentHoldService;
import com.railgate.reservation.idempotency.IdempotencyKey;
import com.railgate.reservation.seat.SeatId;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * <b>테스트 전용</b> 선점 경로 확인용 컨트롤러 (Task 2J-C). 운영의 선점 컨트롤러가 아니다 — 그것은 Task 2J-D 다.
 *
 * <p>입장 필터를 통과하면 <b>실제 조정자</b>를 불러 키·좌석·quota 를 바꾼다. 그래서 거부된 요청에서 그것들이 바뀌지 않았다는 것이
 * "필터가 막았다" 는 근거가 된다. 컴포넌트 스캔 밖 패키지에 두고 필요한 테스트만 {@code @Bean} 으로 등록한다.
 */
@RestController
public class HoldProbeController {

    public static final String PATH = "/api/reservations/holds";

    private final IdempotentHoldService service;
    private final AtomicInteger invocations = new AtomicInteger();

    public HoldProbeController(IdempotentHoldService service) {
        this.service = service;
    }

    public int invocations() {
        return invocations.get();
    }

    @PostMapping(PATH)
    public Map<String, Object> hold(@AuthenticationPrincipal AuthenticatedUser user,
                                    @RequestHeader("Idempotency-Key") String key,
                                    @RequestParam long seatId,
                                    HttpServletRequest request) {
        invocations.incrementAndGet();
        AdmissionContext admission = AdmissionContext.from(request).orElseThrow();
        IdempotentHoldResult result = service.hold(new IdempotencyKey(key),
                new HoldSeatsCommand(user.userId(), List.of(new SeatId(seatId))));
        return Map.of("principalUserId", user.userId().value(),
                "admissionUserId", admission.userId(),
                "admissionSaleEventId", admission.saleEventId(),
                "result", result.getClass().getSimpleName());
    }
}
