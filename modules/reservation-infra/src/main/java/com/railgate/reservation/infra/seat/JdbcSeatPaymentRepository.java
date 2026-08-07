package com.railgate.reservation.infra.seat;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.ReservationId;
import com.railgate.reservation.seat.SeatId;
import java.time.Duration;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 결제 시작({@code HELD → PAYING})과 좌석 확정({@code PAYING → SOLD}).
 *
 * <p><b>{@link JdbcSeatHoldRepository} 와 분리한 이유.</b>
 * 선점은 "빈 좌석을 잡는" 경쟁이고, 여기 둘은 "이미 잡은 좌석을 진행시키는" 전이다.
 * 전자는 {@code hold_id} 를 <i>만들고</i> 후자는 {@code hold_id} 를 <i>확인한다</i>.
 * 입력도 다르다 — 확정에는 {@link ReservationId} 가, 결제 시작에는 결제 유효 시간이 필요하다.
 * 한 클래스에 넣으면 생성자 인자와 책임이 뒤섞인다.
 *
 * <h2>hold_id 조건이 왜 모든 전이에 필요한가</h2>
 *
 * <p>좌석 id 만으로 전이하면 <b>남의 홀드를 진행시킬 수 있다</b> (CLAUDE.md 규칙 4).
 * 사용자 A 의 홀드가 만료되고 B 가 같은 좌석을 잡은 직후, A 의 지연된 확정 요청이 도착하면
 * {@code hold_id} 조건이 없는 한 B 의 좌석이 A 의 예약으로 확정된다.
 * 홀드 식별자는 "지금 이 좌석을 누가 잡고 있는가" 에 대한 유일한 답이며,
 * 모든 전이는 그 답을 확인한 뒤에만 일어난다.
 *
 * <h2>성공 판정</h2>
 *
 * <p>{@code affected_rows} 가 유일한 기준이다. 전이 후 상태를 다시 조회해 확인하지 않는다.
 * 재조회는 또 하나의 창을 만들 뿐이며, 그 사이 좌석이 다시 바뀌어도 알 수 없다.
 * 갱신을 수행한 그 문장이 몇 행을 바꿨는지가 그 시점의 유일하게 신뢰할 수 있는 사실이다.
 *
 * <h2>이 클래스가 보장하지 않는 것</h2>
 *
 * <ul>
 *   <li><b>I-15</b> 결제 승인 검증 — {@code payment.status='AUTHORIZED'} 조건이 없다.
 *       확정은 "좌석 재고가 이 예약에 귀속됐다" 만 뜻하며, 돈이 승인됐다는 뜻이 아니다.</li>
 *   <li><b>I-11</b> 만료 스위퍼와의 경쟁 — {@link JdbcSeatExpiryRepository} 가 상대편이며
 *       TASK-002D 에서 검증했다. 주의할 점은 아래 {@code confirm} 의 조건에
 *       {@code expires_at} 이 <b>없다</b>는 것이다. 이미 만료된 PAYING 좌석도 확정되므로
 *       스위퍼와의 경쟁은 예외가 아니라 정상 경로다.</li>
 *   <li><b>I-12</b> 1인당 좌석 상한. {@code user_hold_quota} 테이블 자체가 없다.
 *       <b>이 클래스의 확정은 quota 감소가 연동돼야 할 지점 중 하나다.</b>
 *       상태 전이는 구현됐지만 quota 연동은 없다.
 *       (I-9 다좌석 원자성은 {@link JdbcMultiSeatHoldRepository} 가 담당한다)</li>
 *   <li>멱등성. 재확정이 0 건인 것은 CAS 결과일 뿐 최초 응답을 돌려주는 것과 다르다.</li>
 * </ul>
 */
public class JdbcSeatPaymentRepository {

    /**
     * 결제 시작. 검사와 갱신이 한 문장이다.
     *
     * <p>{@code expires_at > NOW(3)} 조건이 <b>이미 만료된 홀드로 결제를 시작하는 것을 막는
     * 유일한 방어선</b>이다. 도메인은 현재 시각을 알지 못하므로(CLAUDE.md 규칙 7) 이 검사를
     * 대신할 수 없다. 이 조건이 빠지면 만료된 좌석이 결제로 넘어간다.
     *
     * <p>{@code GREATEST} 는 만료 시각이 앞당겨지는 것을 구조적으로 막는다 (P-4).
     * 결제 창이 홀드 잔여 시간보다 짧은 순간에 단순 대입을 하면, 남은 시간을 오히려 깎아
     * 결제 도중 스위퍼가 좌석을 회수하게 된다.
     *
     * <p>{@code hold_id}, {@code held_by}, {@code held_at} 은 건드리지 않는다.
     * {@code PAYING} 도 여전히 임시 점유이며, 확정과 해제가 소유권을 확인해야 하기 때문이다.
     */
    private static final String START_PAYMENT_SQL = """
            UPDATE seat_inventory
               SET status     = 'PAYING',
                   expires_at = GREATEST(expires_at, DATE_ADD(NOW(3), INTERVAL ? SECOND)),
                   version    = version + 1
             WHERE id         = ?
               AND hold_id    = ?
               AND status     = 'HELD'
               AND expires_at > NOW(3)
            """;

    /**
     * 좌석 확정. <b>I-8 의 마지막 조각이다.</b>
     *
     * <p>{@code WHERE status = 'PAYING'} 때문에 이미 {@code SOLD} 인 행은 매칭되지 않는다.
     * 따라서 확정된 좌석의 {@code reservation_id} 는 이후 어떤 확정 요청으로도 덮어써지지 않는다.
     *
     * <p>홀드 관련 필드를 모두 비우는 이유는 확정이 홀드를 소멸시키기 때문이다.
     * 확정된 좌석에 활성 홀드란 것은 존재하지 않는다.
     *
     * <p><b>따라서 이 UPDATE 는 직전 홀드 정보를 되돌릴 수 없게 지운다.</b>
     * CLAUDE.md 규칙 32 는 모든 좌석 상태 전이를 {@code seat_state_log} 에
     * {@code actor}, {@code reason}, {@code traceId} 와 함께 기록하도록 요구하지만,
     * <b>그 테이블과 기록 로직은 아직 없다</b>. 애플리케이션 서비스가 없어 세 값을 전달할 수
     * 없는 상태이며, 임의의 값을 채워 넣는 것은 감사 로그의 목적을 무너뜨린다.
     * 미해결 항목으로 {@code docs/experiments/TASK-002B-seat-confirm-race.md} 에 기록했다.
     *
     * <p>{@code WHERE} 가 기존 {@code hold_id} 를 읽고 {@code SET} 이 그것을 비운다.
     * 소유권 검사가 자연히 상태 변경보다 먼저 끝난다.
     */
    private static final String CONFIRM_SQL = """
            UPDATE seat_inventory
               SET status         = 'SOLD',
                   reservation_id = ?,
                   hold_id        = NULL,
                   held_by        = NULL,
                   held_at        = NULL,
                   expires_at     = NULL,
                   version        = version + 1
             WHERE id      = ?
               AND hold_id = ?
               AND status  = 'PAYING'
            """;

    private final JdbcTemplate jdbc;
    private final long paymentSeconds;

    /**
     * @param paymentDuration 결제 유효 시간. REQUIREMENTS.md P-4 기준 5분.
     */
    public JdbcSeatPaymentRepository(DataSource dataSource, Duration paymentDuration) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
        this.paymentSeconds = Objects.requireNonNull(paymentDuration, "paymentDuration").toSeconds();
        if (paymentSeconds <= 0) {
            throw new IllegalArgumentException("paymentDuration 은 양수여야 한다: " + paymentDuration);
        }
    }

    /**
     * 선점한 좌석의 결제를 시작한다.
     *
     * @return {@link SeatPaymentOutcome#STARTED} 이면 이 요청이 결제 단계를 확보했다.
     *         {@link SeatPaymentOutcome#NOT_STARTED} 는 정상 동작이다.
     */
    public SeatPaymentOutcome startPayment(SeatId seatId, HoldId holdId) {
        Objects.requireNonNull(seatId, "seatId");
        Objects.requireNonNull(holdId, "holdId");

        int affectedRows = jdbc.update(START_PAYMENT_SQL,
                paymentSeconds,
                seatId.value(),
                holdId.asString());

        return affectedRows == 1 ? SeatPaymentOutcome.STARTED : SeatPaymentOutcome.NOT_STARTED;
    }

    /**
     * 결제 중인 좌석을 예약에 확정한다.
     *
     * <p><b>결제 승인 여부를 검증하지 않는다.</b> 호출자가 승인을 확인한 뒤 부르는 것을 전제하며,
     * 그 전제를 DB 조건으로 강제하는 것은 I-15 의 몫이다 (후속 Task).
     *
     * @return {@link SeatConfirmationOutcome#CONFIRMED} 이면 좌석이 이 예약에 귀속됐다.
     */
    public SeatConfirmationOutcome confirm(
            SeatId seatId, HoldId holdId, ReservationId reservationId) {
        Objects.requireNonNull(seatId, "seatId");
        Objects.requireNonNull(holdId, "holdId");
        Objects.requireNonNull(reservationId, "reservationId");

        int affectedRows = jdbc.update(CONFIRM_SQL,
                reservationId.value(),
                seatId.value(),
                holdId.asString());

        return affectedRows == 1
                ? SeatConfirmationOutcome.CONFIRMED
                : SeatConfirmationOutcome.NOT_CONFIRMED;
    }
}
