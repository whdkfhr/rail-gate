package com.railgate.reservation.application.expiry;

import com.railgate.reservation.expiry.ExpiryCursor;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 만료 배치를 주기적으로 부르는 <b>얇은 어댑터</b>.
 *
 * <p>하는 일은 셋뿐이다 — <b>주기 호출</b>, <b>커서 이어가기</b>, <b>요약 관측</b>.
 * 회수·quota 감소·그룹 격리·재시도는 전부 {@link ExpirySweeper} 구현의 책임이다.
 *
 * <h2>★ 커서를 여기서 보관하는 것이 서비스의 무상태 계약과 모순되지 않는 이유</h2>
 *
 * <p>{@code ExpireHoldsService} 는 진행 상태를 갖지 않는다 — 위치를 <b>인자로</b> 받고
 * {@code nextCursor} 로 <b>돌려준다</b>. 그 계약에서 위치를 보관하는 것은 <b>호출자</b>의 몫이며,
 * 이 어댑터가 바로 그 호출자다. 서비스는 여전히 어느 인스턴스가 불러도 같게 동작한다.
 *
 * <p>보관 범위는 <b>이 인스턴스의 메모리</b>다. 재시작하면 커서가 사라져 처음부터 시작하고,
 * 그것이 손해가 아니라 <b>지나친 실패 후보를 재확인하는 정상 경로</b>다.
 * 여러 인스턴스가 각자 다른 위치를 갖는 것도 문제가 아니다 — 정합성은 커서가 아니라
 * 좌석의 조건부 UPDATE 와 quota 트랜잭션이 보장한다 (TASK-002G-F 실험 D).
 *
 * <h2>★ 한 tick 은 {@code sweep} 을 한 번만 부른다</h2>
 *
 * <p>backlog 를 한 tick 에서 비우는 반복문을 두지 않는다. 그런 루프는 한 번의 실행 시간을
 * backlog 크기에 비례하게 만들고, 그 사이 잠금 경합과 커넥션 점유가 예측 불가능해진다.
 * 진행은 <b>tick 을 여러 번 거쳐</b> 이뤄진다.
 *
 * <ul>
 *   <li>{@code nextCursor} 가 있으면 <b>예산 소진</b>이다 → 다음 tick 이 그 커서로 이어간다.</li>
 *   <li>비어 있으면 <b>순회 완료</b>다 → 다음 tick 은 처음부터 시작한다.</li>
 *   <li><b>그룹 실패·손상 후보가 있어도 커서는 이어간다.</b> 그것은 그 그룹의 문제이고
 *       탐색 위치와는 무관하다. 멈추면 뒤의 정상 좌석이 다시 막힌다.</li>
 * </ul>
 *
 * <h2>★ 실패 시 재개 정책</h2>
 *
 * <p>호출이 예외로 끝나면 <b>커서를 전진시키지 않는다.</b> 어디까지 처리됐는지 알 수 없는데
 * 전진시키면 처리되지 않은 구간을 건너뛴다. 다음 tick 은 <b>같은 커서로 그 구간을 다시 본다</b> —
 * 회수는 조건부 UPDATE 라 이미 처리된 좌석은 0 건이 되므로 다시 보는 것이 안전하다.
 *
 * <p>예외를 스케줄러 밖으로 던지지 않는다. 이것은 Spring 이 반복 작업을 멈추기 때문이 아니다 —
 * Spring 의 기본 스케줄러({@code ThreadPoolTaskScheduler}·{@code ConcurrentTaskScheduler})는
 * 별도 {@code ErrorHandler} 가 없으면 반복 작업의 예외를 <b>로깅하고 억제</b>한다
 * ({@code TaskUtils.LOG_AND_SUPPRESS_ERROR_HANDLER}). 다음 실행은 그대로 예약된다.
 * 그 동작은 스케줄러 구성에 달려 있다 — 사용자 정의 {@code ErrorHandler} 는 다르게 처리할 수 있고,
 * JDK {@code ScheduledExecutorService} 를 직접 쓰면 예외가 난 반복 작업의 이후 실행이 억제된다.
 *
 * <p>여기서 직접 잡는 것은 실패 처리를 <b>어댑터가 명시적으로 소유</b>하기 위한 선택이다.
 * 커서를 전진시키지 않는 재개 정책, {@code outcome=failure} 계측, 이어가기 여부·요청 크기를
 * 담은 로그를 스케줄러 구성과 무관하게 같게 유지한다. 다만 <b>인터럽트는 삼키지 않는다</b> —
 * 플래그를 복원해 종료 요청이 전파되게 한다.
 *
 * <h2>이 어댑터가 하지 않는 것</h2>
 *
 * <ul>
 *   <li><b>{@code @Transactional} 을 붙이지 않는다.</b> 그룹마다 독립 트랜잭션이 열려야 하고,
 *       서비스는 활성 트랜잭션 안에서 호출되면 거절한다.</li>
 *   <li><b>저장소를 직접 부르지 않는다.</b> 포트도 아니고 서비스 하나만 안다.</li>
 *   <li><b>그룹 재시도를 다시 구현하지 않는다.</b> 서비스가 일시적 실패만 최대 3회 재시도한다.</li>
 *   <li><b>잡 락을 쓰지 않는다.</b> 다중 인스턴스의 정합성은 CAS·quota 트랜잭션이 보장하며,
 *       ShedLock 도입은 중복 실행 비율을 측정한 뒤 판단할 최적화다 (2G-F 실험 D).
 *       한 인스턴스 안의 겹침만 {@link #running} 으로 막는다.</li>
 *   <li><b>감사 로그(규칙 32)가 아니다.</b> 여기서 남기는 것은 <b>배치 요약</b>이다 —
 *       좌석 하나하나의 상태 전이를 {@code actor}·{@code reason}·{@code traceId} 와 함께
 *       기록하는 것은 별개이며 아직 없다.</li>
 * </ul>
 */
public class ExpirySweepScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExpirySweepScheduler.class);

    private static final String SWEEP_COUNTER = "railgate.expiry.sweep";
    private static final String RECLAIMED_COUNTER = "railgate.expiry.seats.reclaimed";
    private static final String GROUP_FAILED_COUNTER = "railgate.expiry.groups.failed";
    private static final String CORRUPT_COUNTER = "railgate.expiry.candidates.corrupt";

    private final ExpirySweeper sweeper;
    private final ExpirySweepProperties properties;
    private final MeterRegistry meters;

    /**
     * 한 인스턴스 안의 실행 중첩 방지. 다중 인스턴스는 대상이 아니다.
     *
     * <p>막힌 호출은 {@code outcome=skipped} 로 센다. 이 값은 <b>같은 어댑터에 tick 이 겹쳐
     * 들어와 건너뛴 횟수</b>다. {@code fixedDelay} 등록은 직전 실행이 끝난 뒤 다음 실행을
     * 예약하므로, 실행이 길다는 이유만으로는 겹치지 않고 {@code skipped} 도 오르지 않는다.
     * 배치가 주기를 못 따라가는지는 이 값으로 알 수 없다.
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 다음 tick 이 이어갈 위치. <b>이 인스턴스의 메모리</b>이며 재시작하면 사라진다.
     *
     * <p>{@code volatile} 인 이유는 스케줄러 스레드 풀이 tick 마다 다른 스레드를 쓸 수 있기
     * 때문이다. 쓰기는 {@link #running} 가드 안에서 한 번만 일어나므로 CAS 가 필요 없다.
     */
    private volatile Optional<ExpiryCursor> resumeFrom = Optional.empty();

    public ExpirySweepScheduler(
            ExpirySweeper sweeper, ExpirySweepProperties properties, MeterRegistry meters) {
        this.sweeper = Objects.requireNonNull(sweeper, "sweeper");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.meters = Objects.requireNonNull(meters, "meters");
        if (properties.isEnabled()) {
            // 켠 경우에만 검증한다 — 끄여 있는 설정의 값은 아무 의미가 없고,
            // 그것 때문에 기동이 막히면 끄는 것이 곧 위험이 된다.
            requireValid(properties);
        }
    }

    private static void requireValid(ExpirySweepProperties p) {
        Duration delay = p.getFixedDelay();
        if (delay == null || delay.isZero() || delay.isNegative()) {
            throw new IllegalArgumentException(
                    "만료 배치를 켜려면 fixedDelay 가 양수여야 한다: " + delay);
        }
        if (p.getPageSize() <= 0) {
            throw new IllegalArgumentException(
                    "만료 배치를 켜려면 pageSize 가 양수여야 한다: " + p.getPageSize());
        }
        if (p.getMaxPages() <= 0) {
            throw new IllegalArgumentException(
                    "만료 배치를 켜려면 maxPages 가 양수여야 한다: " + p.getMaxPages());
        }
    }

    /**
     * 한 번의 tick. {@code @Scheduled} 가 부르고, 테스트는 손으로 부른다.
     *
     * <p>{@code @Scheduled} 를 이 메서드에 붙이지 않는다 — 등록 여부가 {@code enabled} 에
     * 좌우되어야 하므로 구성 클래스가 조건부로 등록한다. 여기서는 tick 하나의 계약만 책임진다.
     */
    public void tick() {
        if (!properties.isEnabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            // 직전 tick 이 아직 돌고 있다. 겹쳐 돌리면 같은 후보를 두 번 읽어 헛일과 경합만 늘린다.
            meters.counter(SWEEP_COUNTER, "outcome", "skipped").increment();
            log.warn("만료 배치 tick 을 건너뛴다 — 직전 실행이 아직 진행 중이다");
            return;
        }
        try {
            runOnce();
        } finally {
            running.set(false);
        }
    }

    private void runOnce() {
        ExpirySweepRequest request = new ExpirySweepRequest(
                properties.getPageSize(), properties.getMaxPages(), resumeFrom);
        boolean resumed = resumeFrom.isPresent();

        ExpirySweepResult result;
        try {
            result = sweeper.sweep(request);
        } catch (RuntimeException e) {
            // ★ 커서를 전진시키지 않는다. 다음 tick 이 같은 구간을 다시 본다.
            meters.counter(SWEEP_COUNTER, "outcome", "failure").increment();
            log.error("만료 배치 실행이 실패했다. 커서를 전진시키지 않고 다음 tick 에서 같은 위치를 다시 본다"
                    + " (이어가기={}, pageSize={}, maxPages={})",
                    resumed, request.pageSize(), request.maxPages(), e);
            propagateInterrupt(e);
            return;
        }

        // ★ 그룹 실패·손상 후보가 있어도 커서는 이어간다. 멈추면 뒤의 정상 좌석이 다시 막힌다.
        resumeFrom = result.nextCursor();
        record(result, resumed);
    }

    /**
     * 인터럽트는 삼키지 않는다. 서비스는 재시도 대기 중 인터럽트를
     * {@code IllegalStateException(cause=InterruptedException)} 으로 감싸 올린다.
     */
    private static void propagateInterrupt(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return;
            }
            if (t.getCause() == t) {
                return;
            }
        }
    }

    /**
     * 요약 관측. <b>정상적인 stale 과 회수 0 건은 오류가 아니다</b> — 성공으로 센다.
     *
     * <p>메트릭 태그에는 <b>사용자·좌석·홀드 ID 나 예외 메시지를 넣지 않는다.</b>
     * 카디널리티가 터지고 식별자가 지표 저장소로 새어 나간다. 원인 분류({@code kind})만 태그다.
     */
    private void record(ExpirySweepResult result, boolean resumed) {
        meters.counter(SWEEP_COUNTER, "outcome", "success").increment();
        if (result.reclaimed() > 0) {
            meters.counter(RECLAIMED_COUNTER).increment(result.reclaimed());
        }
        result.failed().forEach(f ->
                meters.counter(GROUP_FAILED_COUNTER, "kind", f.kind().name()).increment());
        if (!result.corrupt().isEmpty()) {
            meters.counter(CORRUPT_COUNTER).increment(result.corrupt().size());
        }

        if (result.hasFailures()) {
            // 실패 그룹과 손상 후보는 조치가 필요하므로 WARN. 식별자는 로그에만 남긴다.
            log.warn("만료 배치 완료 — 후보 {}, 회수 {}, stale {}, 성공 그룹 {}, 실패 그룹 {}, 손상 후보 {},"
                    + " 시도 {}, 페이지 {}, 이어가기={}, 다음 커서={}",
                    result.candidatesFound(), result.reclaimed(), result.staleCandidates(),
                    result.succeeded().size(), result.failed(), result.corrupt(),
                    result.totalAttempts(), result.pagesRead(), resumed,
                    result.nextCursor().map(Object::toString).orElse("없음(순회 완료)"));
        } else {
            log.info("만료 배치 완료 — 후보 {}, 회수 {}, stale {}, 성공 그룹 {}, 시도 {}, 페이지 {},"
                    + " 이어가기={}, 다음 커서={}",
                    result.candidatesFound(), result.reclaimed(), result.staleCandidates(),
                    result.succeeded().size(), result.totalAttempts(), result.pagesRead(), resumed,
                    result.nextCursor().map(Object::toString).orElse("없음(순회 완료)"));
        }
    }
}
