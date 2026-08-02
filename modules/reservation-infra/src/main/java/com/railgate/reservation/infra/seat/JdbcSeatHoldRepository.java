package com.railgate.reservation.infra.seat;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.seat.SeatId;
import java.time.Duration;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 단일 좌석 선점. <b>I-8 의 좌석 선점 단계 방어선이다.</b>
 *
 * <h2>어디까지 방어하는가</h2>
 *
 * <p>이 클래스가 막는 것은 {@code AVAILABLE → HELD} 경쟁 하나다.
 * 여러 요청이 같은 좌석을 동시에 선점하려 할 때 정확히 하나만 통과시킨다.
 *
 * <p>I-8 의 문장은 "하나의 좌석은 <b>최종적으로 한 예약에만 확정된다</b>" 이고,
 * 그 확정 단계는 {@link JdbcSeatPaymentRepository} 가 담당한다.
 * 선점만으로는 I-8 이 완결되지 않으며, 두 클래스가 함께 좌석 재고 수준의 전이를 이룬다.
 *
 * <pre>
 * AVAILABLE ──hold──▶ HELD ──startPayment──▶ PAYING ──confirm──▶ SOLD
 * (이 클래스)          (JdbcSeatPaymentRepository)
 * </pre>
 *
 * <h2>왜 조건부 UPDATE 한 번인가</h2>
 *
 * <p>상태를 읽어 판단한 뒤 갱신하면, 읽기와 쓰기 사이에 창이 열린다.
 * 그 창에서 다른 세션이 같은 좌석을 가져가도 알 방법이 없다(TOCTOU, CLAUDE.md 규칙 1).
 * 창의 크기는 문제의 본질이 아니다. 창이 존재한다는 것 자체가 문제다.
 *
 * <p>조건부 UPDATE 는 <b>검사와 갱신을 한 문장으로 합쳐 창을 없앤다.</b>
 * 성립 근거는 세 가지가 겹쳐 있다.
 *
 * <ol>
 *   <li><b>좌석당 행이 하나뿐이다.</b> {@code UNIQUE (schedule_id, seat_no)} 때문에
 *       "같은 좌석을 두 명이 선점" 은 "같은 행을 {@code AVAILABLE} 에서 두 번 전이" 와 같은 말이 된다.</li>
 *   <li><b>InnoDB 가 그 행의 갱신을 직렬화한다.</b> 두 세션이 같은 행을 UPDATE 하면
 *       한쪽이 행 잠금을 얻고 다른 쪽은 대기한다. 동시에 통과할 수 없다.</li>
 *   <li><b>대기하던 쪽은 갱신된 값을 다시 평가한다.</b> 잠금이 풀린 뒤 {@code WHERE status='AVAILABLE'}
 *       를 재검사하므로, 앞선 세션이 {@code HELD} 로 바꿨다면 조건이 불일치해
 *       {@code affected_rows} 가 0 이 된다.</li>
 * </ol>
 *
 * <p>따라서 성공 판정은 {@code affected_rows} 하나로 끝난다.
 * <b>상태를 다시 조회해 확인하지 않는다.</b> 재조회는 또 하나의 창을 만들 뿐이며,
 * 그 사이 좌석이 다시 바뀌어도 알 수 없다. 갱신을 수행한 문장이 몇 행을 바꿨는지가
 * 그 시점의 유일하게 신뢰할 수 있는 사실이다.
 *
 * <h2>이 클래스가 보장하지 않는 것</h2>
 *
 * <ul>
 *   <li><b>I-8 의 확정 단계</b> — {@link JdbcSeatPaymentRepository} 가 담당한다.</li>
 *   <li><b>I-9</b> 다좌석 전부-또는-전무 — 단일 벌크 UPDATE 의 {@code affected_rows} 검증이 담당한다.
 *       후속 Task.</li>
 *   <li><b>I-12</b> 1인당 좌석 상한 — {@code user_hold_quota} 카운터 행의 조건부 UPDATE 가 담당한다.
 *       후속 Task.</li>
 * </ul>
 *
 * <h2>운영 DataSource 요구사항</h2>
 *
 * <p>세션에 {@code innodb_lock_wait_timeout = 3} 을 설정해야 한다 (CLAUDE.md 규칙 9).
 * 기본값 50초는 경합 시 커넥션 풀을 고갈시킨다. 승자의 트랜잭션이 잠깐 늦어지면
 * 나머지 요청이 전부 50초씩 커넥션을 점유해 무관한 좌석 조회까지 막힌다.
 * 홀드 경로에서 매번 {@code SET} 을 보내면 요청당 왕복이 하나 늘므로
 * 커넥션 초기화 SQL 로 한 번만 설정한다.
 */
public class JdbcSeatHoldRepository {

    /**
     * <b>검사와 갱신이 한 문장이다.</b> 이 사이에는 아무것도 끼어들 수 없다.
     *
     * <p>{@code NOW(3)} 을 쓰는 이유는 CLAUDE.md 규칙 7 이다. 애플리케이션 시각을 쓰면
     * 인스턴스 간 클럭 드리프트로 만료 판정이 갈린다. DB 서버 시각이 유일한 기준이어야 한다.
     *
     * <p>단건은 기본 키 조회이므로 {@code FORCE INDEX (PRIMARY)} 를 붙이지 않는다.
     * 규칙 3 은 {@code IN} 절 UPDATE 를 대상으로 하며 다좌석 선점(후속 Task)에서 적용된다.
     * 다만 인덱스를 추가하다 실행 계획이 바뀌는 것을 감지하려고 EXPLAIN 어서션 테스트를 둔다.
     */
    private static final String HOLD_SQL = """
            UPDATE seat_inventory
               SET status     = 'HELD',
                   hold_id    = ?,
                   held_by    = ?,
                   held_at    = NOW(3),
                   expires_at = DATE_ADD(NOW(3), INTERVAL ? SECOND),
                   version    = version + 1
             WHERE id     = ?
               AND status = 'AVAILABLE'
            """;

    private final JdbcTemplate jdbc;
    private final long holdSeconds;

    /**
     * @param holdDuration 선점 유지 시간. REQUIREMENTS.md P-4 기준 5분.
     */
    public JdbcSeatHoldRepository(DataSource dataSource, Duration holdDuration) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
        this.holdSeconds = Objects.requireNonNull(holdDuration, "holdDuration").toSeconds();
        if (holdSeconds <= 0) {
            throw new IllegalArgumentException("holdDuration 은 양수여야 한다: " + holdDuration);
        }
    }

    /**
     * 판매 가능한 좌석을 선점한다.
     *
     * @return {@link SeatHoldOutcome#HELD} 이면 이 요청이 좌석을 얻었다.
     *         {@link SeatHoldOutcome#ALREADY_HELD} 는 경합 실패이며 정상 동작이다.
     */
    public SeatHoldOutcome hold(SeatId seatId, HoldId holdId, UserId userId) {
        Objects.requireNonNull(seatId, "seatId");
        Objects.requireNonNull(holdId, "holdId");
        Objects.requireNonNull(userId, "userId");

        int affectedRows = jdbc.update(HOLD_SQL,
                holdId.asString(),
                userId.value(),
                holdSeconds,
                seatId.value());

        // affected_rows 가 유일한 판정 근거다. 상태를 다시 읽지 않는다.
        return affectedRows == 1 ? SeatHoldOutcome.HELD : SeatHoldOutcome.ALREADY_HELD;
    }
}
