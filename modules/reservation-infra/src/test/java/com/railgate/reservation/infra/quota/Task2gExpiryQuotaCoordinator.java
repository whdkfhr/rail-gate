package com.railgate.reservation.infra.quota;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.infra.seat.ExpiryCandidate;
import com.railgate.reservation.infra.seat.JdbcSeatExpiryRepository;
import com.railgate.reservation.seat.SeatId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * <b>테스트 전용</b> 만료 배치 quota 정산 coordinator (Task 2G-C).
 *
 * <h2>운영 코드가 아니다</h2>
 *
 * <p>운영 {@link JdbcSeatExpiryRepository} 를 <b>바꾸지 않고 그대로 재사용</b>한다.
 * 바뀐 것은 호출 방식뿐이다 — 후보를 사용자별로 나눠 {@code expire(...)} 를 그룹마다 부른다.
 * 그러면 각 호출의 {@code affected_rows} 가 곧 그 사용자의 실제 회수 수가 된다.
 *
 * <h2>왜 한 번의 벌크 호출로는 안 되는가</h2>
 *
 * <p>{@code expire(...)} 는 전체 합계 하나만 돌려준다. 한 배치에 여러 사용자가 섞이고
 * 일부 후보가 stale 하면 <b>합계를 사용자별로 배분할 수 없다.</b>
 * {@code UserHoldQuotaExpiryAttributionTest} §1 이 같은 관측값에서 두 정답이 나오는 것을 보인다.
 *
 * <p>후보에 {@code held_by} 를 넣어도 부족하다. 그것은 <b>후보 시점</b>의 소유자일 뿐이고,
 * 어느 후보가 실제로 갱신됐는지는 여전히 알 수 없다.
 *
 * <h2>잠금 순서 — quota → seat (Task 2G-B)</h2>
 *
 * <p>정산 트랜잭션은 <b>모든 quota 행을 먼저</b> {@code (scopeId, userId)} 오름차순으로 잠근 뒤
 * 좌석을 갱신한다. 그룹 안에서는 좌석을 {@code seatId} 오름차순으로 넘긴다 (CLAUDE.md 규칙 2).
 *
 * <p><b>이 Task 가 검증한 것은 만료 정산 경로가 그 순서를 적용한다는 것뿐이다.</b>
 * quota 행과 좌석 행의 결정적 처리 순서를 코드와 관측값으로 확인했다.
 * 확정 경로나 다중 스위퍼와의 <b>실제 교착은 검증하지 않았다.</b>
 * 전체 시스템이 deadlock-free 라는 뜻이 아니며, 데드락 재시도 정책도 아직 없다.
 *
 * <h2>트랜잭션 경계</h2>
 *
 * <p>세 구간을 <b>명확히 분리</b>한다.
 * <ol>
 *   <li><b>후보 탐색</b> — 비잠금 SELECT. <b>정산 트랜잭션 밖</b>에서 실행한다.</li>
 *   <li><b>경쟁 전이</b> — 후보가 stale 해지는 순간. 테스트 훅이 여기서 실행되며,
 *       훅이 반환될 때 그 전이는 <b>별도 트랜잭션에서 커밋된 상태</b>여야 한다.</li>
 *   <li><b>정산</b> — quota 잠금부터 좌석 회수와 quota 감소까지 <b>하나의 트랜잭션</b>.</li>
 * </ol>
 *
 * <p><b>이 경계는 문서가 아니라 런타임 guard 로 강제된다.</b>
 * {@link #sweepAndSettle} 은 활성 트랜잭션이 있으면 거부하고,
 * {@link #settleWithin} 은 활성 트랜잭션이 없으면 거부한다.
 * 두 검사 모두 <b>후보 조회와 DB 변경보다 먼저</b> 수행된다.
 *
 * <p>훅을 정산 트랜잭션 안에서 돌리면 경쟁 전이가 같은 트랜잭션에 참여해
 * <b>seat 를 먼저 잠그고 quota 를 나중에 잠그는</b> 역순이 되고, 정산 실패 시 그 전이까지
 * 함께 롤백된다. 그러면 "다른 요청이 먼저 확정했다" 는 상황을 재현하지 못한다.
 *
 * <h2>quota scope 는 아직 미확정이다</h2>
 *
 * <p>{@code event_id} 인지 {@code schedule_id} 인지 정해지지 않았다 (2G-A §9).
 * 그래서 범위 키를 <b>호출자가 넘기는 중립 식별자</b>로 둔다. 이 클래스는 그 결정을 하지 않는다.
 */
final class Task2gExpiryQuotaCoordinator {

    /**
     * 카운터가 실제 상태와 어긋났음을 알리는 <b>테스트 전용</b> 신호.
     *
     * <p>{@link IllegalStateException} 을 상속해 기존 호출자 계약을 깨지 않는다.
     * 운영 예외를 추가하지 않는다.
     */
    static final class QuotaDriftException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        QuotaDriftException(String message) {
            super(message);
        }
    }

    /** quota 카운터 행을 가리키는 키. 잠금 순서를 정하기 위해 정렬 가능해야 한다. */
    record QuotaKey(long scopeId, long userId) implements Comparable<QuotaKey> {

        @Override
        public int compareTo(QuotaKey other) {
            int byScope = Long.compare(scopeId, other.scopeId);
            return byScope != 0 ? byScope : Long.compare(userId, other.userId);
        }

        @Override
        public String toString() {
            return "(scope=%d, user=%d)".formatted(scopeId, userId);
        }
    }

    /**
     * 후보 조회 SQL — 운영 {@code FIND_CANDIDATES_SQL} 과 <b>같은 대상 집합</b>을 조회하고
     * {@code held_by} 컬럼만 더했다.
     *
     * <p><b>{@code held_by IS NOT NULL} 을 붙이지 않는다.</b> 붙이면 소유자가 없는 만료 후보를
     * 조용히 제외하게 되고, 그 좌석은 영원히 회수되지 않으면서 아무도 그 사실을 모른다.
     * {@code status IN ('HELD','PAYING')} 과 {@code held_by} 의 결합을 강제하는 DB CHECK 가
     * 없으므로 그런 행이 생길 가능성을 구조적으로 배제할 수 없다 — 탐지 대상이다.
     *
     * <p>운영 계약을 바꾸지 않기 위해 <b>테스트 안에 따로 둔다.</b> 실제로 어떤 컬럼이
     * 필요한지는 이 실험이 답할 질문이고, 운영 반영은 Task 2G-D 의 결정이다.
     */
    private static final String FIND_CANDIDATES_SQL = """
            SELECT id, hold_id, held_by
              FROM seat_inventory FORCE INDEX (idx_seat_inventory_expiry_candidate)
             WHERE status IN ('HELD', 'PAYING')
               AND expires_at <= NOW(3)
               AND hold_id IS NOT NULL
             ORDER BY expires_at ASC, id ASC
             LIMIT ?
            """;

    private final JdbcTemplate jdbc;
    private final JdbcSeatExpiryRepository expiryRepository;
    private final Task2gQuotaCounter quota;
    private final TransactionTemplate topLevel;

    /** 관측용 기록. 잠금 순서와 감소 호출 횟수를 테스트가 확인한다. */
    private final List<QuotaKey> lockOrder = new ArrayList<>();
    private final Map<QuotaKey, List<Long>> seatOrder = new LinkedHashMap<>();
    private Map<QuotaKey, Integer> candidateCounts = new LinkedHashMap<>();
    private int quotaUpdateCalls;

    Task2gExpiryQuotaCoordinator(DataSource dataSource,
            JdbcSeatExpiryRepository expiryRepository,
            Task2gQuotaCounter quota) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.expiryRepository = expiryRepository;
        this.quota = quota;
        // 이 클래스가 애플리케이션 서비스 역할을 대신하므로 최상위 경계를 소유한다.
        // 같은 DataSource 리소스를 관리하면 관리자 인스턴스가 달라도 참여한다 (2G-B §5).
        this.topLevel = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    /** 자체 최상위 트랜잭션에서 한 배치를 회수하고 사용자별 quota 를 정산한다. */
    Map<QuotaKey, Integer> sweepAndSettle(int batchSize, long scopeId) {
        return sweepAndSettle(batchSize, scopeId, () -> { });
    }

    /**
     * 후보 탐색 → 경쟁 전이 → 정산 세 구간을 <b>서로 다른 트랜잭션 경계</b>로 실행한다.
     *
     * @param afterCandidateSelect 후보 조회 <b>직후, 정산 트랜잭션 시작 전</b>에 실행되는 테스트 훅.
     *                             훅이 반환될 때 그 전이는 <b>이미 커밋된 상태</b>여야 한다.
     *                             운영 계약에는 존재하지 않는 seam 이다.
     */
    Map<QuotaKey, Integer> sweepAndSettle(int batchSize, long scopeId,
            Runnable afterCandidateSelect) {
        requireNoActiveTransaction();

        // (1) 후보 탐색 — 비잠금, 정산 트랜잭션 밖.
        //     후보는 이 시점의 스냅샷이며 이후 확정·해제·연장으로 stale 해질 수 있다 (2D).
        Map<QuotaKey, List<ExpiryCandidate>> groups = groupCandidates(batchSize, scopeId);

        // (2) 경쟁 전이 — 여전히 정산 트랜잭션 밖. 훅은 자기 트랜잭션에서 커밋한다.
        afterCandidateSelect.run();

        // (3) 정산 — quota 잠금부터 좌석 회수와 quota 감소까지 하나의 트랜잭션.
        return topLevel.execute(status -> settle(groups));
    }

    /**
     * 호출자가 이미 연 트랜잭션 안에서 정산한다.
     *
     * <p>후보 탐색도 그 트랜잭션 안에서 일어난다. <b>post-select 경쟁 훅과 합치지 않는다</b> —
     * 두 API 의 목적이 다르다. 이쪽은 "외부 트랜잭션에 참여한다" 를 검증하기 위한 것이다.
     */
    Map<QuotaKey, Integer> settleWithin(int batchSize, long scopeId) {
        requireActiveTransaction();
        return settle(groupCandidates(batchSize, scopeId));
    }

    /**
     * {@link #sweepAndSettle} 의 진입 조건 — <b>활성 트랜잭션이 없어야 한다.</b>
     *
     * <p>{@link TransactionTemplate} 의 기본 전파 속성은 {@code PROPAGATION_REQUIRED} 다.
     * 외부 트랜잭션 안에서 부르면 {@code topLevel} 이 새 트랜잭션을 열지 않고
     * <b>조용히 그 트랜잭션에 참여</b>한다. 그러면 경쟁 전이 훅까지 같은 트랜잭션에 묶여
     * 이 클래스가 분리해 둔 세 구간 경계가 무너진다.
     *
     * <p>{@code REQUIRES_NEW} 로 억지로 떼어내지 않는다 — 그러면 정산이 호출자 롤백의
     * 영향을 벗어나 버린다. <b>잘못된 호출 자체를 거부하는 편이 계약이 단순하다.</b>
     *
     * <p>후보 조회와 콜백 실행보다 <b>먼저</b> 검사하므로 DB 는 건드려지지 않는다.
     */
    private static void requireNoActiveTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "sweepAndSettle 은 외부 트랜잭션 안에서 호출할 수 없다. "
                            + "자체 정산 트랜잭션을 열어야 후보 조회와 경쟁 전이를 그 밖에 둘 수 있다. "
                            + "외부 경계에 참여하려면 settleWithin 을 사용해야 한다.");
        }
    }

    /**
     * {@link #settleWithin} 의 진입 조건 — <b>활성 트랜잭션이 있어야 한다.</b>
     *
     * <p>트랜잭션 없이 부르면 {@code SELECT ... FOR UPDATE} 로 잡은 quota 잠금이 문장 종료와
     * 함께 풀리고, 좌석 회수와 quota 감소가 <b>각각 autocommit</b> 된다. 그 사이에 다른
     * 트랜잭션이 끼어들 수 있고, 감소가 거부돼도 좌석 회수를 되돌릴 수 없다.
     *
     * <p>후보 조회와 quota 잠금보다 <b>먼저</b> 검사한다.
     */
    private static void requireActiveTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "settleWithin 은 활성 트랜잭션 안에서만 호출할 수 있다. "
                            + "독립 실행이 필요하면 sweepAndSettle 을 사용해야 한다.");
        }
    }

    /**
     * 정산 구간. <b>이 메서드는 트랜잭션을 만들지 않는다</b> — 호출자의 경계 안에서 실행된다.
     *
     * @return 사용자별 <b>실제 회수 좌석 수</b>. 후보 수가 아니다.
     * @throws QuotaDriftException quota 행이 없거나 감소가 거부된 경우. <b>조용히 넘기지 않는다.</b>
     */
    private Map<QuotaKey, Integer> settle(Map<QuotaKey, List<ExpiryCandidate>> groups) {
        lockOrder.clear();
        seatOrder.clear();
        quotaUpdateCalls = 0;

        // (3-1) quota 행을 결정적 순서로 먼저 전부 잠근다 — quota → seat (2G-B).
        for (QuotaKey key : groups.keySet()) {
            Integer held = quota.lockQuotaRow(key.userId(), key.scopeId());
            if (held == null) {
                // 좌석은 이 사용자 것인데 카운터 행이 없다 = 이미 어긋난 상태다.
                // 조용히 무시하면 그 사용자의 카운터가 영구히 실제와 달라진다.
                throw new QuotaDriftException(
                        "quota 행이 없다: " + key + " — drift 로 취급하고 배치를 중단한다");
            }
            lockOrder.add(key);
        }

        // (3-2) 그룹별로 회수하고, 그 그룹의 affected_rows 만큼만 감소한다.
        Map<QuotaKey, Integer> reclaimed = new LinkedHashMap<>();
        for (Map.Entry<QuotaKey, List<ExpiryCandidate>> entry : groups.entrySet()) {
            QuotaKey key = entry.getKey();
            List<ExpiryCandidate> group = entry.getValue();
            seatOrder.put(key, group.stream().map(c -> c.seatId().value()).toList());

            int affected = expiryRepository.expire(group);
            reclaimed.put(key, affected);

            if (affected == 0) {
                // 후보가 전부 stale 했다. 줄일 것이 없으므로 SQL 자체를 보내지 않는다.
                continue;
            }
            quotaUpdateCalls++;
            if (!quota.release(key.userId(), key.scopeId(), affected)) {
                throw new QuotaDriftException(
                        "quota 감소 거부: " + key + ", affected=" + affected
                                + " — 좌석 회수까지 롤백한다");
            }
        }
        return reclaimed;
    }

    /** 후보를 {@code (scopeId, userId)} 오름차순 그룹으로 묶고, 그룹 안은 seatId 오름차순으로 둔다. */
    private Map<QuotaKey, List<ExpiryCandidate>> groupCandidates(int batchSize, long scopeId) {
        Map<QuotaKey, List<ExpiryCandidate>> grouped = new TreeMap<>();

        jdbc.query(FIND_CANDIDATES_SQL, rs -> {
            long seatId = rs.getLong("id");
            // NULL 을 0 으로 바꾸지 않는다. getLong 은 NULL 을 0 으로 돌려주므로
            // 사용자 0 이라는 존재하지 않는 그룹이 생기고 좌석이 조용히 회수된다.
            Long heldBy = rs.getObject("held_by", Long.class);
            if (heldBy == null) {
                throw new QuotaDriftException(
                        "만료 후보의 held_by 가 NULL 이다: seatId=" + seatId
                                + " — 소유자를 알 수 없어 quota 를 정산할 수 없다."
                                + " 좌석을 회수하지 않고 배치를 중단한다");
            }
            grouped.computeIfAbsent(new QuotaKey(scopeId, heldBy), k -> new ArrayList<>())
                    .add(new ExpiryCandidate(new SeatId(seatId), HoldId.of(rs.getString("hold_id"))));
        }, batchSize);

        grouped.values().forEach(
                list -> list.sort(Comparator.comparingLong(c -> c.seatId().value())));

        candidateCounts = new LinkedHashMap<>();
        grouped.forEach((key, list) -> candidateCounts.put(key, list.size()));
        return grouped;
    }

    // ------------------------------------------------------------------
    // 관측용 접근자

    /** 후보 SELECT 시점의 사용자별 후보 수. 실제 회수 수와 구분하기 위한 관측값이다. */
    Map<QuotaKey, Integer> candidateCounts() {
        return Map.copyOf(candidateCounts);
    }

    List<QuotaKey> lockOrder() {
        return List.copyOf(lockOrder);
    }

    List<Long> seatOrderOf(QuotaKey key) {
        return List.copyOf(seatOrder.getOrDefault(key, List.of()));
    }

    int quotaUpdateCalls() {
        return quotaUpdateCalls;
    }
}
