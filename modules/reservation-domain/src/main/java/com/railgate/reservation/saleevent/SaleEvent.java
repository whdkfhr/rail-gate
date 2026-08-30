package com.railgate.reservation.saleevent;

import java.time.Instant;
import java.util.Objects;

/**
 * 판매 회차 애그리거트 루트.
 *
 * <p>관리자가 <b>하나의 오픈 시각과 입장 정책으로 운영하는 예매 판매 회차</b>다
 * (TASK-002G-D §4). 예: {@code 2026년 추석 승차권 1차 판매}.
 * 하나의 회차에는 여러 열차 운행편({@code TrainSchedule})이 속한다.
 *
 * <p><b>이 객체가 왜 중요한가.</b> I-12(1인당 4석 상한)의 범위가 이 식별자 단위다.
 * 운행편을 범위로 쓰면 운행편만 바꿔서 상한을 우회할 수 있다는 것을
 * TASK-002G-D §2 가 6석·8석으로 재현했다. 즉 <b>잘못 만들어진 회차는 quota 의 의미를 흔든다.</b>
 *
 * <p><b>보장하는 것과 보장하지 않는 것.</b>
 * <ul>
 *   <li>보장: 전이표에 없는 상태 변경은 일어나지 않는다. 거부된 전이는 상태를 바꾸지 않는다.</li>
 *   <li>보장하지 않음: 동시성. 두 스레드가 같은 인스턴스에 {@code open()} 을 호출하면
 *       둘 다 성공할 수 있다. 운영 방어선은 조건부 UPDATE 이며 이 Task 범위가 아니다.</li>
 * </ul>
 *
 * <p>시간은 항상 파라미터로 받는다. 도메인이 {@code Instant.now()} 를 호출하면 인스턴스 간
 * 클럭 드리프트로 판정이 갈린다 (CLAUDE.md 규칙 7). {@code Seat.expire(Instant now)} 와 같은 원칙이다.
 */
public final class SaleEvent {

    private final SaleEventId id;
    private final String name;
    private final Instant opensAt;
    private final Instant closesAt;

    private SaleEventStatus status;

    private SaleEvent(SaleEventId id, String name, Instant opensAt, Instant closesAt,
            SaleEventStatus status) {
        this.id = id;
        this.name = name;
        this.opensAt = opensAt;
        this.closesAt = closesAt;
        this.status = status;
    }

    /**
     * 새 판매 회차를 등록한다. 항상 {@link SaleEventStatus#SCHEDULED} 로 시작한다.
     *
     * <p>등록 시각을 받지 않는 이유: 등록은 시각에 대한 판정이 아니다. 오픈 시각이 과거든
     * 미래든 등록 자체는 유효하며, 실제로 열 수 있는지는 {@link #open(Instant)} 이 판정한다.
     *
     * @param closesAt 마감 예정 시각. 무기한 판매면 {@code null}
     * @throws NullPointerException     식별자나 오픈 시각이 없는 경우
     * @throws IllegalArgumentException 이름이 비었거나 판매 기간이 뒤집힌 경우
     */
    public static SaleEvent schedule(SaleEventId id, String name, Instant opensAt, Instant closesAt) {
        Objects.requireNonNull(id, "saleEventId 는 null 일 수 없다");
        Objects.requireNonNull(opensAt, "opensAt 는 null 일 수 없다");
        if (!SaleEventRules.hasName(name)) {
            throw new IllegalArgumentException("판매 회차 이름은 비어 있을 수 없다: " + name);
        }
        if (!SaleEventRules.isValidSalePeriod(opensAt, closesAt)) {
            throw new IllegalArgumentException(
                    "마감 시각(%s)은 오픈 시각(%s)보다 뒤여야 한다".formatted(closesAt, opensAt));
        }
        return new SaleEvent(id, name, opensAt, closesAt, SaleEventStatus.SCHEDULED);
    }

    /**
     * 저장된 행을 도메인 객체로 복원한다.
     *
     * <p><b>상태 전이를 재실행하지 않는다.</b> {@code CLOSED} 회차를 만들려고
     * {@code schedule → open → close} 를 다시 돌리는 방식은 쓰지 않는다.
     * {@link #open(Instant)} 이 오픈 시각을 검사하므로, 그렇게 하면 과거에 유효했던 데이터를
     * 현재 시각 기준으로 다시 판정하게 되어 <b>읽기가 실패</b>한다.
     * {@code Seat.restore(SeatSnapshot)} 과 같은 이유다.
     *
     * @throws SaleEventRestoreException 스냅샷이 없거나 도메인이 만들 수 없는 조합인 경우
     */
    public static SaleEvent restore(SaleEventSnapshot snapshot) {
        if (snapshot == null) {
            throw new SaleEventRestoreException("판매 회차를 복원할 수 없다: 스냅샷이 없다");
        }
        return new SaleEvent(
                snapshot.id(),
                snapshot.name(),
                snapshot.opensAt(),
                snapshot.closesAt(),
                snapshot.status());
    }

    // ------------------------------------------------------------------
    // 상태 전이
    // ------------------------------------------------------------------

    /**
     * 판매를 시작한다. {@link SaleEventStatus#SCHEDULED} 에서만 가능하다.
     *
     * <p><b>오픈 시각 전에는 열 수 없다.</b> 이것이 회차를 "오픈 시각으로 운영하는 단위" 로
     * 정의한 §4 계약의 도메인 표현이다. 경계는 {@code now >= opensAt} 으로,
     * 오픈 시각과 정확히 같은 순간은 열 수 있다. DB 의 {@code opens_at <= NOW(3)} 과 같은 경계다.
     *
     * <p>이 메서드는 <b>"두 번 열리는 것"</b> 을 막지 못한다. 도메인 객체는 동시성을 막지 않는다.
     * 운영 방어선은 {@code WHERE id=? AND status='SCHEDULED' AND opens_at <= NOW(3)} 조건부
     * UPDATE 이며, 이 Task 범위가 아니다.
     *
     * @throws SaleEventStateTransitionException SCHEDULED 가 아니거나 아직 오픈 시각이 아닌 경우
     */
    public void open(Instant now) {
        Objects.requireNonNull(now, "now 는 null 일 수 없다");

        if (!status.isOpenable()) {
            throw SaleEventStateTransitionException.of(id, status, "판매를 시작");
        }
        if (now.isBefore(opensAt)) {
            throw new SaleEventStateTransitionException(
                    "판매 회차 %d 는 아직 오픈 시각이 아니다 (오픈 예정 %s, 현재 %s)"
                            .formatted(id.value(), opensAt, now));
        }

        this.status = SaleEventStatus.OPEN;
    }

    /**
     * 판매를 마감한다. {@code SCHEDULED}(오픈 전 취소)와 {@code OPEN} 에서 가능하다.
     *
     * <p><b>시각을 받지 않는 이유.</b> {@code closesAt} 은 <b>예정</b> 시각일 뿐이고,
     * 매진이나 운영 중단으로 인한 조기 마감은 정상적인 운영 행위다.
     * {@code now >= closesAt} 을 요구하면 그 정상 경로가 막힌다.
     * 예정 시각이 되면 자동으로 닫는 것은 배치의 책임이지 이 객체의 책임이 아니다.
     *
     * <p>그래서 {@code closesAt} 은 이 도메인 안에서 <b>판매 기간의 정합성 검증에만</b> 쓰인다.
     * 마감 판정의 근거가 아니다.
     *
     * @throws SaleEventStateTransitionException 이미 CLOSED 인 경우
     */
    public void close() {
        if (!status.isClosable()) {
            throw SaleEventStateTransitionException.of(id, status, "판매를 마감");
        }

        this.status = SaleEventStatus.CLOSED;
    }

    // ------------------------------------------------------------------
    // 질의
    // ------------------------------------------------------------------

    /** 선점 요청을 받을 수 있는 상태인가. */
    public boolean isOpen() {
        return status == SaleEventStatus.OPEN;
    }

    public SaleEventId id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Instant opensAt() {
        return opensAt;
    }

    /**
     * 마감 예정 시각.
     *
     * @return 무기한 판매면 {@code null}
     */
    public Instant closesAt() {
        return closesAt;
    }

    public SaleEventStatus status() {
        return status;
    }

    @Override
    public String toString() {
        return "SaleEvent[id=%d, name=%s, status=%s]".formatted(id.value(), name, status);
    }
}
