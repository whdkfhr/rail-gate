package com.railgate.reservation.infra.quota;

import com.railgate.reservation.UserId;
import com.railgate.reservation.saleevent.SaleEventId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

/**
 * 1인당 활성 선점 좌석 수 카운터 (I-12).
 *
 * <h2>왜 집계가 아니라 카운터 행인가</h2>
 *
 * <p>{@code SELECT COUNT(*) FROM seat_inventory WHERE held_by = ?} 로 검증하면
 * <b>다른 트랜잭션의 미커밋 홀드가 보이지 않는다.</b> 같은 사용자가 3석 요청 2개를 동시에
 * 보내면 둘 다 "내 것 0석" 만 보고 통과해 6석을 점유한다 (CLAUDE.md 규칙 8).
 * {@code UserHoldQuotaRaceReproductionTest} 가 그것을 결정적으로 재현한다.
 *
 * <p>카운터 행은 <b>검사와 증가를 한 문장으로</b> 만든다. 조건이 {@code WHERE} 절 안에 있으므로
 * 읽고 판단하는 구간이 존재하지 않고, 같은 사용자의 요청끼리만 그 행에서 직렬화된다.
 *
 * <h2>★ 트랜잭션은 호출자가 소유한다</h2>
 *
 * <p>이 저장소는 트랜잭션을 <b>열지 않는다.</b> 모든 변경과 잠금은 호출자가 연 트랜잭션에
 * 참여하며, <b>이 DataSource 의 커넥션이 실제로 그 트랜잭션에 참여하는지</b>를
 * SQL 을 보내기 전에 확인한다. "아무 트랜잭션이나 열려 있는가" 로는 부족하다 —
 * 자세한 근거는 {@link #requireEnlistedTransaction} 에 있다.
 *
 * <p>독립 커밋을 만들면 quota 와 좌석이 서로 다른 시점에 확정된다. 좌석 선점이 실패해도
 * 카운터만 늘어난 채 남고, 그 사용자는 <b>잡지도 못한 좌석 때문에 상한을 소진</b>한다.
 * 잠금도 마찬가지다 — 트랜잭션이 없으면 {@code SELECT ... FOR UPDATE} 의 잠금이 문장 종료와
 * 함께 풀려 순서를 통일한 의미가 사라진다.
 *
 * <p>거부를 SQL <b>앞에</b> 두는 이유는, DB 를 건드리고 나서 알리면 이미 늦기 때문이다.
 *
 * <h2>잠금 순서 — quota → seat</h2>
 *
 * <p>quota 행을 먼저 잠그고 좌석을 갱신한다 (TASK-002G-B). 여러 행을 잠글 때는
 * {@link QuotaKey} 오름차순이며, 그 순서는 {@code user_hold_quota} 의 PK 컬럼 순서와 같다.
 * {@link #lockAll} 이 호출자가 넘긴 순서와 무관하게 정렬해 잠근다.
 *
 * <h2>★ 이 저장소가 보장하지 않는 것</h2>
 *
 * <p><b>I-12 는 이 저장소만으로 요청 경로 전체에 강제되지 않는다.</b>
 * 선점 유스케이스가 "quota 확보 → 좌석 선점" 을 <b>한 트랜잭션 안에서</b> 부르도록 배선하고,
 * 확정·만료·해제 세 이탈 경로에서 감소를 연동해야 비로소 강제된다. 그 배선은 Task 2H 다.
 * 여기서 보장하는 것은 <b>불려졌을 때 원자적이라는 것</b>뿐이다.
 *
 * <p>확정 경로의 감소는 아직 어디에도 없다 (TASK-002G-C 의 미해결 항목).
 * 따라서 만료 경로만 연동하면 카운터가 실제보다 계속 커진다.
 */
public class JdbcUserHoldQuotaRepository {

    /** P-2. 1인당 동시 선점 좌석 상한. {@code ck_user_hold_quota_range} 와 같은 값이다. */
    public static final int MAX_SEATS = 4;

    /**
     * 카운터 행을 원자적으로 확보한다.
     *
     * <p><b>SELECT 후 INSERT 를 쓰지 않는다.</b> 확인과 삽입 사이가 열려 있는 TOCTOU 이며,
     * 동시 첫 요청에서 두 트랜잭션이 모두 "행이 없다" 를 보고 각자 INSERT 하면 중복 키 경쟁이 된다.
     *
     * <p>{@code ON DUPLICATE KEY UPDATE held_seats = held_seats} 는 <b>값을 바꾸지 않는</b>
     * no-op 갱신이다. 행이 없으면 만들고, 있으면 <b>기존 값을 그대로 두면서</b> 그 행의 잠금을 얻는다.
     * 여기서 0 으로 되돌리면 이미 잡은 좌석의 몫이 사라진다.
     *
     * <p><b>{@code INSERT IGNORE} 를 쓰지 않는다.</b> 그것은 중복 키뿐 아니라 CHECK 위반이나
     * FK 위반 같은 다른 오류까지 경고로 낮춰 삼킨다 — 없는 회차의 카운터를 만들려는 실수가
     * 조용히 성공한 것처럼 보이게 된다.
     */
    private static final String ENSURE_ROW_SQL = """
            INSERT INTO user_hold_quota (sale_event_id, user_id, held_seats)
            VALUES (?, ?, 0)
            ON DUPLICATE KEY UPDATE held_seats = held_seats
            """;

    /**
     * 검사와 증가를 한 문장으로 수행한다 (CLAUDE.md 규칙 8).
     *
     * <p>{@code affected_rows = 1} 이면 확보 성공, {@code 0} 이면 상한 초과이거나 행이 없다.
     * <b>행이 없어도 만들지 않는다</b> — 확보 경로는 {@link #ensureRow} 를 먼저 부른다.
     */
    private static final String TRY_ACQUIRE_SQL = """
            UPDATE user_hold_quota
               SET held_seats = held_seats + ?
             WHERE sale_event_id = ?
               AND user_id       = ?
               AND held_seats + ? <= %d
            """.formatted(MAX_SEATS);

    /**
     * 감소. <b>실제로 변경된 좌석 행 수만큼만</b> 줄인다 (TASK-002G-C).
     *
     * <p>{@code held_seats - ? >= 0} 은 음수 방어다. 감소가 중복 호출되거나 감소량이 과대
     * 계산되면 카운터가 음수가 되어 이후 상한 검사가 무의미해진다.
     *
     * <p><b>없는 행을 만들지 않는다.</b> {@code WHERE} 가 매칭되지 않으면 그대로 0 행이다.
     */
    private static final String RELEASE_SQL = """
            UPDATE user_hold_quota
               SET held_seats = held_seats - ?
             WHERE sale_event_id = ?
               AND user_id       = ?
               AND held_seats - ? >= 0
            """;

    /**
     * 카운터 행을 <b>명시적으로 잠근다</b>.
     *
     * <p><b>여기서 읽은 값으로 상한을 판단하면 안 된다.</b> 그것은 규칙 8 이 금지한
     * 집계-후-판단으로 되돌아가는 것이다. 상한 검사는 여전히 {@link #tryAcquire} 의
     * 조건부 UPDATE 가 원자적으로 수행한다.
     *
     * <p>여기서 얻는 것은 <b>quota 행을 좌석보다 먼저 잠근다</b>는 순서뿐이다.
     * 감소 경로는 실제 {@code affected_rows} 를 좌석 UPDATE 후에야 알 수 있어서
     * "먼저 잠그고 나중에 값을 정한다" 가 필요하다.
     */
    private static final String LOCK_ROW_SQL = """
            SELECT held_seats FROM user_hold_quota
             WHERE sale_event_id = ? AND user_id = ?
             FOR UPDATE
            """;

    private final JdbcTemplate jdbc;

    /**
     * Spring 이 트랜잭션 리소스를 찾을 때 쓰는 키. 생성 시점에 한 번 정규화해 둔다.
     *
     * <p>매번 계산하지 않는 이유는 <b>검사 기준이 흔들리지 않게</b> 하기 위해서다.
     * {@code JdbcMultiSeatHoldRepository} 와 같은 방식이다.
     */
    private final Object transactionResourceKey;

    /**
     * 참여 여부를 물어보기 위한 관리자. <b>이 저장소의 DataSource 로 직접 만든다.</b>
     *
     * <p>주입받지 않는 이유는 <b>배선 실수를 원천적으로 없애기 위해서다.</b>
     * 스스로 만들면 저장소와 관리자의 리소스가 어긋날 수 없다.
     *
     * <p><b>관리자 인스턴스 동일성을 요구하는 것이 아니다.</b>
     * {@code DataSourceTransactionManager.doGetTransaction} 은
     * {@code TransactionSynchronizationManager.getResource(dataSource)} 로 리소스를 찾으므로,
     * <b>다른 인스턴스가 시작한 트랜잭션도 그대로 인식한다.</b> 조건은 리소스 동일성뿐이다.
     *
     * <p>이 관리자로 트랜잭션을 <b>시작하지 않는다.</b> {@code PROPAGATION_MANDATORY} 로
     * "이미 있는가" 만 묻는다.
     */
    private final PlatformTransactionManager participationProbe;

    /**
     * 참여 여부만 확인하기 위한 정의.
     *
     * <p>{@code MANDATORY} 는 기존 트랜잭션이 없으면 {@link IllegalTransactionStateException}
     * 을 던지고 <b>새 트랜잭션을 만들지 않는다.</b> 그것이 우리가 원하는 질문 그대로다.
     */
    private static final TransactionDefinition PARTICIPATION_PROBE = createProbeDefinition();

    private static TransactionDefinition createProbeDefinition() {
        DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
        definition.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
        definition.setName("quota-participation-probe");
        return definition;
    }

    public JdbcUserHoldQuotaRepository(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactionResourceKey = transactionResourceKey(dataSource);
        this.participationProbe = new DataSourceTransactionManager(dataSource);
    }

    /**
     * 카운터 행이 존재하도록 보장한다. 이미 있으면 <b>값을 바꾸지 않는다.</b>
     *
     * <p>동시 첫 요청에서도 행은 하나만 남는다.
     *
     * @throws IllegalStateException 이 DataSource 의 트랜잭션에 참여하지 않은 경우
     */
    public void ensureRow(SaleEventId saleEventId, UserId userId) {
        requireEnlistedTransaction("ensureRow");
        Objects.requireNonNull(saleEventId, "saleEventId");
        Objects.requireNonNull(userId, "userId");

        jdbc.update(ENSURE_ROW_SQL, saleEventId.value(), userId.value());
    }

    /**
     * 좌석 몫을 확보한다. 검사와 증가가 한 문장이다.
     *
     * @param seats 확보할 좌석 수. 1 이상 {@value #MAX_SEATS} 이하
     * @return {@link QuotaAcquireOutcome#LIMIT_EXCEEDED} 는 <b>정상 동작이다.</b>
     *         상한 초과이거나 카운터 행이 없다는 뜻이며, 둘을 구분하려면 {@link #lockRow} 를 쓴다
     * @throws IllegalStateException    이 DataSource 의 트랜잭션에 참여하지 않은 경우
     * @throws IllegalArgumentException {@code seats} 가 범위를 벗어난 경우
     */
    public QuotaAcquireOutcome tryAcquire(SaleEventId saleEventId, UserId userId, int seats) {
        requireEnlistedTransaction("tryAcquire");
        Objects.requireNonNull(saleEventId, "saleEventId");
        Objects.requireNonNull(userId, "userId");
        requireSeatsInRange(seats, "확보");

        int affectedRows = jdbc.update(TRY_ACQUIRE_SQL,
                seats, saleEventId.value(), userId.value(), seats);

        return affectedRows == 1
                ? QuotaAcquireOutcome.ACQUIRED
                : QuotaAcquireOutcome.LIMIT_EXCEEDED;
    }

    /**
     * 좌석 몫을 반납한다.
     *
     * <p><b>{@code seats} 는 요청 좌석 수가 아니라 실제로 변경된 좌석 행 수여야 한다</b>
     * (TASK-002G-C). 만료 배치라면 회수 UPDATE 의 {@code affected_rows} 다.
     *
     * <p><b>0 을 거부하는 이유.</b> 줄일 것이 없으면 이 메서드를 부르지 않는 것이 계약이다
     * (TASK-002G-F 의 스위퍼가 그렇게 한다). 0 을 허용하면 "행이 없어서 0 행" 과
     * "줄일 것이 없어서 0 행" 이 같은 결과가 되어 drift 를 숨긴다.
     *
     * @return {@link QuotaReleaseOutcome#REJECTED} 는 <b>drift 신호다.</b>
     *         행이 없거나 음수가 될 요청이며, 둘을 구분하려면 {@link #lockRow} 를 쓴다
     * @throws IllegalStateException    이 DataSource 의 트랜잭션에 참여하지 않은 경우
     * @throws IllegalArgumentException {@code seats} 가 범위를 벗어난 경우
     */
    public QuotaReleaseOutcome release(SaleEventId saleEventId, UserId userId, int seats) {
        requireEnlistedTransaction("release");
        Objects.requireNonNull(saleEventId, "saleEventId");
        Objects.requireNonNull(userId, "userId");
        requireSeatsInRange(seats, "감소");

        int affectedRows = jdbc.update(RELEASE_SQL,
                seats, saleEventId.value(), userId.value(), seats);

        return affectedRows == 1
                ? QuotaReleaseOutcome.RELEASED
                : QuotaReleaseOutcome.REJECTED;
    }

    /**
     * 카운터 행을 잠그고 현재 값을 읽는다.
     *
     * <p>목적은 값 판단이 아니라 <b>잠금 순서 통일</b>이다 (위 SQL 주석 참고).
     *
     * <h2>빈 반환값의 의미 — "행 없음" 이지 "잠금 없음" 이 아니다</h2>
     *
     * <p>빈 값은 <b>일치하는 quota 행이 없다</b>는 뜻이다. <b>잠금이 생기지 않았다는 뜻이 아니다.</b>
     *
     * <p>REPEATABLE READ 에서 실제로 관측한 결과다
     * ({@code QuotaLockRowGapLockTest}, {@code performance_schema.data_locks}).
     *
     * <pre>
     *   있는 키 (9001, 30) 조회 → 값 반환. RECORD / X,REC_NOT_GAP  on PRIMARY [9001, 30]
     *   없는 키 (9001, 20) 조회 → 빈 값.   RECORD / <b>X,GAP</b>          on PRIMARY [9001, 30]
     * </pre>
     *
     * <p>즉 <b>없는 키를 조회하면 다음 레코드 앞의 갭에 잠금이 잡힌다.</b>
     * 그 사이에 다른 트랜잭션이 그 키를 INSERT 하려 하면 대기하게 된다.
     * 갭 잠금 여부는 <b>격리 수준과 조회 조건에 따라 달라진다</b> — READ COMMITTED 에서는
     * 다르며, 이 프로젝트는 그 조합을 측정하지 않았다.
     *
     * <p>계약은 반환값에 있다 — <b>빈 값이면 카운터가 없다는 뜻이고, 자동 생성하지 않는다.</b>
     * <b>호출자가 잠금의 유무를 가정하지 않아야 한다.</b>
     *
     * @return 잠근 시점의 {@code held_seats}. 일치하는 행이 없으면 빈 값
     * @throws IllegalStateException 이 DataSource 의 트랜잭션에 참여하지 않은 경우
     */
    public Optional<Integer> lockRow(SaleEventId saleEventId, UserId userId) {
        requireEnlistedTransaction("lockRow");
        Objects.requireNonNull(saleEventId, "saleEventId");
        Objects.requireNonNull(userId, "userId");

        return jdbc.queryForList(LOCK_ROW_SQL, Integer.class,
                        saleEventId.value(), userId.value())
                .stream()
                .findFirst();
    }

    /**
     * 여러 카운터 행을 <b>키 오름차순으로</b> 잠근다 (TASK-002G-B, 규칙 2).
     *
     * <p>호출자가 어떤 순서로 넘기든 여기서 정렬한다. 순서를 호출부마다 지키게 하면
     * 한 곳만 어겨도 데드락이 나고, 그 한 곳을 찾기 어렵다.
     *
     * <p>중복 키는 한 번만 잠근다 — 같은 행을 두 번 잠그는 것은 의미가 없다.
     *
     * @return 실제로 잠근 순서
     * @throws IllegalStateException     이 DataSource 의 트랜잭션에 참여하지 않은 경우
     * @throws QuotaRowMissingException  키 중 하나라도 행이 없는 경우.
     *                                   <b>조용히 건너뛰지 않는다</b> — 좌석은 그 사용자 것인데
     *                                   카운터가 없다는 것은 이미 어긋난 상태다
     */
    public List<QuotaKey> lockAll(List<QuotaKey> keys) {
        requireEnlistedTransaction("lockAll");
        Objects.requireNonNull(keys, "keys");

        List<QuotaKey> ordered = keys.stream().distinct().sorted().toList();
        List<QuotaKey> locked = new ArrayList<>();
        for (QuotaKey key : ordered) {
            if (lockRow(key.saleEventId(), key.userId()).isEmpty()) {
                throw new QuotaRowMissingException(
                        "quota 행이 없다: " + key + " — 좌석은 이 사용자 것인데 카운터가 없다");
            }
            locked.add(key);
        }
        return locked;
    }

    // ------------------------------------------------------------------

    /**
     * <b>이 DataSource 의 커넥션이 호출자의 JDBC 트랜잭션에 실제로 참여하는지</b> 확인한다.
     * SQL 을 보내기 전에 검사하므로 실패해도 DB 는 건드려지지 않는다.
     *
     * <h2>왜 직접 판정하지 않고 Spring 에게 묻는가</h2>
     *
     * <p>다음 신호는 <b>하나씩으로는 물론 합쳐도</b> 실제 참여를 증명하지 못한다.
     *
     * <ul>
     *   <li>{@code isActualTransactionActive()} — <b>다른</b> DataSource 위의 트랜잭션에도 참이다</li>
     *   <li>{@code hasResource(key)} — 다른 트랜잭션 도중 이 DataSource 로 조회하면
     *       Spring 이 커넥션을 <b>동기화 목적으로 바인딩</b>한다. 참이지만 참여가 아니다</li>
     *   <li>{@code isSynchronizedWithTransaction()} — 위 바인딩에서도 참으로 설정된다</li>
     *   <li>{@code Connection.getAutoCommit() == false} — <b>풀이 {@code autoCommit=false} 로
     *       설정돼 있으면 동기화 목적 바인딩도 이 조건을 통과한다.</b>
     *       리뷰가 실제 MySQL 로 재현했다 — quota 는 확보에 성공한 것처럼 보이지만
     *       풀 반납 시 롤백되어 좌석만 커밋된다</li>
     * </ul>
     *
     * <p>정확한 신호는 {@code ConnectionHolder.isTransactionActive()} 인데 <b>protected</b> 다.
     * 그런데 {@code DataSourceTransactionManager.isExistingTransaction()} 이 바로 그 값을 보고,
     * 그 판정은 <b>공개 API 로 도달할 수 있다</b> — {@code PROPAGATION_MANDATORY} 다.
     *
     * <pre>
     * isExistingTransaction(tx) == tx.hasConnectionHolder()
     *                             &amp;&amp; tx.getConnectionHolder().isTransactionActive()
     * </pre>
     *
     * <p>기존 트랜잭션이 없으면 {@code MANDATORY} 는 {@link IllegalTransactionStateException}
     * 을 던지고 <b>새 트랜잭션을 만들지 않는다.</b> 그래서 이 저장소는 여전히
     * 최상위 트랜잭션을 열지 않고 독립 커밋도 하지 않는다.
     *
     * <h2>확인 뒤 상태를 어떻게 정리하는가</h2>
     *
     * <p>참여 중이면 반환되는 {@link TransactionStatus} 는 <b>새 트랜잭션이 아니다</b>
     * ({@code isNewTransaction() == false}). 그런 상태의 커밋은 DB 커밋이 아니라 정리일 뿐이다.
     *
     * <p>다만 외부가 이미 rollback-only 로 표시돼 있으면 커밋은
     * {@code UnexpectedRollbackException} 을 던진다. 그것은 <b>호출자의 계약을 바꾸는 것</b>이라
     * (TASK-002F 가 다룬 바로 그 문제다) 그 경우에는 롤백으로 정리한다 —
     * 이미 rollback-only 이므로 상태를 <b>더 나쁘게 만들지 않는다.</b>
     */
    private void requireEnlistedTransaction(String operation) {
        TransactionStatus participation;
        try {
            participation = participationProbe.getTransaction(PARTICIPATION_PROBE);
        } catch (IllegalTransactionStateException e) {
            throw new IllegalStateException(
                    ("%s 는 이 저장소의 DataSource 에 열린 트랜잭션 안에서만 호출할 수 있다. "
                            + "다른 DataSource 의 트랜잭션이나 동기화 목적으로 바인딩만 된 커넥션은 "
                            + "quota 변경을 이 트랜잭션 밖에서 확정시켜, 호출자가 롤백해도 "
                            + "되돌아가지 않거나 조용히 사라진다. 저장소 리소스=%s")
                            .formatted(operation, transactionResourceKey), e);
        }
        releaseParticipationProbe(participation);
    }

    /**
     * 참여 확인용 상태를 정리한다. <b>호출자의 트랜잭션에는 아무 영향도 주지 않아야 한다.</b>
     *
     * <p><b>측정한 사실</b> ({@code QuotaTransactionEnlistmentTest} §6). 이 상태는
     * 참여(non-new) 상태이므로 {@code commit()} 도 {@code rollback()} 도 물리 커넥션을
     * 커밋하거나 롤백하지 않고, 호출자의 rollback-only 표시도 지우지 않는다.
     *
     * <table>
     *   <caption>참여 상태 정리의 관측 결과</caption>
     *   <tr><th>바깥 상태</th><th>{@code isRollbackOnly()}</th>
     *       <th>{@code commit()} 결과</th></tr>
     *   <tr><td>global rollback-only, {@code failEarlyOnGlobalRollbackOnly=false}(기본)</td>
     *       <td>{@code true}</td><td>예외 없음</td></tr>
     *   <tr><td>global rollback-only, {@code failEarlyOnGlobalRollbackOnly=true}</td>
     *       <td>{@code true}</td><td>{@code UnexpectedRollbackException}</td></tr>
     *   <tr><td>바깥 {@code status.setRollbackOnly()} (local)</td>
     *       <td><b>{@code false}</b></td><td>예외 없음</td></tr>
     * </table>
     *
     * <p>즉 이 분기는 <b>기본 설정에서는 관측 가능한 차이를 만들지 않는</b> 방어 코드다.
     * 그런데도 두는 이유는 {@code failEarlyOnGlobalRollbackOnly} 가 켜진 경우 커밋 시도가
     * <b>이 저장소 호출 지점에서</b> {@code UnexpectedRollbackException} 을 던져
     * 진짜 실패 원인을 가리기 때문이다. 그 예외는 호출자의 실패이지
     * quota 확인의 실패가 아니다.
     *
     * <p><b>local 과 global 을 혼동하지 말 것.</b> 여기서 보는 {@code isRollbackOnly()} 는
     * JDBC 리소스(커넥션 홀더)의 <b>global</b> 표시다. 바깥 {@code TransactionStatus} 에
     * 찍힌 <b>local</b> rollback-only 는 이 상태에서 보이지 않아 커밋 분기를 탄다 —
     * 그래도 위 표대로 무해하다.
     */
    private void releaseParticipationProbe(TransactionStatus participation) {
        if (participation.isRollbackOnly()) {
            participationProbe.rollback(participation);
        } else {
            participationProbe.commit(participation);
        }
    }

    /**
     * Spring 이 트랜잭션 리소스를 찾을 때 쓰는 키로 정규화한다.
     *
     * <p>{@code JdbcMultiSeatHoldRepository} 와 <b>같은 규칙</b>이다. 두 단계 모두
     * Spring 자신의 동작을 그대로 따른 것이다.
     * <ol>
     *   <li>{@link TransactionAwareDataSourceProxy} 를 대상 {@code DataSource} 로 벗긴다 —
     *       {@code DataSourceTransactionManager.setDataSource} 가 같은 일을 하므로,
     *       프록시를 넘겨도 트랜잭션은 <b>대상</b> DataSource 위에 열린다.</li>
     *   <li>{@code unwrapResourceIfNecessary} 를 적용한다 —
     *       {@code TransactionSynchronizationManager} 가 리소스를 조회할 때 쓰는 정규화다.</li>
     * </ol>
     *
     * <p><b>한계.</b> 2단계는 {@code InfrastructureProxy}/{@code ScopedObject} 만 벗긴다.
     * 그 인터페이스를 구현하지 않는 프록시(예: {@code LazyConnectionDataSourceProxy})를
     * 한쪽에만 씌우면 키가 어긋나 거부된다. 거부되는 편이 안전하다.
     */
    private static Object transactionResourceKey(DataSource dataSource) {
        DataSource target = dataSource;
        while (target instanceof TransactionAwareDataSourceProxy proxy
                && proxy.getTargetDataSource() != null) {
            target = proxy.getTargetDataSource();
        }
        return TransactionSynchronizationUtils.unwrapResourceIfNecessary(target);
    }

    /**
     * 좌석 수 범위 검사.
     *
     * <p>상한을 여기서도 막는 이유: {@code seats > MAX_SEATS} 는 조건부 UPDATE 가 어차피
     * 거부하지만, 그것은 "상한 초과" 라는 <b>정상 결과</b>로 보고된다.
     * 애초에 불가능한 요청은 <b>잘못된 입력</b>으로 구분하는 편이 호출부에서 읽기 쉽다.
     */
    private static void requireSeatsInRange(int seats, String operation) {
        if (seats < 1 || seats > MAX_SEATS) {
            throw new IllegalArgumentException(
                    "%s 좌석 수는 1~%d석이어야 한다: %d".formatted(operation, MAX_SEATS, seats));
        }
    }
}
