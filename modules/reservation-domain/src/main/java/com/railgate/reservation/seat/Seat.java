package com.railgate.reservation.seat;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.ReservationId;
import com.railgate.reservation.UserId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 좌석 재고 애그리거트 루트.
 *
 * <p><b>일관성 경계는 좌석 하나다.</b> 이는 DB 의 {@code seat_inventory} 한 행과 정확히 대응하며,
 * 인프라 계층의 조건부 UPDATE 한 건이 이 객체의 전이 하나에 대응하도록 의도한 것이다.
 *
 * <p><b>이 클래스가 보장하는 것과 보장하지 않는 것을 구분해야 한다.</b>
 * <ul>
 *   <li>보장: I-14. 전이표에 없는 상태 변경은 일어나지 않는다.</li>
 *   <li>보장하지 않음: I-8. 단일 스레드 객체는 동시성을 막지 못한다.
 *       두 스레드가 같은 좌석 인스턴스에 {@code hold()} 를 호출하면 둘 다 성공할 수 있다.
 *       실제 방어선은 InnoDB 행 잠금 + 조건부 UPDATE 이며 TASK-2 에서 다룬다.</li>
 * </ul>
 *
 * <p>시간은 항상 파라미터로 받는다. 도메인이 {@code Instant.now()} 를 호출하면
 * 인스턴스 간 클럭 드리프트로 만료 판정이 갈린다 (CLAUDE.md 규칙 7).
 * 운영에서는 MySQL {@code NOW(3)} 이 유일한 시각 기준이다.
 */
public final class Seat {

    private final SeatId id;
    private final SeatNo seatNo;

    private SeatStatus status;
    private HoldId holdId;
    private UserId heldBy;
    private Instant expiresAt;
    private ReservationId reservationId;

    private Seat(SeatId id, SeatNo seatNo, SeatStatus status, HoldId holdId, UserId heldBy,
            Instant expiresAt, ReservationId reservationId) {
        this.id = Objects.requireNonNull(id, "seatId 는 null 일 수 없다");
        this.seatNo = Objects.requireNonNull(seatNo, "seatNo 는 null 일 수 없다");
        this.status = Objects.requireNonNull(status, "status 는 null 일 수 없다");
        this.holdId = holdId;
        this.heldBy = heldBy;
        this.expiresAt = expiresAt;
        this.reservationId = reservationId;
    }

    /** 판매 가능한 새 좌석을 만든다. */
    public static Seat available(SeatId id, SeatNo seatNo) {
        return new Seat(id, seatNo, SeatStatus.AVAILABLE, null, null, null, null);
    }

    /**
     * 저장된 행을 도메인 객체로 복원한다.
     *
     * <p><b>상태 전이를 재실행하지 않는다.</b> SOLD 좌석을 만들려고
     * {@code hold → startPayment → confirm} 을 다시 돌리는 방식은 쓰지 않는다.
     * 그렇게 하면 존재하지 않는 홀드를 지어내야 하고, 전이 규칙이 바뀔 때마다 복원이 깨지며,
     * 과거에 유효했던 데이터를 현재 규칙으로 다시 검사하게 되어 읽기가 실패한다.
     *
     * <p>대신 {@link SeatSnapshot} 이 필드 조합의 정합성을 생성 시점에 보장하고,
     * 여기서는 값을 그대로 옮긴다. 임의 setter 를 열지 않으면서도 복원이 가능한 이유다.
     *
     * @throws SeatRestoreException 스냅샷이 없거나 도메인이 만들 수 없는 조합인 경우
     */
    public static Seat restore(SeatSnapshot snapshot) {
        if (snapshot == null) {
            throw new SeatRestoreException("좌석을 복원할 수 없다: 스냅샷이 없다");
        }
        return new Seat(
                snapshot.id(),
                snapshot.seatNo(),
                snapshot.status(),
                snapshot.holdId(),
                snapshot.heldBy(),
                snapshot.expiresAt(),
                snapshot.reservationId());
    }

    // ------------------------------------------------------------------
    // 상태 전이
    // ------------------------------------------------------------------

    /**
     * 좌석을 선점한다. AVAILABLE 에서만 가능하다.
     *
     * @throws SeatAlreadyHeldException 이미 다른 홀드가 잡고 있는 경우 (경합 실패, 409)
     */
    public void hold(HoldId holdId, UserId userId, Instant expiresAt) {
        Objects.requireNonNull(holdId, "holdId 는 null 일 수 없다");
        Objects.requireNonNull(userId, "userId 는 null 일 수 없다");
        Objects.requireNonNull(expiresAt, "expiresAt 는 null 일 수 없다");

        if (!status.isHoldable()) {
            throw new SeatAlreadyHeldException(
                    "좌석 %d 는 이미 %s 상태다".formatted(id.value(), status));
        }

        this.status = SeatStatus.HELD;
        this.holdId = holdId;
        this.heldBy = userId;
        this.expiresAt = expiresAt;
    }

    /**
     * 결제를 시작한다. 만료 데드라인이 결제 데드라인까지 연장된다.
     *
     * <p>데드라인을 연장하는 이유: 결제 중 PG 왕복 시간 동안 스위퍼가 좌석을 회수하면
     * 돈은 나가고 좌석은 없는 상황이 된다.
     *
     * <p><b>최종 만료 시각은 {@code max(기존 expiresAt, paymentDeadline)} 이다</b>
     * (REQUIREMENTS.md P-4). 더 이른 데드라인이 들어와도 <b>거부하지 않고 기존 값을 유지</b>한다.
     * 같은 값이어도 마찬가지다. 거부하지 않는 이유는, 결제 창(예: 5분)이 홀드 잔여 시간보다
     * 짧아지는 순간에 정상 요청이 실패하게 되기 때문이다.
     * 정책의 목적은 "만료를 앞당기지 않는 것" 이지 "요청을 거부하는 것" 이 아니다.
     *
     * <p><b>이 메서드는 "이미 만료된 홀드로 결제를 시작하는 것" 을 막지 못한다.</b>
     * 도메인은 현재 시각을 알지 못한다(CLAUDE.md 규칙 7: 만료 판정은 MySQL {@code NOW(3)}).
     * 최종 방어는 TASK-2 의 조건부 UPDATE 다.
     * <pre>
     * UPDATE seat_inventory
     *    SET status='PAYING', expires_at=GREATEST(expires_at, NOW(3) + INTERVAL 5 MINUTE)
     *  WHERE id=? AND hold_id=? AND status='HELD' AND expires_at &gt; NOW(3);
     * -- affected_rows = 0 → 이미 만료됐거나 남이 가져갔다
     * </pre>
     *
     * @throws SeatStateTransitionException HELD 가 아닌 경우
     * @throws HoldOwnershipException       다른 홀드가 잡고 있는 좌석인 경우
     */
    public void startPayment(HoldId holdId, Instant paymentDeadline) {
        Objects.requireNonNull(paymentDeadline, "paymentDeadline 는 null 일 수 없다");

        if (status != SeatStatus.HELD) {
            throw SeatStateTransitionException.of(id, status, "결제를 시작");
        }
        requireOwnedBy(holdId, "결제를 시작");

        this.status = SeatStatus.PAYING;
        this.expiresAt = laterOf(expiresAt, paymentDeadline);
    }

    /**
     * 결제 승인이 확인된 뒤 좌석을 확정한다. PAYING 에서만 가능하다.
     *
     * <p><b>확정은 홀드를 소멸시킨다.</b> 홀드는 "만료될 수 있는 임시 점유" 이고,
     * 확정된 좌석에는 그런 것이 존재하지 않는다. 그래서 {@code holdId}, {@code heldBy},
     * {@code expiresAt} 을 모두 지운다. 확정 뒤 {@link #isHeldBy(HoldId)} 는 항상 false 다.
     *
     * <p>홀드 이력을 활성 상태 필드에 남기지 않는 이유는, 남겨두면
     * "지금 이 좌석을 누가 잡고 있는가" 라는 질문에 이미 끝난 홀드가 답하게 되기 때문이다.
     * 감사 이력은 {@code seat_state_log} 가 담당한다 (CLAUDE.md 규칙 32).
     *
     * <p><b>{@code expiresAt} 을 비우는 것은 I-11 의 방어 수단이 아니다.</b>
     * I-11 을 지키는 것은 스위퍼의 {@code WHERE status IN ('HELD','PAYING')} 상태 술어이고,
     * 이는 {@code expiresAt} 값과 무관하게 SOLD 를 배제한다.
     * 여기서 비우는 이유는 상태 조합을 명확하게 유지하기 위해서다.
     * SOLD 에 의미 없는 필드가 남아 있지 않아야 {@link SeatSnapshot} 이 손상된 행을 잡아낼 수 있다.
     *
     * <p>소유권 검사는 <b>상태를 바꾸기 전에 기존 {@code holdId} 로</b> 수행한다.
     * 인프라 계층도 같은 순서다. WHERE 절이 기존 값을 읽고 SET 절이 지운다.
     * <pre>
     * UPDATE seat_inventory
     *    SET status='SOLD', reservation_id=?, hold_id=NULL, held_by=NULL, expires_at=NULL
     *  WHERE id=? AND hold_id=? AND status='PAYING';
     * </pre>
     */
    public void confirm(HoldId holdId, ReservationId reservationId) {
        Objects.requireNonNull(reservationId, "reservationId 는 null 일 수 없다");

        if (status != SeatStatus.PAYING) {
            throw SeatStateTransitionException.of(id, status, "확정");
        }
        // 아래에서 holdId 를 지우므로, 검사는 반드시 지우기 전에 끝나야 한다.
        requireOwnedBy(holdId, "확정");

        this.status = SeatStatus.SOLD;
        this.reservationId = reservationId;
        this.holdId = null;
        this.heldBy = null;
        this.expiresAt = null;
    }

    /** 사용자가 자발적으로 선점을 해제한다. HELD 와 PAYING 에서 가능하다. */
    public void release(HoldId holdId) {
        if (!status.isReleasable()) {
            throw SeatStateTransitionException.of(id, status, "해제");
        }
        requireOwnedBy(holdId, "해제");

        resetToAvailable();
    }

    /**
     * 만료된 선점을 회수한다. 만료 스위퍼가 호출한다.
     *
     * <p>홀드 소유권을 요구하지 않는다. 스위퍼는 특정 홀드의 대리인이 아니기 때문이다.
     * 대신 SOLD 를 상태 검사로 막는다. 이것이 I-11 의 도메인 표현이다.
     */
    public void expire(Instant now) {
        Objects.requireNonNull(now, "now 는 null 일 수 없다");

        if (!status.isReleasable()) {
            throw SeatStateTransitionException.of(id, status, "만료");
        }
        if (!isExpiredAt(now)) {
            throw new SeatStateTransitionException(
                    "좌석 %d 는 아직 만료되지 않았다 (만료 예정 %s, 현재 %s)"
                            .formatted(id.value(), expiresAt, now));
        }

        resetToAvailable();
    }

    // ------------------------------------------------------------------
    // 질의
    // ------------------------------------------------------------------

    /**
     * 주어진 시각 기준으로 만료되었는가.
     *
     * <p>경계 규칙은 DB 의 {@code expires_at <= NOW(3)} 과 동일하게 맞춘다.
     * 즉 만료 시각과 정확히 같은 순간은 만료된 것으로 본다.
     */
    public boolean isExpiredAt(Instant now) {
        Objects.requireNonNull(now, "now 는 null 일 수 없다");
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    public boolean isHeldBy(HoldId holdId) {
        return this.holdId != null && this.holdId.equals(holdId);
    }

    public SeatId id() {
        return id;
    }

    public SeatNo seatNo() {
        return seatNo;
    }

    public SeatStatus status() {
        return status;
    }

    public Optional<HoldId> holdId() {
        return Optional.ofNullable(holdId);
    }

    public Optional<UserId> heldBy() {
        return Optional.ofNullable(heldBy);
    }

    public Optional<Instant> expiresAt() {
        return Optional.ofNullable(expiresAt);
    }

    public Optional<ReservationId> reservationId() {
        return Optional.ofNullable(reservationId);
    }

    // ------------------------------------------------------------------

    /**
     * 두 시각 중 뒤의 것을 고른다. 만료 시각이 앞당겨지는 일을 구조적으로 없앤다.
     * MySQL 의 {@code GREATEST(expires_at, ...)} 와 같은 의미다.
     */
    private static Instant laterOf(Instant current, Instant candidate) {
        if (current == null) {
            return candidate;
        }
        return candidate.isAfter(current) ? candidate : current;
    }

    private void requireOwnedBy(HoldId holdId, String action) {
        Objects.requireNonNull(holdId, "holdId 는 null 일 수 없다");
        if (!isHeldBy(holdId)) {
            throw new HoldOwnershipException(
                    "좌석 %d 를 %s 할 권한이 없다. 다른 홀드가 잡고 있다".formatted(id.value(), action));
        }
    }

    private void resetToAvailable() {
        this.status = SeatStatus.AVAILABLE;
        this.holdId = null;
        this.heldBy = null;
        this.expiresAt = null;
    }

    @Override
    public String toString() {
        return "Seat[id=%d, no=%s, status=%s]".formatted(id.value(), seatNo.value(), status);
    }
}
