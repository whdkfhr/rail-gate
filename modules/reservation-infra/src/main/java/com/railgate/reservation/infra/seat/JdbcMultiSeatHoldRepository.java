package com.railgate.reservation.infra.seat;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.hold.SeatCount;
import com.railgate.reservation.hold.SeatHoldPolicy;
import com.railgate.reservation.seat.SeatId;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 다좌석 원자 선점. <b>I-9(여러 좌석 예약은 전부 선점되거나 전부 실패한다)의 방어선이다.</b>
 *
 * <h2>왜 반복문이 아니라 단일 벌크 UPDATE 인가</h2>
 *
 * <p>좌석마다 개별 조건부 UPDATE 를 반복하면, <b>각 문장이 개별적으로는 올바른데도</b>
 * 요청 전체로는 깨진다. 중간에 한 좌석이 불가능해지는 순간 앞서 잡은 좌석들이 이미 갱신된
 * 상태이기 때문이다. 즉 <b>단일 좌석 수준의 정확성만으로는 I-9 를 얻을 수 없다.</b>
 *
 * <p>단일 벌크 UPDATE 는 {@code affected_rows} 하나로 전부/전무를 판정하게 해준다.
 * 요청 좌석 수와 다르면 하나 이상이 이미 남의 것이라는 뜻이고, 그때는 트랜잭션을 롤백해
 * 이 요청이 만든 변경을 전부 되돌린다.
 *
 * <h2>왜 롤백이 필요한가</h2>
 *
 * <p>벌크 UPDATE 는 "가능한 것만 갱신하고 몇 개 했는지" 를 알려준다. 3석 중 2석이 가능하면
 * <b>그 2석을 실제로 HELD 로 바꾼 뒤</b> {@code affected_rows = 2} 를 돌려준다.
 * 여기서 멈추면 실패를 응답받은 요청이 2석을 점유한 채 남는다. 롤백이 그것을 되돌린다.
 *
 * <h2>왜 PK 오름차순 정렬과 FORCE INDEX 인가</h2>
 *
 * <p>여러 행을 잠그므로 데드락이 새로운 실패 양식으로 등장한다. 서로 겹치는 좌석 집합을
 * 요청하는 두 트랜잭션의 행 접근 순서가 어긋나면 순환 대기가 될 수 있다 (CLAUDE.md 규칙 2).
 *
 * <p><b>다만 실제 행 접근 순서는 전달한 배열 순서만으로 정해지지 않는다.</b>
 * MySQL 의 실행 계획과 선택된 인덱스가 함께 영향을 준다. 그래서 두 가지를 같이 쓴다.
 * <ul>
 *   <li>{@link SeatHoldPolicy#lockOrder} 로 정렬된 ID 를 전달한다.</li>
 *   <li>{@code FORCE INDEX (PRIMARY)} 로 옵티마이저가 다른 인덱스를 고를 여지를 없앤다
 *       (CLAUDE.md 규칙 3). {@code status = 'AVAILABLE'} 조건 때문에 그런 여지가 실제로 있다.</li>
 * </ul>
 * {@code EXPLAIN} 어서션 테스트가 계획이 PRIMARY 를 벗어나는 것을 감지한다.
 * 동시성 테스트에서 데드락이 관측되지 않은 것은 보조 근거이며 데드락 부재의 증명은 아니다.
 *
 * <h2>⚠️ 트랜잭션 경계 — 현재 검증 범위</h2>
 *
 * <p>이 클래스는 자체 {@link org.springframework.jdbc.datasource.DataSourceTransactionManager}
 * 로 트랜잭션을 열고, 부분 실패 시 {@code status.setRollbackOnly()} 로 되돌린다.
 *
 * <p><b>검증된 것은 "외부 트랜잭션이 없는 독립 호출" 범위뿐이다.</b>
 * 그 범위에서는 트랜잭션이 새로 생성되므로 local rollback-only 로 표시되고,
 * {@link TransactionTemplate} 이 롤백한 뒤 예외 없이 반환값을 돌려준다.
 *
 * <p><b>외부 트랜잭션에 참여하면 동작이 달라질 수 있다.</b> 애플리케이션 서비스가
 * 같은 {@link DataSource} 위에서 트랜잭션을 먼저 열어둔 상태로 이 메서드를 호출하면,
 * 여기서는 새 트랜잭션이 생기지 않고 기존 트랜잭션에 참여한다. 그때의
 * {@code setRollbackOnly()} 는 <b>외부 트랜잭션 전체를 rollback-only 로 만들고</b>,
 * 외부 커밋 시점에 {@code UnexpectedRollbackException} 이 발생할 수 있다.
 *
 * <p>따라서 앱 배선 시 <b>트랜잭션 경계를 애플리케이션 계층이 소유하도록 재검토해야 한다.</b>
 * I-12 카운터나 감사 로그를 이 작업과 하나의 트랜잭션으로 묶기 전에,
 * 외부 트랜잭션 참여 상황을 다루는 통합 테스트가 먼저 필요하다.
 * 이번 Task 에서는 구조를 바꾸지 않고 위험만 기록한다.
 *
 * <h2>외부 구성에 의존하는 것</h2>
 *
 * <p>{@link JdbcSeatHoldRepository} 와 마찬가지로 세션에
 * {@code innodb_lock_wait_timeout = 3} 이 설정되어 있어야 한다 (CLAUDE.md 규칙 9).
 * 여러 행을 하나의 트랜잭션에서 갱신하므로 단일 문장 autocommit 경로보다
 * 잠금 범위와 경합 영향이 커질 수 있다. 실제 잠금 보유 시간은 환경과 경합 정도에 따라
 * 달라지며 별도 측정이 필요하다. 아직 측정하지 않았다.
 * <b>이 설정은 이 클래스가 강제할 수 없으며 DataSource 구성의 책임이다.</b>
 * 현재는 테스트 픽스처에만 적용되어 있고 운영 DataSource 는 아직 존재하지 않는다.
 *
 * <h2>이 클래스가 보장하지 않는 것</h2>
 *
 * <ul>
 *   <li><b>I-12</b> 1인당 좌석 상한 — {@code user_hold_quota} 테이블 자체가 없다.
 *       구현할 때는 이 클래스의 선점 시 증가뿐 아니라, SOLD 확정
 *       ({@link JdbcSeatPaymentRepository})·만료 회수({@link JdbcSeatExpiryRepository})·
 *       자발적 해제에 대응하는 감소를 같은 트랜잭션 경계에서 연동해야 한다.
 *       세 경로의 상태 전이는 모두 구현됐고 quota 연동만 없다.</li>
 *   <li><b>규칙 32</b> 감사 로그 — {@code seat_state_log} 테이블과 기록 로직이 없다.
 *       {@code actor}/{@code reason}/{@code traceId} 를 전달할 앱 계층이 아직 없다.</li>
 *   <li><b>규칙 35</b> Micrometer 메트릭 — 선점 성공/실패 계측이 없다.</li>
 *   <li>멱등성, 결제, 해제, 만료 스위퍼.</li>
 * </ul>
 *
 * <p>{@link JdbcSeatHoldRepository} 는 단일 좌석 CAS 검증을 위해 그대로 둔다.
 * 아직 앱에 배선된 것이 없으므로 둘 중 어느 것이 운영 진입점인지는 정해지지 않았다.
 */
public class JdbcMultiSeatHoldRepository {

    /**
     * 좌석 전부를 한 문장으로 선점한다.
     *
     * <p>{@code IN} 절은 {@link NamedParameterJdbcTemplate} 이 바인딩한다.
     * 값을 문자열로 이어 붙이면 SQL 인젝션 표면이 생기고 조립 코드를 직접 관리해야 한다.
     *
     * <p>다만 컬렉션 파라미터는 실행 시 <b>좌석 수만큼의 {@code ?} 로 확장</b>되므로,
     * 최종 프리페어드 스테이트먼트의 형태는 좌석 수에 따라 달라진다.
     * 여기서 얻는 것은 인젝션 방지와 안전한 바인딩이지 statement 형태의 동일성이 아니다.
     * 좌석 수가 1~4석으로 제한되어 있어 형태 차이 자체는 문제가 되지 않는다.
     */
    private static final String HOLD_ALL_SQL = """
            UPDATE seat_inventory FORCE INDEX (PRIMARY)
               SET status     = 'HELD',
                   hold_id    = :holdId,
                   held_by    = :userId,
                   held_at    = NOW(3),
                   expires_at = DATE_ADD(NOW(3), INTERVAL :holdSeconds SECOND),
                   version    = version + 1
             WHERE id     IN (:seatIds)
               AND status = 'AVAILABLE'
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final long holdSeconds;

    /**
     * @param holdDuration 선점 유지 시간. REQUIREMENTS.md P-4 기준 5분.
     */
    public JdbcMultiSeatHoldRepository(DataSource dataSource, Duration holdDuration) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
        this.transactionTemplate =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.holdSeconds = Objects.requireNonNull(holdDuration, "holdDuration").toSeconds();
        if (holdSeconds <= 0) {
            throw new IllegalArgumentException("holdDuration 은 양수여야 한다: " + holdDuration);
        }
    }

    /**
     * 좌석 전부를 하나의 홀드로 선점한다. 하나라도 실패하면 어떤 좌석도 점유하지 않는다.
     *
     * @throws NullPointerException     인자가 null 인 경우
     * @throws IllegalArgumentException 좌석 수가 1~4석이 아니거나 중복된 좌석이 있는 경우.
     *                                  <b>트랜잭션에 진입하기 전에 검증하므로</b> 이 예외가 나면
     *                                  DB 는 전혀 건드려지지 않은 상태다.
     */
    public MultiSeatHoldOutcome holdAll(List<SeatId> seatIds, HoldId holdId, UserId userId) {
        Objects.requireNonNull(seatIds, "seatIds");
        Objects.requireNonNull(holdId, "holdId");
        Objects.requireNonNull(userId, "userId");

        // FR-2.3. 도메인의 값 객체를 그대로 쓴다. 한 요청의 좌석 수 규칙이 두 곳에 생기면 어긋난다.
        SeatCount requested = new SeatCount(seatIds.size());
        requireNoDuplicates(seatIds);

        // 원본 리스트를 정렬하지 않는다. 호출자의 컬렉션을 조용히 바꾸는 것도,
        // List.of(...) 같은 불변 리스트에서 터지는 것도 피해야 한다.
        List<Long> lockOrderedIds = SeatHoldPolicy.lockOrder(seatIds).stream()
                .map(SeatId::value)
                .toList();

        Map<String, Object> params = Map.of(
                "holdId", holdId.asString(),
                "userId", userId.value(),
                "holdSeconds", holdSeconds,
                "seatIds", lockOrderedIds);

        // 트랜잭션 안에서는 DB 접근만 한다. 외부 I/O 와 로그 전송은 넣지 않는다
        // (CLAUDE.md 규칙 10).
        return transactionTemplate.execute(status -> {
            int affectedRows = jdbc.update(HOLD_ALL_SQL, params);

            if (affectedRows == requested.value()) {
                return MultiSeatHoldOutcome.HELD;
            }

            // 일부만 갱신됐다면 그 일부가 이미 HELD 로 바뀌어 있다. 되돌린다.
            //
            // 외부 트랜잭션이 없는 독립 호출에서는 여기서 새 트랜잭션이 열리므로
            // local rollback-only 로 표시되고, TransactionTemplate 이 롤백한 뒤
            // 예외 없이 이 반환값을 돌려준다. 현재 테스트가 검증한 범위가 그것이다.
            //
            // 외부 트랜잭션에 참여하는 경우에는 전역 rollback-only 가 되어
            // 외부 커밋 시 UnexpectedRollbackException 이 날 수 있다. 클래스 JavaDoc 참고.
            status.setRollbackOnly();
            return MultiSeatHoldOutcome.SEAT_UNAVAILABLE;
        });
    }

    private static void requireNoDuplicates(List<SeatId> seatIds) {
        Set<SeatId> distinct = new HashSet<>(seatIds);
        if (distinct.size() != seatIds.size()) {
            // 중복을 허용하면 affected_rows 가 요청 수보다 구조적으로 작아져
            // 정상 요청이 항상 실패한다.
            throw new IllegalArgumentException("같은 좌석을 중복해서 선점할 수 없다: " + seatIds);
        }
    }
}
