package com.railgate.reservation.api.hold;

import static org.assertj.core.api.Assertions.assertThat;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.api.security.AdmissionContext;
import com.railgate.reservation.api.security.AuthenticatedUser;
import com.railgate.reservation.application.hold.HoldSeatsService;
import com.railgate.reservation.application.idempotency.IdempotentHoldService;
import com.railgate.reservation.idempotency.IdempotencyKeyPort;
import com.railgate.reservation.quota.HoldAttribution;
import com.railgate.reservation.saleevent.SaleEventId;
import com.railgate.reservation.saleevent.SaleEventScopePort;
import com.railgate.reservation.seat.SeatId;
import com.railgate.reservation.support.MySqlSpringTestSupport;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * ★ Task 2J-D — HTTP 요청만으로는 주입할 수 없는 장애 경계를 <b>운영 컨트롤러</b>와 실제 MySQL·운영 빈으로 확인한다.
 *
 * <ul>
 *   <li><b>F5 · D4</b> — 사전 회차 대조와 TX1 사이에 좌석의 회차 소속이 바뀌는 경우 (정상 경로에는 없다. DB 직접 SQL 로만 생긴다).
 *       사전 조회 포트를 감싸 사전 조회 직후 좌석을 다른 회차의 운행편으로 옮긴다</li>
 *   <li><b>F2</b> — 커밋 결과 불명. 트랜잭션 관리자가 TX1 의 COMMIT 을 <b>실행한 뒤</b> 실패를 보고한다.
 *       실제 COMMIT 중 연결 단절은 재현하지 않았다</li>
 * </ul>
 *
 * <p>필터 체인은 거치지 않는다 — 인증·입장 정보는 필터가 남기는 것과 같은 요청 속성·principal 로 넣는다. 필터 체인을 거친 계약은
 * {@code HoldControllerIntegrationTest} 가 확인한다.
 */
@DisplayName("Task 2J-D — 선점 HTTP 장애 경계 (운영 컨트롤러·MySQL)")
class HoldControllerFaultTest extends MySqlSpringTestSupport {

    private static final long USER = 6161L;
    private static final long SCHEDULE = 8_950L;
    private static final long OTHER_EVENT = 9_850_000L;
    private static final long OTHER_SCHEDULE = 8_951L;
    private static final String TRACE_ATTRIBUTE = "com.railgate.reservation.api.trace.TraceIdFilter.traceId";

    @Autowired
    private IdempotentHoldService holds;

    @Autowired
    private HoldSeatsService holdSeats;

    @Autowired
    private IdempotencyKeyPort keyPort;

    @Autowired
    private SaleEventScopePort saleEventScope;

    @Autowired
    private MeterRegistry meters;

    private int traceSeq;

    @BeforeEach
    void reset() {
        jdbc().execute("DELETE FROM idempotency_key");
    }

    private MockHttpServletResponse call(HoldController controller, String key, long... seats) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", HoldController.PATH);
        request.setAttribute(AdmissionContext.class.getName(), new AdmissionContext(USER, FIXTURE_SALE_EVENT_ID));
        request.setAttribute(TRACE_ATTRIBUTE, "trace-" + (traceSeq++));
        request.addHeader("Idempotency-Key", key);
        request.setContentType("application/json");
        StringBuilder body = new StringBuilder("{\"seatIds\":[");
        for (int i = 0; i < seats.length; i++) {
            body.append(i == 0 ? "" : ",").append(seats[i]);
        }
        request.setContent(body.append("]}").toString().getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.hold(new AuthenticatedUser(new UserId(USER)), request, response);
        return response;
    }

    private Map<String, Object> keyRow(String key) {
        return jdbc().queryForMap("""
                SELECT state, fence, result_type,
                       CAST(lease_until IS NOT NULL AND lease_until <= NOW(3) AS SIGNED) AS lease_expired
                  FROM idempotency_key WHERE user_id = ? AND operation = 'HOLD_SEATS' AND idempotency_key = ?
                """, USER, key);
    }

    private static String code(MockHttpServletResponse response) throws Exception {
        return (String) JsonParserFactory.getJsonParser().parseMap(response.getContentAsString(StandardCharsets.UTF_8))
                .get("code");
    }

    /** 사전 조회는 실제 포트로 하고, 그 직후 좌석을 다른 회차의 운행편으로 옮긴다 — TX1 은 바뀐 소속을 본다. */
    private SaleEventScopePort movingAfterPreCheck(long seat) {
        return new SaleEventScopePort() {
            @Override
            public SaleEventId resolve(List<SeatId> seatIds) {
                SaleEventId resolved = saleEventScope.resolve(seatIds);
                jdbc().update("UPDATE seat_inventory SET schedule_id = ? WHERE id = ?", OTHER_SCHEDULE, seat);
                return resolved;
            }

            @Override
            public Optional<SaleEventId> resolveActiveHold(HoldId holdId, UserId userId) {
                return saleEventScope.resolveActiveHold(holdId, userId);
            }

            @Override
            public Optional<HoldAttribution> resolvePayingSeat(SeatId seatId, HoldId holdId) {
                return saleEventScope.resolvePayingSeat(seatId, holdId);
            }
        };
    }

    /**
     * ★ F5 — TX1 의 회차 재대조(D4)가 걸리면 403 {@code ADMISSION_EVENT_MISMATCH} 이고, 결과를 저장하지 않으며 좌석·quota 를
     * 바꾸지 않는다. 5xx·서버 오류율·조정자 failed 에 들어가지 않는다. 키 행은 결과 없는 진행 중 + 만료된 리스로 남아 같은 키
     * 재요청이 인수한다.
     */
    @Test
    void TX1_회차_불일치는_403_이고_결과_미저장이며_같은_키는_인수한다() throws Exception {
        long a = insertAvailableSeat(SCHEDULE, "1A");
        ensureTrainSchedule(OTHER_SCHEDULE, OTHER_EVENT);
        SimpleMeterRegistry local = new SimpleMeterRegistry();
        HoldController controller = new HoldController(holds, movingAfterPreCheck(a), local);
        double failedBefore = count(meters, "railgate.idempotency.hold.calls", "result", "failed");
        String key = "d4-http-key-0001";

        MockHttpServletResponse response = call(controller, key, a);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(code(response)).isEqualTo("ADMISSION_EVENT_MISMATCH");
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).contains("\"traceId\":\"trace-0\"");
        assertThat(statusOf(a)).isEqualTo("AVAILABLE");
        assertThat(quotaRowCount()).isZero();
        assertThat(keyRow(key)).containsEntry("state", "IN_PROGRESS").containsEntry("lease_expired", 1L);
        assertThat(keyRow(key).get("result_type")).isNull();
        assertThat(count(meters, "railgate.idempotency.hold.calls", "result", "failed")).isEqualTo(failedBefore);
        assertThat(count(local, "railgate.hold.http.responses", "status", "403", "code", "ADMISSION_EVENT_MISMATCH",
                "category", "client_error")).isEqualTo(1);
        assertThat(local.find("railgate.hold.http.responses").tags("category", "server_error").counters()).isEmpty();

        // 소속을 되돌린 뒤 같은 키·같은 요청은 인수해 실행한다.
        jdbc().update("UPDATE seat_inventory SET schedule_id = ? WHERE id = ?", SCHEDULE, a);
        MockHttpServletResponse retried = call(new HoldController(holds, saleEventScope, local), key, a);
        assertThat(retried.getStatus()).isEqualTo(201);
        assertThat(keyRow(key)).containsEntry("state", "COMPLETED").containsEntry("fence", 2L);
    }

    /**
     * ★ F2 — 커밋 결과 불명은 500 이고, 실제로는 커밋됐으므로 같은 키 재요청은 저장된 성공을 재생한다 (선점 1회).
     */
    @Test
    void 커밋_결과_불명은_500_이고_재요청은_저장된_성공을_재생한다() throws Exception {
        long a = insertAvailableSeat(SCHEDULE, "1A");
        FailingNthCommitTransactionManager unknown = new FailingNthCommitTransactionManager(dataSource(), 2);
        IdempotentHoldService unknownCommit = new IdempotentHoldService(
                keyPort, holdSeats, unknown, Duration.ofSeconds(30), new SimpleMeterRegistry());
        SimpleMeterRegistry local = new SimpleMeterRegistry();
        String key = "unknown-commit-0001";

        MockHttpServletResponse failed = call(new HoldController(unknownCommit, saleEventScope, local), key, a);

        assertThat(failed.getStatus()).isEqualTo(500);
        assertThat(code(failed)).isEqualTo("INTERNAL_ERROR");
        assertThat(failed.getHeader("Retry-After")).isNull();
        assertThat(failed.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("주입된");
        assertThat(count(local, "railgate.hold.http.responses", "status", "500", "code", "INTERNAL_ERROR",
                "category", "server_error")).isEqualTo(1);
        assertThat(keyRow(key)).as("실제로는 커밋됐다").containsEntry("state", "COMPLETED");

        MockHttpServletResponse replayed = call(new HoldController(holds, saleEventScope, local), key, a);
        assertThat(replayed.getStatus()).isEqualTo(201);
        assertThat(heldSeatCount()).as("선점 1회").isEqualTo(1);
        assertThat(replayed.getHeader("Location")).isEqualTo(HoldController.PATH + "/" + holdIdOf(a));
    }

    /** 본문을 다 받지 못한 읽기 실패(클라이언트 중단·시간 초과)는 서버 장애가 아니라 400 이고, 요청이 끝나면 MDC 를 비운다. */
    @Test
    void 본문_읽기_실패는_400_이고_MDC_를_남기지_않는다() throws Exception {
        long a = insertAvailableSeat(SCHEDULE, "1A");
        SimpleMeterRegistry local = new SimpleMeterRegistry();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", HoldController.PATH) {
            @Override
            public jakarta.servlet.ServletInputStream getInputStream() {
                return new jakarta.servlet.ServletInputStream() {
                    @Override
                    public int read() throws java.io.IOException {
                        throw new java.io.IOException("주입된 클라이언트 중단");
                    }

                    @Override
                    public boolean isFinished() {
                        return false;
                    }

                    @Override
                    public boolean isReady() {
                        return true;
                    }

                    @Override
                    public void setReadListener(jakarta.servlet.ReadListener listener) {
                    }
                };
            }
        };
        request.setAttribute(AdmissionContext.class.getName(), new AdmissionContext(USER, FIXTURE_SALE_EVENT_ID));
        request.setAttribute(TRACE_ATTRIBUTE, "trace-io");
        request.addHeader("Idempotency-Key", "io-failure-0001");
        request.setContentType("application/json");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new HoldController(holds, saleEventScope, local).hold(new AuthenticatedUser(new UserId(USER)), request, response);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(code(response)).isEqualTo("INVALID_REQUEST");
        assertThat(count(local, "railgate.hold.http.responses", "status", "400", "code", "INVALID_REQUEST",
                "category", "client_error")).isEqualTo(1);
        assertThat(org.slf4j.MDC.get("userId")).isNull();
        assertThat(statusOf(a)).isEqualTo("AVAILABLE");
    }

    private static double count(MeterRegistry registry, String name, String... tags) {
        Counter counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    /** n 번째 COMMIT 을 실제로 실행한 뒤 실패를 보고한다 (TX0 = 1번째, TX1 = 2번째). */
    static final class FailingNthCommitTransactionManager extends DataSourceTransactionManager {

        private static final long serialVersionUID = 1L;
        private final transient AtomicInteger commits = new AtomicInteger();
        private final int failAt;

        FailingNthCommitTransactionManager(DataSource dataSource, int failAt) {
            super(dataSource);
            this.failAt = failAt;
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            super.doCommit(status);
            if (commits.incrementAndGet() == failAt) {
                throw new TransactionSystemException("주입된 커밋 결과 불명 (COMMIT 은 실행됨)");
            }
        }
    }
}
