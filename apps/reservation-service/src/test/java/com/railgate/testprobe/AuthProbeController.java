package com.railgate.testprobe;

import com.railgate.reservation.api.security.AuthenticatedUser;
import com.railgate.reservation.api.trace.TraceIdFilter;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <b>테스트 전용</b> 확인용 컨트롤러. 인증 주체와 요청 처리 중의 MDC traceId 를 그대로 돌려준다.
 *
 * <p>애플리케이션의 컴포넌트 스캔 범위({@code com.railgate.reservation}) 밖에 두어 다른 테스트 컨텍스트에 섞이지 않게 하고,
 * 필요한 테스트만 {@code @Bean} 으로 등록한다.
 */
@RestController
public class AuthProbeController {

    public static final String PATH = "/api/reservations/_probe/whoami";

    @GetMapping(PATH)
    public Map<String, Object> whoami(@AuthenticationPrincipal AuthenticatedUser user) {
        return Map.of("userId", user.userId().value(), "mdcTraceId", String.valueOf(MDC.get(TraceIdFilter.MDC_KEY)));
    }
}
