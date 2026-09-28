package com.railgate.reservation.application.expiry;

import com.railgate.reservation.application.expiry.ExpirySweepResult.CorruptCandidate;
import com.railgate.reservation.application.expiry.ExpirySweepResult.GroupFailure;
import com.railgate.reservation.expiry.ExpiredSeat;
import com.railgate.reservation.expiry.ExpiryCursor;
import com.railgate.reservation.expiry.ExpiryCandidate;
import com.railgate.reservation.expiry.SeatExpiryPort;
import com.railgate.reservation.quota.QuotaReleaseOutcome;
import com.railgate.reservation.quota.UserHoldQuotaPort;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 만료 배치 한 번 — 만료 좌석 회수(I-10)와 quota 감소(I-12)를 <b>그룹마다</b> 같은 트랜잭션으로 묶는다.
 *
 * <h2>흐름</h2>
 *
 * <ol>
 *   <li><b>후보 조회</b> — DB {@code NOW(3)} 기준 HELD/PAYING 만료 후보를 소유자·회차와 함께 읽는다.
 *       잠금 없는 스냅숏이며 트랜잭션 밖이다. <b>키셋 커서로 페이지를 넘기며</b> 예산만큼 모은다</li>
 *   <li><b>그룹화</b> — {@code (saleEventId, userId)} 로 묶는다. {@code hold_id}·{@code held_by} 가
 *       없는 후보는 <b>손상으로 분리</b>해 회수하지 않고 결과에 실어 보낸다</li>
 *   <li><b>그룹마다 독립 트랜잭션</b> — quota 행 잠금 → 좌석 회수(PK 오름차순 벌크 UPDATE) →
 *       <b>실제 {@code affected_rows} 만큼</b> quota 감소 → 커밋</li>
 *   <li><b>실패 격리와 재시도</b> — 실패는 그 그룹만 롤백한다. 일시적 실패만 새 트랜잭션으로
 *       최대 3회 재시도하고, 성공한 그룹은 다시 실행하지 않는다</li>
 * </ol>
 *
 * <h2>★ 실패 후보가 앞을 막아도 뒤가 처리되는 이유</h2>
 *
 * <p>회수되지 않는 후보(손상·quota 부재)는 후보 집합에서 <b>사라지지 않는다.</b>
 * {@code (expires_at, id)} 정렬의 앞자리를 영구히 차지하므로, 첫 페이지만 읽으면
 * 그 뒤의 정상 좌석에 <b>끝내 도달하지 못한다.</b> 그룹별 트랜잭션 격리는
 * <b>후보 목록 안</b>의 그룹끼리만 보호하고, 목록에 들어오지도 못한 좌석은 보호하지 못한다.
 *
 * <p>그래서 한 실행이 {@link ExpiryCursor} 로 <b>여러 페이지를 넘어가며</b> 후보를 모은다.
 * 예산({@code maxPages})까지 모은 뒤 한 번에 그룹화·정산한다. 페이지마다 정산하지 않는 이유는
 * 한 그룹이 페이지 경계에 걸렸을 때 <b>한 그룹 = 한 트랜잭션 = 한 벌크 UPDATE</b> 계약이
 * 쪼개지지 않게 하기 위해서다. 모으는 양은 {@code pageSize × maxPages} 로 묶여 있다.
 *
 * <p><b>무한 루프도 전체 스캔도 아니다.</b> 예산이 소진되면 {@code nextCursor} 를 돌려주고
 * 멈춘다. <b>같은 순회를 이어가려면 그 커서를 다음 요청에 넣어야 하고</b>, 순회가 끝난 뒤
 * 처음부터 다시 시작하면 지나쳤던 실패 후보가 재확인된다. 자세한 계약은
 * {@link ExpirySweepRequest} 에 있다.
 *
 * <p><b>{@code pageSize} 는 벌크 UPDATE 크기의 상한이 아니다.</b> 여러 페이지를 모아
 * 그룹화하므로 한 그룹의 좌석이 여러 페이지에 걸쳐 있으면 한 번의 UPDATE 가 그보다 커진다.
 * 한 그룹의 좌석 수는 P-2(1인당 4석)가 실질적인 상한이고, 모으는 양 전체는
 * {@code pageSize × maxPages} 로 묶여 있다.
 *
 * <h2>★ 배치 전체가 아니라 그룹마다 트랜잭션인 이유</h2>
 *
 * <p>배치를 한 트랜잭션으로 묶으면 한 사용자의 drift(카운터 부재)가 <b>모든 사용자의 회수를
 * 막는다</b> (TASK-002G-F 실험 B). 그룹 안에서만 원자적이면 실패 그룹은 좌석도 카운터도
 * 바뀌지 않고, 정상 그룹은 남의 drift 때문에 롤백되지 않는다.
 *
 * <h2>★ 외부 트랜잭션에서 부르면 거절한다</h2>
 *
 * <p>그룹 트랜잭션은 {@code REQUIRED} 다. 바깥에 트랜잭션이 있으면 그룹들이 그 하나에 참여해
 * "그룹마다 독립 커밋" 이 사라지고, 호출자가 롤백하면 이미 성공으로 집계한 그룹까지 되돌아간다.
 * 반대로 {@code REQUIRES_NEW} 로 두면 호출자의 롤백 기대와 무관하게 독립 커밋되어 더 위험하다.
 * 그래서 <b>활성 트랜잭션이 있으면 아무것도 읽기 전에 거절</b>한다. 이 서비스는 배치 진입점이지
 * 유스케이스의 한 단계가 아니다.
 *
 * <h2>★ quota → seat 순서</h2>
 *
 * <p>그룹 트랜잭션은 quota 행을 {@code FOR UPDATE} 로 먼저 잠근 뒤 좌석을 갱신한다.
 * 선점·해제·확정과 같은 순서라 같은 사용자·회차의 트랜잭션은 quota 행에서 직렬화된다
 * (TASK-002G-B). 좌석들 사이는 저장소가 PK 오름차순으로 유지한다 (규칙 2).
 *
 * <h2>★ 카운터 부재는 좌석 UPDATE 전에 실패시킨다</h2>
 *
 * <p>해제·확정 경로(2H-B·2H-C)는 실제로 바뀐 뒤에 판단하지만, 만료 배치는
 * <b>2G-F 계약 3</b>대로 잠금 단계에서 실패시킨다. 배치는 사용자가 기다리는 요청이 아니라
 * 반복 실행이므로, 카운터 없는 그룹의 좌석을 UPDATE 했다 되돌리는 비용을 매번 치를 이유가 없다.
 * 행을 만들거나 값을 보정하지 않는다 — drift 의 증거를 남긴다.
 *
 * <h2>★ 성공 건수는 커밋 뒤에만 센다</h2>
 *
 * <p>{@link ExpirySweepResult#succeeded} 는 그룹 트랜잭션이 커밋을 마친 뒤 기록한다.
 * 롤백된 회수를 성공으로 보고하지 않는다.
 *
 * <h2>이 서비스가 하지 않는 것</h2>
 *
 * <ul>
 *   <li><b>주기 실행</b> — {@code @Scheduled} 배선이 없다. 명시적으로 부르는 한 번의 배치다.
 *       <b>반복 실행의 진행 보장은 호출 계약 위에서만 성립한다.</b>
 *       {@code sweep(int)} 는 매번 처음부터 조회하므로, <b>실패 후보가 실행 예산을 채우면
 *       반복 호출만으로는 뒤의 정상 좌석에 도달하지 못한다.</b> 진행은 예산이 소진됐을 때
 *       {@code nextCursor} 를 다음 요청에 전달할 때만 보장된다. 순회가 완료된 뒤 처음부터
 *       다시 시작하는 것은 실패 후보 재확인을 위한 것이다. 서비스는 스스로 다시 부르지 않는다.
 *       다중 스위퍼는 잡 락 없이도 정합성이 유지되며(2G-F 실험 D) 잡 락은 측정 후 판단할 최적화다</li>
 *   <li><b>감사 로그·메트릭</b> — 손상 후보와 실패 그룹은 결과로 돌려줄 뿐 아무 데도 기록되지 않는다.
 *       규칙 32·35 는 후속이다</li>
 *   <li><b>backfill</b> — 카운터 없는 그룹은 실패로 남는다. 활성화 전 절차(2G-G §4)의 몫이다</li>
 * </ul>
 */
@Service
public class ExpireHoldsService {

    /** 최초 시도 포함 최대 시도 횟수. 백오프는 최대 2회 (TASK-002G-F 계약 5). */
    static final int MAX_ATTEMPTS = 3;

    private final SeatExpiryPort expiry;
    private final UserHoldQuotaPort quota;
    private final TransactionTemplate groupTransaction;
    private final RetryBackoff backoff;

    public ExpireHoldsService(
            SeatExpiryPort expiry,
            UserHoldQuotaPort quota,
            PlatformTransactionManager transactionManager,
            RetryBackoff backoff) {
        this.expiry = Objects.requireNonNull(expiry, "expiry");
        this.quota = Objects.requireNonNull(quota, "quota");
        this.groupTransaction = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.groupTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.groupTransaction.setName("expire-holds-group");
        this.backoff = Objects.requireNonNull(backoff, "backoff");
    }

    /**
     * 만료 배치를 한 번 실행한다. <b>처음부터</b> 보며 기본 페이지 예산을 쓴다.
     *
     * @param pageSize 후보 SQL 한 번의 {@code LIMIT}. 한 실행은 이 크기의 페이지를
     *                 최대 {@link ExpirySweepRequest#DEFAULT_MAX_PAGES} 개까지 읽는다 —
     *                 <b>검사 후보 수의 상한은 그 곱이다</b>
     * @return 그룹별 성공·실패·손상 후보. 회수 수는 커밋된 그룹의 합이다
     * @throws IllegalArgumentException {@code pageSize} 가 0 이하
     * @throws IllegalStateException    활성 트랜잭션 안에서 호출된 경우 — 아무것도 읽기 전에 거절
     *                                  — 또는 백오프 대기 중 인터럽트(원인으로 {@link InterruptedException},
     *                                  인터럽트 플래그는 복원된다). 이미 커밋된 그룹은 그대로다
     */
    public ExpirySweepResult sweep(int pageSize) {
        return sweep(ExpirySweepRequest.fromStart(pageSize));
    }

    /**
     * 만료 배치를 한 번 실행한다. 탐색 범위와 예산을 명시한다.
     *
     * <p>{@code request.startAfter()} 가 비어 있으면 <b>처음부터</b> 본다 — 지난 실행에서
     * 지나쳤던 실패 후보를 다시 확인한다. 값이 있으면 그 뒤부터 이어간다.
     *
     * @return {@link ExpirySweepResult#nextCursor} 가 있으면 예산이 소진됐고 뒤에 더 있을 수
     *         있다는 뜻이다. <b>이 서비스는 그 값을 보관하지 않는다</b> — 이어가려면 호출자가
     *         {@link ExpirySweepRequest#continueAfter} 로 되돌려 줘야 한다
     * @throws IllegalStateException 활성 트랜잭션 안에서 호출된 경우, 또는 백오프 중 인터럽트
     */
    public ExpirySweepResult sweep(ExpirySweepRequest request) {
        Objects.requireNonNull(request, "request");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "만료 배치는 활성 트랜잭션 안에서 실행할 수 없다. 그룹마다 독립 커밋해야 하는데 "
                            + "바깥 트랜잭션에 참여하면 그 계약이 사라지고, 호출자의 롤백이 "
                            + "이미 성공으로 집계한 그룹까지 되돌린다");
        }

        Traversal traversal = collect(request);
        Grouped grouped = group(traversal.candidates);

        Map<QuotaGroupKey, Integer> succeeded = new LinkedHashMap<>();
        List<GroupFailure> failed = new ArrayList<>();
        int totalAttempts = 0;
        int reclaimed = 0;
        int stale = 0;

        for (Map.Entry<QuotaGroupKey, List<ExpiryCandidate>> entry : grouped.groups.entrySet()) {
            QuotaGroupKey key = entry.getKey();
            List<ExpiryCandidate> group = entry.getValue();
            Settlement settlement = settleWithRetry(key, group);
            totalAttempts += settlement.attempts;
            if (settlement.failure == null) {
                // 커밋이 끝난 뒤에만 성공으로 센다.
                succeeded.put(key, settlement.reclaimed);
                reclaimed += settlement.reclaimed;
                stale += group.size() - settlement.reclaimed;
            } else {
                failed.add(settlement.failure);
            }
        }

        return new ExpirySweepResult(
                traversal.candidates.size(), reclaimed, succeeded, failed, stale, grouped.corrupt,
                totalAttempts, traversal.pagesRead, traversal.nextCursor);
    }

    private record Traversal(List<ExpiredSeat> candidates, int pagesRead, Optional<ExpiryCursor> nextCursor) {
    }

    /**
     * 예산만큼 후보 페이지를 넘기며 모은다. <b>트랜잭션 밖</b>이고 잠금을 잡지 않는다.
     *
     * <p>페이지가 {@code pageSize} 보다 적게 오면 뒤에 후보가 없다는 뜻이므로 순회가 끝난다
     * ({@code nextCursor} 비어서 반환). 예산을 다 쓰고도 마지막 페이지가 꽉 찼으면
     * 마지막으로 본 위치를 돌려준다.
     *
     * <p>커서는 <b>성공·실패·손상을 가리지 않고 본 것 전부</b>를 지나 전진한다. 그래서 회수되지
     * 않는 후보가 다음 페이지를 막지 못한다. 회수되어 사라진 행이 있어도 키셋이라 위치가
     * 흔들리지 않는다.
     */
    private Traversal collect(ExpirySweepRequest request) {
        List<ExpiredSeat> collected = new ArrayList<>();
        Optional<ExpiryCursor> cursor = request.startAfter();
        int pagesRead = 0;

        while (pagesRead < request.maxPages()) {
            List<ExpiredSeat> page = expiry.findExpiredSeats(request.pageSize(), cursor);
            pagesRead++;
            collected.addAll(page);
            if (page.size() < request.pageSize()) {
                // 뒤에 후보가 없다 — 순회 완료.
                return new Traversal(collected, pagesRead, Optional.empty());
            }
            cursor = Optional.of(page.get(page.size() - 1).cursor());
        }
        // 예산 소진. 마지막 페이지가 꽉 찼으므로 뒤에 더 있을 수 있다.
        return new Traversal(collected, pagesRead, cursor);
    }

    private record Settlement(int reclaimed, int attempts, GroupFailure failure) {
    }

    /**
     * 한 그룹을 정산한다. 일시적 실패면 <b>새 트랜잭션으로</b> 최대 {@link #MAX_ATTEMPTS} 회.
     * 백오프는 실패한 트랜잭션이 끝난 뒤, 다음 트랜잭션이 시작하기 전에 한다.
     */
    private Settlement settleWithRetry(QuotaGroupKey key, List<ExpiryCandidate> group) {
        RuntimeException lastFailure = null;
        GroupFailureKind lastKind = GroupFailureKind.UNKNOWN;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                Integer reclaimed = groupTransaction.execute(status -> settleGroup(key, group));
                return new Settlement(reclaimed == null ? 0 : reclaimed, attempt, null);
            } catch (RuntimeException e) {
                lastFailure = e;
                lastKind = GroupFailureClassifier.classify(e);
                if (!lastKind.isRetryable()) {
                    return new Settlement(0, attempt, failure(key, lastKind, e, attempt));
                }
                if (attempt == MAX_ATTEMPTS) {
                    break;
                }
                awaitBackoff(attempt);
            }
        }
        return new Settlement(0, MAX_ATTEMPTS, failure(key, lastKind, lastFailure, MAX_ATTEMPTS));
    }

    /** 그룹 트랜잭션 본문 — quota 잠금 → 좌석 회수 → 실제 수만큼 감소. */
    private int settleGroup(QuotaGroupKey key, List<ExpiryCandidate> group) {
        // quota 를 좌석보다 먼저 잠근다. 행이 없으면 좌석을 건드리기 전에 이 그룹을 실패시킨다 (2G-F 계약 3).
        if (quota.lockRow(key.saleEventId(), key.userId()).isEmpty()) {
            throw new GroupSettlementException(GroupFailureKind.QUOTA_ROW_MISSING,
                    "quota 행이 없다: " + key + " — 좌석은 이 사용자 것인데 카운터가 없다. 회수하지 않고 롤백한다");
        }
        int affected = expiry.expire(group);
        if (affected == 0) {
            // 후보가 전부 stale 했다. 줄일 것이 없으므로 감소 SQL 을 보내지 않는다.
            return 0;
        }
        if (quota.release(key.saleEventId(), key.userId(), affected) != QuotaReleaseOutcome.RELEASED) {
            throw new GroupSettlementException(GroupFailureKind.QUOTA_DECREASE_REJECTED,
                    "quota 감소 거절: " + key + ", 실제 회수 " + affected + "석 — 카운터가 실제보다 작다. 회수까지 롤백한다");
        }
        return affected;
    }

    private void awaitBackoff(int attempt) {
        try {
            backoff.await(attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "만료 배치가 재시도 대기 중 인터럽트됐다. 이미 커밋된 그룹은 그대로이고 나머지는 처리하지 않았다", e);
        }
    }

    private static GroupFailure failure(QuotaGroupKey key, GroupFailureKind kind, Throwable e, int attempts) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return new GroupFailure(key, kind, root.getClass().getSimpleName() + ": " + root.getMessage(), attempts);
    }

    private record Grouped(Map<QuotaGroupKey, List<ExpiryCandidate>> groups, List<CorruptCandidate> corrupt) {
    }

    /**
     * 후보를 그룹으로 묶는다. 손상 후보는 그룹에 넣지 않고 따로 모은다 — 사용자 0 으로 바꾸면
     * 없는 카운터를 건드리고, 숨기면 그 좌석이 영원히 남는다 (2G-F 실험 F).
     */
    private static Grouped group(List<ExpiredSeat> candidates) {
        Map<QuotaGroupKey, List<ExpiryCandidate>> groups = new TreeMap<>();
        List<CorruptCandidate> corrupt = new ArrayList<>();
        for (ExpiredSeat seat : candidates) {
            if (!seat.isSettleable()) {
                corrupt.add(new CorruptCandidate(seat.seatId(), seat.corruptionReason().orElse("손상")));
                continue;
            }
            groups.computeIfAbsent(
                            new QuotaGroupKey(seat.saleEventId(), seat.heldBy().orElseThrow()),
                            k -> new ArrayList<>())
                    .add(seat.toCandidate());
        }
        groups.values().forEach(list -> list.sort(Comparator.comparingLong(c -> c.seatId().value())));
        return new Grouped(groups, corrupt);
    }
}
