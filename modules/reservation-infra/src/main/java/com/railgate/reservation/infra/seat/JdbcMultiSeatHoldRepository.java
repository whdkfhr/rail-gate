package com.railgate.reservation.infra.seat;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.hold.SeatCount;
import com.railgate.reservation.hold.SeatHoldPolicy;
import com.railgate.reservation.seat.SeatId;
import com.railgate.reservation.seat.SeatUnavailableException;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;
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
 * 요청 좌석 수와 다르면 하나 이상이 이미 남의 것이라는 뜻이다.
 *
 * <h2>왜 롤백이 필요한가</h2>
 *
 * <p>벌크 UPDATE 는 "가능한 것만 갱신하고 몇 개 했는지" 를 알려준다. 3석 중 2석이 가능하면
 * <b>그 2석을 실제로 HELD 로 바꾼 뒤</b> {@code affected_rows = 2} 를 돌려준다.
 * 여기서 멈추면 실패를 응답받은 요청이 2석을 점유한 채 남는다. 롤백이 그것을 되돌린다.
 *
 * <p><b>이 클래스는 최상위 트랜잭션의 롤백 여부를 결정하지 않는다.</b> 그러나 자신이 실행한
 * 벌크 UPDATE 에 대해서는 <b>savepoint 경계를 소유</b>한다. 경합이 발생하면 그 부분 UPDATE 를
 * savepoint 까지 되돌린 뒤 같은 예외를 재전파한다. 이유는 아래 절에 있다.
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
 * <h2>트랜잭션 경계는 호출자가 소유한다 (Task 2F)</h2>
 *
 * <p><b>이 클래스는 최상위 트랜잭션을 만들지 않는다.</b> 호출자가 같은 {@link DataSource}
 * 위에 이미 열어둔 최상위 트랜잭션에 <b>참여</b>하며, 그 트랜잭션을 커밋할지 롤백할지는
 * 호출자가 결정한다.
 *
 * <p>다만 그 안에 <b>자신의 벌크 UPDATE 만을 위한 {@code NESTED} savepoint 경계</b>는
 * 만든다. 이것은 최상위 트랜잭션을 나누는 것이 아니라 그 안에 로컬 원자성 구간을 두는 것이다.
 *
 * <p>이전 구현은 자체 {@code DataSourceTransactionManager} 로 트랜잭션을 열고 부분 실패 시
 * {@code status.setRollbackOnly()} 를 호출했다. 독립 호출에서는 새 트랜잭션이 생기므로
 * local rollback-only 로 끝났지만, <b>외부 트랜잭션에 참여하면 그 호출이 남의 트랜잭션
 * 전체를 rollback-only 로 만들었다.</b> 저장소는 정상 결과값을 돌려주는데 외부 커밋 시점에
 * {@code UnexpectedRollbackException} 이 터졌다. Task 2F 에서 재현한 뒤 구조를 바꿨다.
 *
 * <p>지금은 세 가지로 그 문제를 없앤다.
 * <ul>
 *   <li><b>벌크 UPDATE 를 {@code PROPAGATION_NESTED} savepoint 안에서 실행한다.</b>
 *       부분 갱신이 생기면 <b>savepoint 까지만</b> 되돌리고 같은 예외를 다시 전파한다.
 *       외부 트랜잭션은 rollback-only 가 되지 않는다.</li>
 *   <li><b>부분 실패를 예외로 알린다.</b> {@link SeatUnavailableException} 이다.
 *       저장소가 남의 트랜잭션 상태를 건드리지 않는다.</li>
 *   <li><b>트랜잭션이 없으면 UPDATE 전에 거부한다.</b> 트랜잭션 없이 실행하면 벌크 UPDATE 가
 *       autocommit 으로 확정되어, 부분 갱신을 되돌릴 수단이 사라진다 (I-9 붕괴).</li>
 * </ul>
 *
 * <h2>왜 예외 전파만으로는 부족한가</h2>
 *
 * <p>예외 전파에만 의존하면 <b>호출자가 트랜잭션 경계 안에서 그 예외를 잡는 순간 I-9 가 깨진다.</b>
 * 벌크 UPDATE 가 이미 바꿔놓은 좌석이 그대로 남고, 호출자가 계속 진행해 커밋하면
 * <b>실패한 요청이 좌석 일부를 점유한 채 확정</b>된다. 경합을 정상 흐름으로 처리하는 것은
 * 이 도메인에서 자연스러운 코드이므로 (규칙 21) 그 상황을 배선 실수로 취급할 수 없다.
 *
 * <p>그래서 저장소가 <b>자신의 UPDATE 만큼은 스스로 되돌린다.</b> savepoint 가 그 범위를 준다.
 * 되돌린 뒤 예외를 다시 던지므로, 호출자가 잡지 않으면 최상위 경계까지 전파되어
 * 같은 트랜잭션의 다른 변경(향후 I-12 quota 증가 등)도 함께 롤백된다.
 *
 * <h2>savepoint 의 범위는 이 저장소의 UPDATE 뿐이다</h2>
 *
 * <p>{@code PROPAGATION_NESTED} 는 <b>로컬 원자성</b>에만 쓴다. 유스케이스 전체의 원자성은
 * 여전히 호출자가 소유한 최상위 트랜잭션이 책임진다. 두 경우가 다르게 동작한다.
 * <ul>
 *   <li><b>호출자가 예외를 전파</b> → savepoint 롤백 후 최상위 트랜잭션도 롤백.
 *       좌석과 quota 역할 변경이 <b>함께</b> 사라진다.</li>
 *   <li><b>호출자가 예외를 트랜잭션 안에서 catch</b> → 저장소의 부분 좌석 변경만 이미
 *       savepoint 로 되돌아간 상태이고, 최상위 트랜잭션은 <b>계속 진행 가능</b>하다.</li>
 * </ul>
 *
 * <p>후자에서 호출자가 무엇을 커밋할지는 <b>호출자의 책임</b>이다. 좌석 없이 quota 만
 * 증가시키고 커밋하면 카운터가 어긋난다. 이 저장소가 막을 수 있는 범위 밖이다.
 *
 * <p>{@code REQUIRES_NEW} 는 쓰지 않는다. 좌석이 독립 트랜잭션으로 확정되면 최상위 롤백이
 * 닿지 않아 quota 와 좌석이 서로 다른 시점에 확정된다. savepoint 는 <b>같은 트랜잭션·같은
 * 커넥션</b> 안에 머무르므로 그 문제가 없고 커넥션도 추가로 점유하지 않는다.
 * "성공한 선점이 외부 롤백으로 함께 사라진다" 는 테스트가 그 차이를 고정한다.
 *
 * <h2>{@link SeatUnavailableException} 의 HTTP 매핑</h2>
 *
 * <p>앱/API 계층이 이 예외를 <b>409 Conflict</b> 로 매핑한다 (CLAUDE.md 규칙 21).
 * 그 매핑은 후속 범위이며 도메인 예외가 HTTP 상태를 소유하지 않는다.
 *
 * <p><b>주의.</b> 매핑은 <b>트랜잭션 경계 밖</b>에서 해야 한다. 애플리케이션 서비스가
 * 트랜잭션 <b>안</b>에서 이 예외를 잡아 성공 결과로 삼키면, 좌석은 savepoint 로 되돌아간
 * 반면 같은 트랜잭션의 quota 증가는 그대로 커밋된다. 좌석 없이 카운터만 오르는 상태다.
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

    private final Object transactionResourceKey;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate savepointTemplate;
    private final long holdSeconds;

    /**
     * @param dataSource         좌석 테이블이 있는 데이터 소스
     * @param transactionManager <b>이 {@code dataSource} 와 같은 트랜잭션 리소스를 관리하는</b>
     *                           JDBC 트랜잭션 관리자. 저장소가 직접 만들지 않고 주입받는다.
     *                           <b>같은 인스턴스일 필요는 없다</b> — 호출자가 쓰는 관리자와
     *                           다른 인스턴스여도, 같은 {@code DataSource} 리소스를 관리하면
     *                           같은 스레드에 바인딩된 트랜잭션에 참여해 savepoint 를 만든다.
     *                           조건은 <b>인스턴스 동일성이 아니라 리소스 동일성</b>이다.
     * @param holdDuration       선점 유지 시간. REQUIREMENTS.md P-4 기준 5분.
     * @throws IllegalArgumentException {@code transactionManager} 가 다른 {@code DataSource} 를
     *                                  관리하는 경우. 아래 {@link #requireSameTransactionResource} 참고.
     */
    public JdbcMultiSeatHoldRepository(
            DataSource dataSource,
            DataSourceTransactionManager transactionManager,
            Duration holdDuration) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(transactionManager, "transactionManager");
        // SQL 을 실행하기 전, 트랜잭션을 시작하기 전, 객체가 만들어지기 전에 배선을 검증한다.
        requireSameTransactionResource(dataSource, transactionManager);
        this.transactionResourceKey = transactionResourceKey(dataSource);

        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
        this.savepointTemplate = new TransactionTemplate(transactionManager);
        // 이 저장소의 UPDATE 만 되돌리기 위한 로컬 경계다. 최상위 트랜잭션은 호출자가 소유한다.
        this.savepointTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        this.holdSeconds = Objects.requireNonNull(holdDuration, "holdDuration").toSeconds();
        if (holdSeconds <= 0) {
            throw new IllegalArgumentException("holdDuration 은 양수여야 한다: " + holdDuration);
        }
    }

    /**
     * 좌석 전부를 하나의 홀드로 선점한다. 하나라도 실패하면 어떤 좌석도 점유하지 않는다.
     *
     * <p><b>호출자가 연 트랜잭션 안에서 실행해야 한다.</b> 성공한 선점은 그 트랜잭션이
     * 커밋할 때 반영되고, 롤백하면 함께 사라진다.
     *
     * <p>반환값이 없다. 정상 종료가 곧 "요청 좌석 전부를 선점했다" 는 뜻이다.
     * 부분 성공이라는 결과가 존재하지 않으므로 (I-9) 성공을 표현할 값이 하나뿐이고,
     * 값이 하나뿐인 결과 타입은 아무 정보도 전달하지 않는다.
     *
     * @throws NullPointerException       인자가 null 인 경우
     * @throws IllegalArgumentException   좌석 수가 1~4석이 아니거나 중복된 좌석이 있는 경우
     * @throws IllegalStateException      이 저장소의 {@link DataSource} 에 활성 트랜잭션이 없는 경우.
     *                                    <b>호출자의 배선 실수이며 좌석 경합이 아니다.</b>
     * @throws SeatUnavailableException   요청 좌석 중 하나 이상을 선점할 수 없는 경우.
     *                                    <b>예상 가능한 정상 실패이며 오류가 아니다</b> (규칙 21).
     *
     * <p>앞의 세 예외는 모두 <b>UPDATE 전에</b> 발생하므로 DB 는 건드려지지 않는다.
     *
     * <h2>{@link SeatUnavailableException} 이 났을 때의 순서</h2>
     *
     * <p>이 예외만 <b>벌크 UPDATE 후에</b> 발생할 수 있다. 그 시점에는 요청 좌석 중 일부가
     * 이미 {@code HELD} 로 바뀌어 있다. 되돌아가는 순서는 다음과 같다.
     *
     * <ol>
     *   <li><b>NESTED savepoint 경계가 먼저 롤백한다.</b> 이 저장소가 실행한 부분 UPDATE 가
     *       savepoint 까지 제거된다.</li>
     *   <li><b>같은 {@link SeatUnavailableException} 이 호출자에게 재전파된다.</b>
     *       저장소가 잡거나 다른 예외로 감싸지 않는다.</li>
     *   <li>호출자가 예외를 <b>트랜잭션 경계 밖까지 전파</b>하면 최상위 트랜잭션도 롤백되어,
     *       같은 트랜잭션의 다른 변경(향후 I-12 quota 증가 등)도 함께 사라진다.</li>
     *   <li>호출자가 <b>트랜잭션 안에서 예외를 잡으면</b> 최상위 트랜잭션은 계속 진행할 수 있다.
     *       그래도 <b>좌석의 부분 선점은 1단계에서 이미 제거된 상태</b>이므로 I-9 는 유지된다.</li>
     * </ol>
     *
     * <p>즉 부분 갱신 복구는 호출자의 롤백에 의존하지 않는다. 호출자의 롤백은
     * <b>유스케이스 전체</b>의 원자성을 담당하고, savepoint 는 <b>이 저장소의 UPDATE</b> 만 담당한다.
     */
    public void holdAll(List<SeatId> seatIds, HoldId holdId, UserId userId) {
        Objects.requireNonNull(seatIds, "seatIds");
        Objects.requireNonNull(holdId, "holdId");
        Objects.requireNonNull(userId, "userId");

        // FR-2.3. 도메인의 값 객체를 그대로 쓴다. 한 요청의 좌석 수 규칙이 두 곳에 생기면 어긋난다.
        SeatCount requested = new SeatCount(seatIds.size());
        requireNoDuplicates(seatIds);
        requireEnlistedTransaction();

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

        // savepoint 안에는 벌크 UPDATE 와 affected_rows 판정만 둔다.
        // 트랜잭션 안에서는 DB 접근만 한다. 외부 I/O 와 로그 전송은 넣지 않는다
        // (CLAUDE.md 규칙 10).
        //
        // 예외가 이 콜백 밖으로 나가면 TransactionTemplate 이 savepoint 까지 롤백한 뒤
        // 같은 예외를 그대로 다시 던진다. 여기서 잡거나 다른 예외로 감싸지 않는다.
        savepointTemplate.executeWithoutResult(status -> {
            int affectedRows = jdbc.update(HOLD_ALL_SQL, params);

            if (affectedRows != requested.value()) {
                // 일부만 갱신됐다면 그 일부가 이미 HELD 로 바뀌어 있다.
                // savepoint 롤백이 그것을 되돌린다.
                //
                // 어떤 좌석이 왜 불가능했는지는 담지 않는다. 원인을 구분해 노출하면
                // 특정 좌석의 점유 여부를 탐색하는 수단이 된다.
                throw new SeatUnavailableException(
                        "요청한 좌석 중 하나 이상을 선점할 수 없어 요청 전체가 실패했다");
            }
        });
    }

    /**
     * 이 저장소의 {@link DataSource} 에 묶인 활성 트랜잭션이 있는지 확인한다.
     *
     * <p><b>"아무 트랜잭션이나 활성인가" 로는 부족하다.</b> 다른 {@link DataSource} 위의
     * 트랜잭션이 열려 있어도 {@code isActualTransactionActive()} 는 참이 된다. 그 상태로
     * UPDATE 하면 이 저장소의 커넥션은 그 트랜잭션에 참여하지 않고 <b>autocommit 으로
     * 확정</b>되어, 부분 갱신을 되돌릴 수단이 사라진다.
     *
     * <p>그래서 {@code hasResource(...)} 로 <b>이 DataSource 의 커넥션이 실제로
     * 트랜잭션 리소스로 바인딩됐는지</b>를 함께 본다. 키는 생성자에서 만든
     * {@link #transactionResourceKey} 를 쓴다 — 생성자 검증과 같은 기준이어야 한다.
     *
     * <p>UPDATE 를 보내기 전에 검사하므로, 실패해도 DB 는 건드려지지 않는다.
     *
     * <p><b>NESTED 실행보다 먼저 호출해야 한다.</b> Spring 의 {@code PROPAGATION_NESTED} 는
     * 기존 트랜잭션이 없으면 savepoint 대신 <b>새 트랜잭션을 연다</b>({@code REQUIRED} 처럼
     * 동작한다). 그대로 두면 저장소가 최상위 트랜잭션을 만들게 되어, 이 클래스가 트랜잭션을
     * 소유하지 않는다는 계약이 깨진다.
     */
    private void requireEnlistedTransaction() {
        boolean enlisted = TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.hasResource(transactionResourceKey);
        if (!enlisted) {
            throw new IllegalStateException(
                    "다좌석 선점은 호출자가 연 트랜잭션 안에서 실행해야 한다. "
                            + "트랜잭션 없이 실행하면 부분 갱신이 autocommit 으로 확정되어 "
                            + "I-9(전부-또는-전무)를 지킬 수 없다.");
        }
    }

    /**
     * 저장소의 {@link DataSource} 와 트랜잭션 관리자가 <b>같은 트랜잭션 리소스</b>를 쓰는지 확인한다.
     *
     * <h2>어긋나면 무엇이 깨지는가</h2>
     *
     * <p>관리자가 다른 {@code DataSource} 를 관리하면 다음이 일어난다.
     * <ol>
     *   <li>호출자가 좌석 DataSource A 위에 트랜잭션을 연다.</li>
     *   <li>{@link #requireEnlistedTransaction()} 는 A 기준으로 검사하므로 <b>통과한다.</b></li>
     *   <li>savepoint 템플릿은 관리자 B 로 실행된다. B 에는 바인딩된 트랜잭션이 없으므로
     *       savepoint 가 아니라 <b>B 위의 새 트랜잭션</b>이 열린다.</li>
     *   <li>좌석 UPDATE 는 A 의 커넥션에서 실행되어 <b>savepoint 의 보호를 받지 못한다.</b></li>
     *   <li>호출자가 {@link SeatUnavailableException} 을 트랜잭션 안에서 잡고 커밋하면
     *       <b>부분 선점이 확정된다 — I-9 붕괴.</b></li>
     * </ol>
     *
     * <p>문서 경고로는 막을 수 없다. 생성 시점에 실패시킨다.
     *
     * <h2>비교 기준 — 인스턴스가 아니라 리소스</h2>
     *
     * <p>서로 다른 {@link DataSourceTransactionManager} 인스턴스라도 같은 {@code DataSource} 를
     * 관리하면 같은 스레드 리소스에 참여한다. 그래서 <b>관리자 인스턴스 동일성은 조건이 아니다.</b>
     *
     * <p>비교는 {@link #transactionResourceKey} 가 만든 <b>정규화된 키</b>로 한다.
     * 그래야 "저장소가 참여 가능하다고 판단하는 기준" 과 "Spring 이 실제로 리소스를 찾는 기준" 이
     * 어긋나지 않는다.
     */
    private static void requireSameTransactionResource(
            DataSource dataSource, DataSourceTransactionManager transactionManager) {

        DataSource managed = transactionManager.getDataSource();
        if (managed == null) {
            throw new IllegalArgumentException("트랜잭션 관리자에 DataSource 가 설정되어 있지 않다");
        }

        Object repositoryKey = transactionResourceKey(dataSource);
        Object managerKey = transactionResourceKey(managed);

        if (!repositoryKey.equals(managerKey)) {
            throw new IllegalArgumentException(
                    "트랜잭션 관리자가 이 저장소와 다른 DataSource 트랜잭션 리소스를 관리한다. "
                            + "savepoint 가 좌석 UPDATE 와 다른 리소스에 생성되어 "
                            + "부분 선점을 되돌리지 못한다 (I-9). "
                            + "저장소 리소스=" + repositoryKey
                            + ", 트랜잭션 관리자 리소스=" + managerKey);
        }
    }

    /**
     * Spring 이 트랜잭션 리소스를 찾을 때 쓰는 키로 정규화한다.
     *
     * <p>두 단계를 적용한다. <b>둘 다 Spring 자신의 동작을 그대로 따른 것이다.</b>
     * <ol>
     *   <li>{@link TransactionAwareDataSourceProxy} 를 대상 {@code DataSource} 로 벗긴다.
     *       {@link DataSourceTransactionManager#setDataSource} 가 같은 일을 하기 때문이다 —
     *       프록시를 넘겨도 관리자는 <b>대상</b> DataSource 로 트랜잭션을 연다.
     *       이 단계를 빼면 저장소와 관리자에 같은 프록시를 줬는데도 키가 어긋난다
     *       (실제로 관측했다. {@code MultiSeatHoldWiringTest} 참고).</li>
     *   <li>{@link TransactionSynchronizationUtils#unwrapResourceIfNecessary} 를 적용한다.
     *       {@code TransactionSynchronizationManager} 가 리소스를 조회할 때 쓰는 정규화다.</li>
     * </ol>
     *
     * <p><b>한계.</b> 2단계는 {@code InfrastructureProxy}/{@code ScopedObject} 만 벗긴다.
     * Spring 6.2 의 {@code LazyConnectionDataSourceProxy} 는 그 인터페이스를 구현하지 않으므로
     * 벗겨지지 않는다. 그런 프록시를 한쪽에만 씌우면 여기서 거부된다. 거부되는 편이 안전하다 —
     * 통과시키면 savepoint 가 다른 리소스에 생겨 I-9 가 조용히 깨진다.
     * 앱 배선에서는 <b>저장소와 관리자에 같은 DataSource 참조를 주는 것</b>이 정답이다.
     */
    private static Object transactionResourceKey(DataSource dataSource) {
        DataSource target = dataSource;
        while (target instanceof TransactionAwareDataSourceProxy proxy
                && proxy.getTargetDataSource() != null) {
            target = proxy.getTargetDataSource();
        }
        return TransactionSynchronizationUtils.unwrapResourceIfNecessary(target);
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
