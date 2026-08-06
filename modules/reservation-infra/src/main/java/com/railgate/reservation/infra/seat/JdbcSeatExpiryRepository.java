package com.railgate.reservation.infra.seat;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.seat.SeatId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 만료된 선점 좌석의 회수. <b>I-10 의 실행 주체이자 I-11 의 한쪽 당사자다.</b>
 *
 * <h2>왜 후보 SELECT 와 회수 UPDATE 를 나누는가</h2>
 *
 * <p>만료 대상을 찾는 조건({@code status}, {@code expires_at})과 잠금 순서를 정하는 기준(PK)이
 * 서로 다르기 때문이다. 만료 후보 인덱스 순서로 곧장 잠그면 확정 경로(PK 순서)와 교차해
 * 데드락이 난다 (CLAUDE.md 규칙 2). 그래서 후보를 먼저 읽고, PK 오름차순으로 정렬한 뒤,
 * 단일 벌크 UPDATE 로 회수한다.
 *
 * <p><b>그 대가로 후보 SELECT 와 UPDATE 사이에 창이 열린다.</b> 이 창을 긴 트랜잭션이나
 * 외부 락으로 막지 않는다. 대신 <b>후보를 현재 상태의 진실로 간주하지 않는다.</b>
 * 후보 조회는 탐색일 뿐이고, 성공 판정은 최종 조건부 UPDATE 의 {@code affected_rows} 가 한다.
 *
 * <h2>왜 창이 실제로 열리는가</h2>
 *
 * <p>{@link JdbcSeatPaymentRepository#confirm} 의 조건에는 {@code expires_at} 이 없다.
 * 즉 <b>이미 만료된 PAYING 좌석도 확정된다.</b> 결제 승인이 늦게 도착한 정상 요청이
 * 스위퍼가 회수하려는 바로 그 좌석을 확정하는 상황은 예외가 아니라 정상 경로다.
 * 이것이 I-11 이 다루는 경쟁이며, 스위퍼가 후보만 믿으면 <b>팔린 좌석을 다시 풀어버린다.</b>
 *
 * <h2>I-11 의 방어선</h2>
 *
 * <p>회수 UPDATE 가 네 조건을 <b>모두 다시</b> 검사한다.
 * <ul>
 *   <li>{@code (id, hold_id)} 쌍 — 그때 그 홀드를 여전히 잡고 있는가</li>
 *   <li>{@code status IN ('HELD','PAYING')} — <b>SOLD 를 상태 술어에서 구조적으로 배제한다</b></li>
 *   <li>{@code expires_at <= NOW(3)} — 그 사이 데드라인이 연장되지 않았는가</li>
 * </ul>
 * 만료 판정은 MySQL {@code NOW(3)} 이 한다. 애플리케이션 시각을 파라미터로 넘기면
 * 인스턴스 간 클럭 드리프트로 판정이 갈린다 (CLAUDE.md 규칙 7).
 *
 * <h2>스위퍼는 전부-또는-전무가 아니다</h2>
 *
 * <p>다좌석 선점(I-9)과 반대다. 후보 하나가 stale 하다고 나머지를 롤백하면 만료 좌석이 계속
 * 쌓이기만 한다. 유효한 후보는 회수하고, stale 한 후보는 {@code affected_rows} 에서 빠진다.
 * 후보 수와 회수 수가 다른 것은 오류가 아니라 정상이다.
 *
 * <p>그래서 자체 {@code TransactionTemplate} 과 {@code setRollbackOnly()} 를 쓰지 않는다.
 * 되돌릴 것이 없기 때문이다. 따라서 내부 rollback-only 가 외부 커밋 시점에
 * {@code UnexpectedRollbackException} 으로 나타나는 문제는 만들지 않는다.
 *
 * <h2>외부 트랜잭션 참여</h2>
 *
 * <p><b>그렇다고 외부 트랜잭션과 무관한 것은 아니다.</b> {@link JdbcTemplate} 은
 * {@code DataSourceUtils} 를 통해 커넥션을 얻으므로, 같은 {@link DataSource} 위에
 * 활성 트랜잭션이 있으면 <b>그 트랜잭션에 참여한다.</b>
 * 그때는 실제 커밋 시점과 잠금 보유 시간이 외부 트랜잭션 경계에 좌우된다.
 *
 * <p>현재 저장소 단독 호출 테스트가 검증하는 것은 autocommit 기반의 짧은 UPDATE 경로다.
 * 앞으로 quota 감소나 감사 로그를 이 회수와 같은 트랜잭션으로 묶는다면
 * <b>트랜잭션 경계는 애플리케이션 서비스가 소유해야 한다.</b>
 * 그 트랜잭션 안에 외부 I/O 를 넣지 않는다 (CLAUDE.md 규칙 10).
 *
 * <h2>이 클래스가 하지 않는 것</h2>
 *
 * <ul>
 *   <li><b>주기 실행 배선</b> — {@code @Scheduled} 등록은 앱 계층의 몫이다.
 *       여기는 한 번의 스윕만 수행한다.</li>
 *   <li><b>자발적 해제</b> (FR-2.5) — 도메인 전이({@code Seat.release})는 있으나
 *       인프라/API 경로가 없다.</li>
 *   <li><b>I-12</b> 1인당 좌석 상한 — {@code user_hold_quota} 테이블 자체가 없다.
 *       회수는 quota 감소가 연동돼야 할 지점 중 하나다.</li>
 *   <li><b>규칙 32</b> 감사 로그, <b>규칙 35</b> 메트릭 — 회수 이력과 계측이 없다.</li>
 * </ul>
 *
 * <h2>다중 스위퍼</h2>
 *
 * <p>여러 인스턴스가 동시에 스윕해도 <b>정합성은 유지된다.</b> 같은 후보를 읽더라도
 * 최종 조건부 UPDATE 가 다시 검증하므로 한쪽만 회수하고 다른 쪽은
 * {@code affected_rows = 0} 이 된다. 즉 중복 실행은 <b>정합성 문제가 아니라
 * 헛일과 잠금 경합이라는 효율 문제다.</b>
 *
 * <p>따라서 잡 락이 정합성을 위한 필수 조건은 아니다. 도입 여부는 실제 중복 실행 비율,
 * stale 비율, DB 부하를 측정한 뒤 판단한다. 잡 락 자체가 그것이 죽으면 스위퍼 전체가
 * 멈추는 새로운 가용성 위험을 만든다는 점도 함께 고려해야 한다.
 */
public class JdbcSeatExpiryRepository {

    /** 후보 조회가 사용하는 인덱스 (V2). 실행 계획 테스트가 이 값을 검증한다. */
    static final String CANDIDATE_INDEX = "idx_seat_inventory_expiry_candidate";

    /**
     * 만료 후보 조회. 행 잠금을 잡지 않는다.
     *
     * <p><b>{@code ORDER BY expires_at, id} 는 공정성을 위한 것이다.</b>
     * 배치 크기가 유한한 이상 어떤 후보를 먼저 고르느냐가 실질적인 회수 지연을 결정한다.
     * 정렬이 없으면 반환 순서가 인덱스 스캔 순서에 좌우되고, 특정 상태의 만료 행이 쌓이면
     * 다른 상태의 <b>더 오래된</b> 좌석이 매 배치에서 반복적으로 밀려난다.
     * {@code id} 는 동률일 때의 결정성을 위한 tie-breaker 다.
     *
     * <p><b>이 정렬은 잠금 순서와 무관하다.</b> 후보 SELECT 는 행 잠금을 잡지 않는다.
     * 잠금 순서는 {@link #expire} 가 Java 에서 id 오름차순으로 다시 정렬해 정한다.
     * 두 정렬의 목적이 다르다 — 이쪽은 <b>공정성</b>, 저쪽은 <b>데드락 방지</b>다.
     *
     * <p>{@code (expires_at, id)} 인덱스를 강제하는 이유는 filesort 를 피하기 위해서다.
     * 기존 {@code (status, expires_at)} 인덱스는 첫 컬럼이 {@code status} 라 전역
     * {@code ORDER BY expires_at} 을 만족하지 못하고, LIMIT 이 조기 종료하지 못해
     * 만료 후보 전부를 읽어 정렬한 뒤 잘라낸다 (V2 마이그레이션 주석에 실측 근거).
     *
     * <p><b>{@code hold_id IS NOT NULL} 조건이 있는 이유.</b>
     * 회수 UPDATE 는 좌석과 홀드 소유권을 {@code (id, hold_id)} 쌍으로 검증한다.
     * {@code hold_id} 가 없으면 검증할 대상 자체가 없으므로, 소유권 정보 없이
     * 임의로 {@code AVAILABLE} 로 되돌리지 않기 위해 후보에서 제외한다.
     *
     * <p><b>다만 그런 행은 정상 상태가 아니다.</b> HELD/PAYING 인데 {@code hold_id} 가 NULL 이면
     * 그 자체로 정합성 위반이며, 스위퍼가 손대지 않으므로 <b>영구히 회수되지 않고 남는다.</b>
     * 제외는 안전을 위한 선택이지 정상 처리가 아니다.
     *
     * <p>따라서 이런 행은 조용히 넘어갈 것이 아니라 <b>탐지하고 알려야 한다.</b>
     * 감사 로그(규칙 32)나 메트릭(규칙 35)이 구현되면 그 지점에서 다뤄야 한다.
     * 이번 Task 에서는 관측 기능을 구현하지 않았고 남은 위험으로만 기록했다
     * ({@code docs/experiments/TASK-002D-seat-expiry-sweeper.md}).
     *
     * <p>테스트가 이 상수를 그대로 EXPLAIN 한다. 복사한 SQL 과 운영 SQL 이 어긋나는 것을 막는다.
     */
    static final String FIND_CANDIDATES_SQL = """
            SELECT id, hold_id
              FROM seat_inventory FORCE INDEX (idx_seat_inventory_expiry_candidate)
             WHERE status IN ('HELD', 'PAYING')
               AND expires_at <= NOW(3)
               AND hold_id IS NOT NULL
             ORDER BY expires_at ASC, id ASC
             LIMIT ?
            """;

    private final JdbcTemplate jdbc;

    public JdbcSeatExpiryRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
    }

    /**
     * 만료 후보를 찾는다. 이 결과는 조회 시점의 스냅샷이며 현재 상태의 진실이 아니다.
     *
     * @throws IllegalArgumentException {@code batchSize} 가 0 이하인 경우
     */
    public List<ExpiryCandidate> findExpiredCandidates(int batchSize) {
        requirePositiveBatchSize(batchSize);

        return jdbc.query(FIND_CANDIDATES_SQL,
                (rs, rowNum) -> new ExpiryCandidate(
                        new SeatId(rs.getLong("id")),
                        HoldId.of(rs.getString("hold_id"))),
                batchSize);
    }

    /**
     * 후보를 회수한다. 각 후보에 대해 상태·홀드 소유권·만료 여부를 <b>다시</b> 검사한다.
     *
     * @return 실제로 회수된 행 수. 후보 수보다 적을 수 있으며 그것은 오류가 아니다.
     */
    public int expire(List<ExpiryCandidate> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        if (candidates.isEmpty()) {
            return 0;
        }

        // 원본 리스트를 정렬하지 않는다. 호출자의 컬렉션을 바꾸는 것도,
        // List.of(...) 같은 불변 리스트에서 터지는 것도 피해야 한다.
        List<ExpiryCandidate> lockOrdered = new ArrayList<>(candidates);
        lockOrdered.sort(Comparator.comparingLong(c -> c.seatId().value()));

        Object[] params = lockOrdered.stream()
                .flatMap(c -> java.util.stream.Stream.of(
                        (Object) c.seatId().value(), c.holdId().asString()))
                .toArray();

        return jdbc.update(expireSql(lockOrdered.size()), params);
    }

    /**
     * 후보를 찾아 곧바로 회수한다. 주기 실행 주체가 호출할 진입점이다.
     *
     * @return 실제로 회수된 행 수
     */
    public int sweep(int batchSize) {
        requirePositiveBatchSize(batchSize);
        return expire(findExpiredCandidates(batchSize));
    }

    // ------------------------------------------------------------------

    /**
     * 회수 UPDATE 를 만든다. <b>구조(플레이스홀더 개수)만 만들고 값은 전부 바인딩한다.</b>
     *
     * <p>{@code (id, hold_id) IN ((?,?), (?,?), ...)} 형태를 쓰는 이유는
     * <b>좌석과 홀드의 쌍을 함께 검증하기 위해서다.</b>
     * {@code id IN (...) AND hold_id IN (...)} 로 두 집합을 독립적으로 비교하면
     * 후보 A 의 좌석이 후보 B 의 홀드와 교차 매칭되어 엉뚱한 행이 회수된다.
     *
     * <p>{@code FORCE INDEX (PRIMARY)} 는 잠금 순서를 위한 것이다. 옵티마이저가
     * {@code idx_seat_inventory_sweep} 같은 다른 인덱스를 고르면 정렬해 넘긴 의미가 사라진다
     * (CLAUDE.md 규칙 3).
     *
     * <p>{@code reservation_id = NULL} 은 방어적 초기화다. HELD/PAYING 에는 원래 없지만,
     * 회수 후 AVAILABLE 의 필드 조합을 확실히 맞춘다.
     */
    static String expireSql(int candidateCount) {
        String tuples = IntStream.range(0, candidateCount)
                .mapToObj(i -> "(?, ?)")
                .collect(Collectors.joining(", "));

        return """
                UPDATE seat_inventory FORCE INDEX (PRIMARY)
                   SET status         = 'AVAILABLE',
                       hold_id        = NULL,
                       held_by        = NULL,
                       held_at        = NULL,
                       expires_at     = NULL,
                       reservation_id = NULL,
                       version        = version + 1
                 WHERE (id, hold_id) IN (%s)
                   AND status     IN ('HELD', 'PAYING')
                   AND expires_at <= NOW(3)
                """.formatted(tuples);
    }

    private static void requirePositiveBatchSize(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize 는 양수여야 한다: " + batchSize);
        }
    }
}
