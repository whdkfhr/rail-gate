package com.railgate.reservation.infra.quota;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.expiry.ExpiryCandidate;
import com.railgate.reservation.infra.seat.JdbcSeatExpiryRepository;
import com.railgate.reservation.seat.SeatId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntConsumer;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * <b>테스트 전용</b> 만료 배치 스위퍼 — 운영 계약 후보 (Task 2G-F).
 *
 * <h2>운영 코드가 아니다</h2>
 *
 * <p>운영 {@link JdbcSeatExpiryRepository} 를 <b>바꾸지 않고 그대로 재사용</b>한다.
 * 바뀐 것은 호출 방식과 트랜잭션 경계뿐이다. 운영 {@code ExpiryCandidate} 도 건드리지 않는다 —
 * {@code held_by} 와 {@code sale_event_id} 는 이 클래스의 후보 조회가 따로 읽는다 (실험 A).
 *
 * <h2>2G-C 의 coordinator 와 무엇이 다른가</h2>
 *
 * <p>{@code Task2gExpiryQuotaCoordinator} 는 <b>배치 전체가 한 트랜잭션</b>이고, 한 그룹이
 * 실패하면 예외를 던져 전부 롤백한다. 이 클래스는 그 방식({@link TransactionMode#BATCH_WIDE})과
 * <b>사용자 그룹별 트랜잭션</b>({@link TransactionMode#PER_USER_GROUP})을 나란히 두고
 * 결과를 같은 타입으로 돌려준다. 그래야 두 대안을 같은 시나리오에서 비교할 수 있다.
 *
 * <h2>이미 결정된 것을 다시 정하지 않는다</h2>
 *
 * <ul>
 *   <li>quota 범위는 {@code sale_event_id} (2G-D). 이제 실제 {@code train_schedule} 조인으로 얻는다</li>
 *   <li>잠금 순서는 quota → seat, 키는 {@code (saleEventId, userId)} 오름차순 (2G-B)</li>
 *   <li>그룹 안 좌석은 {@code seatId} 오름차순 (규칙 2)</li>
 *   <li>감소량은 후보 수가 아니라 실제 {@code affected_rows} (2G-C)</li>
 * </ul>
 */
final class Task2gfExpirySweeper {

    /** 최초 시도를 포함한 최대 시도 횟수. 실험 E 의 가설값이다. */
    static final int MAX_ATTEMPTS = 3;

    enum TransactionMode {
        /** 배치 전체가 하나의 트랜잭션. 한 그룹이 실패하면 전부 롤백된다. */
        BATCH_WIDE,
        /** {@code (saleEventId, userId)} 그룹마다 독립 트랜잭션. */
        PER_USER_GROUP
    }

    /**
     * 그룹 실패의 원인.
     *
     * <p>재시도 여부만 정하는 것이 아니라 <b>관측값의 이름</b>이기도 하다.
     * {@code DEADLOCK} 과 {@code LOCK_WAIT_TIMEOUT} 은 운영에서 서로 다른 신호이며
     * 대응도 다르다. <b>근거 없이 둘 중 하나로 몰면 엉뚱한 곳을 고치게 된다.</b>
     *
     * <p>그래서 원인을 특정하지 못한 일시적 실패는 그 두 이름을 빌려 쓰지 않고
     * {@code TRANSIENT_*} 로 따로 센다. 넷 다 재시도 대상이라는 점은 같다 —
     * <b>재시도 여부와 원인 분류는 별개의 문제다.</b>
     */
    enum FailureKind {
        QUOTA_ROW_MISSING,
        QUOTA_DECREASE_REJECTED,
        CORRUPT_CANDIDATE,

        /**
         * MySQL 1213. <b>잠금 순서가 어긋났다는 신호다.</b>
         *
         * <p>이 값이 늘면 quota → seat 순서(2G-B)나 좌석 id 오름차순(규칙 2)이 깨진 곳을
         * 찾아야 한다. <b>그래서 근거 없이 이 이름을 붙이면 안 된다</b> — 엉뚱한 곳을 뒤지게 된다.
         */
        DEADLOCK,

        /**
         * MySQL 1205. <b>잠금이 오래 잡혀 있었다는 신호다.</b>
         *
         * <p>이 값이 늘면 트랜잭션이 길어졌거나 {@code innodb_lock_wait_timeout}(3초, 규칙 9)이
         * 짧은지 봐야 한다. {@code DEADLOCK} 과는 <b>대응이 완전히 다르다.</b>
         */
        LOCK_WAIT_TIMEOUT,

        /**
         * 드라이버가 트랜잭션 롤백을 알렸지만 <b>vendor code 가 없어 원인을 특정할 수 없다.</b>
         *
         * <p>{@code SQLTransactionRollbackException} 은 deadlock 뿐 아니라 직렬화 실패 등
         * 다른 일시적 롤백도 나타낸다. 이것을 {@code DEADLOCK} 으로 세면
         * <b>데드락 지표가 부풀려져</b> 잠금 순서를 의심하게 만든다.
         */
        TRANSIENT_TRANSACTION_ROLLBACK,

        /**
         * Spring 이 잠금 실패로 번역했지만 <b>vendor code 가 없어 원인을 특정할 수 없다.</b>
         *
         * <p>{@code PessimisticLockingFailureException} 이 알려 주는 것은 <b>"잠금을 얻지
         * 못했다" 까지다.</b> 그것이 1205 처럼 잠금 보유 시간이 길어서인지, 1213 처럼 순서가
         * 어긋나서인지, 다른 이유인지는 말해 주지 않는다.
         *
         * <p>이것을 {@code LOCK_WAIT_TIMEOUT} 으로 세면 <b>timeout 지표가 부풀려져</b>
         * 트랜잭션 길이나 타임아웃 설정을 의심하게 만든다 — 원인이 거기에 없을 수도 있는데도.
         *
         * <p>{@link #TRANSIENT_TRANSACTION_ROLLBACK} 과도 구분한다. 그쪽은 <b>드라이버가
         * 롤백을 알린</b> 경우이고 이쪽은 <b>Spring 이 잠금 실패로 번역한</b> 경우라,
         * 나오는 지점과 조사할 방향이 다르다.
         */
        TRANSIENT_LOCK_FAILURE,

        UNKNOWN
    }

    /** quota 카운터 행을 가리키는 키. 잠금 순서를 정하려면 정렬 가능해야 한다. */
    record GroupKey(long saleEventId, long userId) implements Comparable<GroupKey> {
        @Override
        public int compareTo(GroupKey other) {
            int byEvent = Long.compare(saleEventId, other.saleEventId);
            return byEvent != 0 ? byEvent : Long.compare(userId, other.userId);
        }

        @Override
        public String toString() {
            return "(회차=%d, 사용자=%d)".formatted(saleEventId, userId);
        }
    }

    /** 실패한 그룹. <b>조용히 넘어가지 않고 호출자가 볼 수 있게 남긴다.</b> */
    record GroupFailure(GroupKey key, FailureKind kind, String detail, int attempts) {
    }

    /**
     * 정산할 수 없는 손상 후보.
     *
     * <p>{@code held_by} 가 NULL 인 활성 좌석이 대표적이다. 소유자를 모르므로 어느 카운터를
     * 줄여야 할지 알 수 없다. <b>추측하지 않고 회수하지도 않는다.</b>
     */
    record CorruptCandidate(long seatId, String reason) {
    }

    static final class QuotaRowMissingException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        QuotaRowMissingException(String message) {
            super(message);
        }
    }

    static final class QuotaDecreaseRejectedException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        QuotaDecreaseRejectedException(String message) {
            super(message);
        }
    }

    static final class CorruptCandidateException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        CorruptCandidateException(String message) {
            super(message);
        }
    }

    /**
     * 후보 조회 — quota 범위를 <b>실제 조인으로</b> 얻는다.
     *
     * <p>2G-E-B2 가 만든 {@code train_schedule} 덕분에 이제 회차를 추측하지 않아도 된다.
     * {@code STRAIGHT_JOIN} 으로 좌석을 구동 테이블로 고정하는 이유도 그 문서 §5 와 같다 —
     * 통계가 최신이 아니면 조인 순서가 뒤집혀 전체 좌석 수에 비례하는 비용이 된다.
     *
     * <p><b>{@code held_by IS NOT NULL} 을 붙이지 않는다.</b> 붙이면 소유자 없는 만료 좌석이
     * 조용히 후보에서 빠지고, 그 좌석은 영원히 회수되지 않으면서 아무도 그 사실을 모른다.
     * 손상 행은 <b>후보로 읽어서 명시적으로 분리</b>한다 (실험 F).
     *
     * <p><b>{@code FORCE INDEX} 는 운영 {@code JdbcSeatExpiryRepository} 와 같은 의도다.</b>
     * {@code (expires_at, id)} 인덱스(V2)를 강제하지 않으면 옵티마이저가 다른 계획을 고를 수
     * 있고, 그중에는 {@code ORDER BY expires_at, id} 를 <b>filesort 로 처리하는 계획도 있다.</b>
     * 그렇게 되면 {@code LIMIT} 이 조기 종료하지 못해 만료 후보 전부를 읽어 정렬한 뒤 잘라내고,
     * 배치 비용이 <b>배치 크기가 아니라 backlog 크기</b>에 비례한다.
     * V2 마이그레이션 주석이 다른 인덱스((status, expires_at))에서 그 비용을 실측했다.
     *
     * <p><b>다만 이 SQL 에서 힌트를 뺐을 때 실제로 어떤 계획이 선택되는지는 측정하지 않았다.</b>
     * "힌트가 없으면 반드시 filesort 가 된다" 고 단정할 근거는 없다. 여기서 강제하는 이유는
     * 옵티마이저의 선택에 계약을 맡기지 않기 위해서이며(규칙 3), 조인이 붙어 선택지가 늘어난
     * 만큼 그 필요가 더 커졌다는 판단이다.
     */
    static final String CANDIDATE_INDEX = "idx_seat_inventory_expiry_candidate";

    /**
     * <b>테스트가 이 상수를 그대로 EXPLAIN 한다.</b> 복사본이 조용히 어긋나는 것을 막는다
     * ({@code JdbcSeatExpiryRepositoryTest} 와 같은 방식).
     */
    static final String FIND_CANDIDATES_SQL = """
            SELECT s.id, s.hold_id, s.held_by, ts.sale_event_id
              FROM seat_inventory s FORCE INDEX (idx_seat_inventory_expiry_candidate)
              STRAIGHT_JOIN train_schedule ts ON ts.id = s.schedule_id
             WHERE s.status     IN ('HELD', 'PAYING')
               AND s.expires_at <= NOW(3)
             ORDER BY s.expires_at ASC, s.id ASC
             LIMIT ?
            """;

    private final JdbcTemplate jdbc;
    private final JdbcSeatExpiryRepository expiryRepository;
    private final Task2gQuotaCounter quota;
    private final TransactionTemplate transactions;
    private final TransactionMode mode;

    /** 재시도 사이의 대기. 테스트는 0 지연을 주입해 느려지지 않게 한다 (실험 E). */
    private final IntConsumer backoff;

    /**
     * <b>테스트 전용</b> 실패 주입 seam. 기본은 아무 일도 하지 않는다.
     *
     * <p>재시도 루프를 실제로 돌려 보려면 <b>정산 트랜잭션 안에서</b> 잠금 실패가 나야 한다.
     * 실제 데드락을 매번 만드는 것은 결정적이지 않으므로, 분류기가 인식하는 것과 같은
     * cause 사슬을 가진 예외를 그 자리에서 던진다.
     *
     * <p>훅은 {@code TransactionTemplate.execute()} <b>안에서</b> 호출된다 —
     * 그래야 던진 예외가 그 트랜잭션을 롤백시키고, 다음 시도가 새 트랜잭션을 열게 된다.
     */
    private final FailureInjector failureInjector;

    /** 그룹 키와 현재 시도 번호(1부터)를 받아, 실패시키려면 예외를 던진다. */
    @FunctionalInterface
    interface FailureInjector {
        void beforeSettle(GroupKey key, int attempt);
    }

    // 관측용 계수기
    private int candidateSelects;
    private int quotaLockSelects;
    private int seatUpdates;
    private int quotaUpdates;
    private int transactionCount;

    Task2gfExpirySweeper(DataSource dataSource,
            JdbcSeatExpiryRepository expiryRepository,
            Task2gQuotaCounter quota,
            TransactionMode mode,
            IntConsumer backoff) {
        this(dataSource, expiryRepository, quota, mode, backoff, (key, attempt) -> { });
    }

    Task2gfExpirySweeper(DataSource dataSource,
            JdbcSeatExpiryRepository expiryRepository,
            Task2gQuotaCounter quota,
            TransactionMode mode,
            IntConsumer backoff,
            FailureInjector failureInjector) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.expiryRepository = expiryRepository;
        this.quota = quota;
        this.mode = mode;
        this.backoff = backoff;
        this.failureInjector = failureInjector;
        TransactionTemplate template =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        // 그룹 트랜잭션이 호출자 트랜잭션에 조용히 참여하면 경계 실험 자체가 무의미해진다.
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactions = template;
    }

    /** 대기 없는 스위퍼. 대부분의 실험이 쓴다. */
    static Task2gfExpirySweeper of(DataSource dataSource,
            JdbcSeatExpiryRepository expiryRepository,
            Task2gQuotaCounter quota,
            TransactionMode mode) {
        return new Task2gfExpirySweeper(dataSource, expiryRepository, quota, mode, attempt -> { });
    }

    // ------------------------------------------------------------------

    /**
     * 한 배치를 회수하고 사용자별 quota 를 정산한다.
     *
     * <p><b>후보 탐색은 정산 트랜잭션 밖</b>에서 한다 (2G-C 의 경계 그대로).
     * 후보는 스냅샷이고, 성공 판정은 최종 조건부 UPDATE 의 {@code affected_rows} 가 한다.
     *
     * @param afterCandidateSelect 후보 조회 직후·정산 시작 전에 실행되는 테스트 훅.
     *                             훅이 반환될 때 그 전이는 이미 커밋된 상태여야 한다.
     *                             운영 계약에는 없는 seam 이다.
     */
    Task2gfSweepOutcome sweep(int batchSize, Runnable afterCandidateSelect) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "스위퍼는 외부 트랜잭션 안에서 호출할 수 없다. "
                            + "후보 탐색과 경쟁 전이가 정산 트랜잭션 밖에 있어야 한다");
        }
        resetCounters();
        Instant startedAt = Instant.now();

        GroupedCandidates grouped = groupCandidates(batchSize);
        afterCandidateSelect.run();

        Map<GroupKey, Integer> succeeded = new LinkedHashMap<>();
        List<GroupFailure> failed = new ArrayList<>();
        int attempts = mode == TransactionMode.BATCH_WIDE
                ? settleBatchWide(grouped, succeeded, failed)
                : settlePerGroup(grouped, succeeded, failed);

        int reclaimed = succeeded.values().stream().mapToInt(Integer::intValue).sum();
        int candidatesInFailedGroups = failed.stream()
                .mapToInt(f -> grouped.candidateCounts().getOrDefault(f.key(), 0))
                .sum();
        // stale = 후보였는데 회수되지 않은 것. 실패 그룹의 후보와 손상 후보는 stale 이 아니다.
        int stale = grouped.totalCandidates() - reclaimed - candidatesInFailedGroups
                - grouped.corrupt().size();

        return new Task2gfSweepOutcome(
                grouped.totalCandidates(), reclaimed, succeeded, failed,
                Math.max(0, stale), grouped.corrupt(), attempts,
                startedAt, Instant.now(),
                new Task2gfSqlCounts(candidateSelects, quotaLockSelects, seatUpdates,
                        quotaUpdates, transactionCount));
    }

    Task2gfSweepOutcome sweep(int batchSize) {
        return sweep(batchSize, () -> { });
    }

    // ------------------------------------------------------------------

    /**
     * 대안 1 — 배치 전체가 한 트랜잭션.
     *
     * <p>한 그룹이 실패하면 <b>이미 처리한 다른 사용자의 좌석 회수와 quota 감소까지</b>
     * 함께 롤백된다. 정합성 규칙은 단순하지만 한 사용자의 drift 가 I-10 회수 전체를 멈춘다.
     */
    private int settleBatchWide(GroupedCandidates grouped,
            Map<GroupKey, Integer> succeeded, List<GroupFailure> failed) {
        if (grouped.groups().isEmpty()) {
            return 0;
        }
        transactionCount++;
        Map<GroupKey, Integer> staged = new LinkedHashMap<>();
        try {
            transactions.executeWithoutResult(status -> {
                lockAllQuotaRows(grouped.groups().keySet());
                grouped.groups().forEach((key, group) -> {
                    failureInjector.beforeSettle(key, 1);
                    staged.put(key, settleGroup(key, group));
                });
            });
            succeeded.putAll(staged);
        } catch (RuntimeException e) {
            FailureKind kind = Task2gfFailureClassifier.classify(e);
            // 배치 전체가 롤백됐으므로 성공 그룹이 없다. 모든 그룹을 실패로 기록한다.
            grouped.groups().keySet().forEach(
                    key -> failed.add(new GroupFailure(key, kind, rootMessage(e), 1)));
        }
        return 1;
    }

    /**
     * 대안 2 — {@code (saleEventId, userId)} 그룹마다 독립 트랜잭션.
     *
     * <p>그룹 안에서는 좌석 회수와 quota 감소가 <b>원자적</b>이다. 실패한 그룹만 롤백되고
     * 나머지는 커밋된다. 처리 순서는 quota 키 오름차순을 유지한다.
     *
     * <p>재시도는 <b>실패한 그룹만</b> 새 트랜잭션으로 다시 시도한다. 이미 커밋된 그룹은
     * 다시 실행하지 않는다.
     */
    private int settlePerGroup(GroupedCandidates grouped,
            Map<GroupKey, Integer> succeeded, List<GroupFailure> failed) {
        int totalAttempts = 0;

        for (Map.Entry<GroupKey, List<ExpiryCandidate>> entry : grouped.groups().entrySet()) {
            GroupKey key = entry.getKey();
            List<ExpiryCandidate> group = entry.getValue();

            RuntimeException lastFailure = null;
            FailureKind lastKind = FailureKind.UNKNOWN;

            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                totalAttempts++;
                transactionCount++;
                try {
                    int currentAttempt = attempt;
                    Integer reclaimed = transactions.execute(status -> {
                        lockAllQuotaRows(List.of(key));
                        // 테스트 전용 seam. 운영에서는 no-op 이다.
                        failureInjector.beforeSettle(key, currentAttempt);
                        return settleGroup(key, group);
                    });
                    succeeded.put(key, reclaimed == null ? 0 : reclaimed);
                    lastFailure = null;
                    break;
                } catch (RuntimeException e) {
                    lastFailure = e;
                    lastKind = Task2gfFailureClassifier.classify(e);
                    if (!Task2gfFailureClassifier.isRetryable(lastKind) || attempt == MAX_ATTEMPTS) {
                        break;
                    }
                    backoff.accept(attempt);
                }
            }

            if (lastFailure != null) {
                int attemptsUsed = Task2gfFailureClassifier.isRetryable(lastKind)
                        ? MAX_ATTEMPTS : countedAttemptsFor(lastKind);
                failed.add(new GroupFailure(key, lastKind, rootMessage(lastFailure), attemptsUsed));
            }
        }
        return totalAttempts;
    }

    private static int countedAttemptsFor(FailureKind kind) {
        return 1;
    }

    /** quota 행을 결정적 순서로 먼저 전부 잠근다 — quota → seat (2G-B). */
    private void lockAllQuotaRows(Iterable<GroupKey> keys) {
        for (GroupKey key : keys) {
            quotaLockSelects++;
            Integer held = quota.lockQuotaRow(key.userId(), key.saleEventId());
            if (held == null) {
                throw new QuotaRowMissingException(
                        "quota 행이 없다: " + key + " — 좌석은 이 사용자 것인데 카운터가 없다");
            }
        }
    }

    /** 한 그룹의 좌석을 회수하고 <b>실제 affected_rows 만큼만</b> quota 를 줄인다. */
    private int settleGroup(GroupKey key, List<ExpiryCandidate> group) {
        seatUpdates++;
        int affected = expiryRepository.expire(group);
        if (affected == 0) {
            // 후보가 전부 stale 했다. 줄일 것이 없으므로 SQL 을 보내지 않는다.
            return 0;
        }
        quotaUpdates++;
        if (!quota.release(key.userId(), key.saleEventId(), affected)) {
            throw new QuotaDecreaseRejectedException(
                    "quota 감소 거부: " + key + ", affected=" + affected
                            + " — 카운터가 실제보다 작다");
        }
        return affected;
    }

    // ------------------------------------------------------------------

    private record GroupedCandidates(
            Map<GroupKey, List<ExpiryCandidate>> groups,
            Map<GroupKey, Integer> candidateCounts,
            List<CorruptCandidate> corrupt,
            int totalCandidates) {
    }

    /**
     * 후보를 그룹으로 묶는다. <b>손상 후보는 그룹에 넣지 않고 따로 모은다.</b>
     *
     * <p>{@code held_by} 가 NULL 이면 어느 카운터를 줄일지 알 수 없다. 0 으로 읽어 사용자 0
     * 그룹을 만들면 존재하지 않는 카운터를 건드리게 되고, 조용히 제외하면 그 좌석이 영원히
     * 남는다. <b>둘 다 하지 않고 결과에 실어 보낸다.</b>
     */
    private GroupedCandidates groupCandidates(int batchSize) {
        Map<GroupKey, List<ExpiryCandidate>> grouped = new TreeMap<>();
        List<CorruptCandidate> corrupt = new ArrayList<>();
        int[] total = {0};

        candidateSelects++;
        jdbc.query(FIND_CANDIDATES_SQL, rs -> {
            total[0]++;
            long seatId = rs.getLong("id");
            String holdId = rs.getString("hold_id");
            Long heldBy = rs.getObject("held_by", Long.class);
            long saleEventId = rs.getLong("sale_event_id");

            if (holdId == null) {
                corrupt.add(new CorruptCandidate(seatId,
                        "활성 좌석인데 hold_id 가 NULL 이다 — 소유권을 검증할 수 없어 회수하지 않는다"));
                return;
            }
            if (heldBy == null) {
                corrupt.add(new CorruptCandidate(seatId,
                        "활성 좌석인데 held_by 가 NULL 이다 — 소유자를 몰라 quota 를 정산할 수 없다"));
                return;
            }
            grouped.computeIfAbsent(new GroupKey(saleEventId, heldBy), k -> new ArrayList<>())
                    .add(new ExpiryCandidate(new SeatId(seatId), HoldId.of(holdId)));
        }, batchSize);

        grouped.values().forEach(
                list -> list.sort(Comparator.comparingLong(c -> c.seatId().value())));

        Map<GroupKey, Integer> counts = new LinkedHashMap<>();
        grouped.forEach((key, list) -> counts.put(key, list.size()));
        return new GroupedCandidates(grouped, counts, List.copyOf(corrupt), total[0]);
    }

    private static String rootMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage();
    }

    private void resetCounters() {
        candidateSelects = 0;
        quotaLockSelects = 0;
        seatUpdates = 0;
        quotaUpdates = 0;
        transactionCount = 0;
    }
}
