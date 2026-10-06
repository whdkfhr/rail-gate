package com.railgate.reservation.expiry;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.saleevent.SaleEventId;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * 만료 후보 조회 시점의 좌석 스냅숏 — quota 귀속 정보를 함께 담는다.
 *
 * <p>{@link ExpiryCandidate}(좌석·홀드)에 <b>누구의 어느 회차인지</b>가 더해진 것이다.
 * 회수 후 quota 를 줄이려면 좌석이 사라지기 전에 소유자를 알아야 하고, 회수 UPDATE 가
 * {@code hold_id}·{@code held_by} 를 지우므로 <b>조회 시점에 확보</b>한다 (TASK-002G-F 실험 A).
 *
 * <h2>★ 손상 후보를 숨기지 않는다</h2>
 *
 * <p>{@code hold_id} 나 {@code held_by} 가 없는 활성 좌석은 정합성 위반이다.
 * 후보에서 조용히 빼면 그 좌석은 <b>영원히 회수되지 않으면서 아무도 그 사실을 모른다.</b>
 * 사용자 0 으로 바꾸면 존재하지 않는 카운터를 건드린다. 둘 다 하지 않고
 * <b>후보로 읽어 손상으로 분리 보고</b>한다 — 그래서 두 필드가 {@link Optional} 이다.
 *
 * <p>{@code expiresAt} 을 담는 이유는 <b>키셋 커서를 만들기 위해서</b>다 ({@link ExpiryCursor}).
 * 정렬 키를 결과에 실어야 다음 페이지의 시작 위치를 정할 수 있다.
 *
 * <p><b>이 값은 "그때 그랬다" 는 기록이다.</b> 조회는 잠금을 잡지 않으므로 그 직후 확정·해제·
 * 연장·다른 스위퍼의 회수가 모두 가능하다. 실제 회수는 조건부 UPDATE 가 다시 판단한다.
 */
public record ExpiredSeat(
        com.railgate.reservation.seat.SeatId seatId,
        Optional<HoldId> holdId,
        Optional<UserId> heldBy,
        SaleEventId saleEventId,
        LocalDateTime expiresAt) {

    public ExpiredSeat {
        Objects.requireNonNull(seatId, "seatId");
        Objects.requireNonNull(holdId, "holdId");
        Objects.requireNonNull(heldBy, "heldBy");
        Objects.requireNonNull(saleEventId, "saleEventId");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    /** 이 후보를 마지막으로 본 위치로 하는 커서. */
    public ExpiryCursor cursor() {
        return new ExpiryCursor(expiresAt, seatId.value());
    }

    /** 회수·정산이 가능한 정상 후보인가. */
    public boolean isSettleable() {
        return holdId.isPresent() && heldBy.isPresent();
    }

    /** 손상 사유. 정상 후보면 빈 값. */
    public Optional<String> corruptionReason() {
        if (holdId.isEmpty()) {
            return Optional.of("활성 좌석인데 hold_id 가 NULL 이다 — 소유권을 검증할 수 없어 회수하지 않는다");
        }
        if (heldBy.isEmpty()) {
            return Optional.of("활성 좌석인데 held_by 가 NULL 이다 — 소유자를 몰라 quota 를 정산할 수 없다");
        }
        return Optional.empty();
    }

    /** 정상 후보를 회수 UPDATE 가 받는 형태로. 손상 후보에서는 부르지 않는다. */
    public ExpiryCandidate toCandidate() {
        return new ExpiryCandidate(seatId, holdId.orElseThrow(
                () -> new IllegalStateException("손상 후보는 회수 후보가 될 수 없다: " + seatId)));
    }
}
