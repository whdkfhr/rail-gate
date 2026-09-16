package com.railgate.reservation.quota;

import com.railgate.reservation.UserId;
import com.railgate.reservation.saleevent.SaleEventId;
import java.util.Objects;

/**
 * 활성 좌석이 <b>누구의, 어느 회차의</b> quota 에 귀속되는지.
 *
 * <p>확정 경로가 필요로 한다. {@code PAYING → SOLD} UPDATE 는 {@code hold_id}·{@code held_by} 를
 * 지우므로, <b>UPDATE 뒤에는 어느 카운터를 줄여야 하는지 알 수 없다.</b> 그래서 UPDATE 전에
 * 이 값을 확보한다. 클라이언트가 보낸 값이 아니라 좌석 행에서 서버가 읽은 값이다.
 *
 * <p>조회 시점의 <b>스냅숏</b>이다. 최종 확정 여부는 조건부 UPDATE 가 다시 판단하며,
 * 스냅숏과 최종 변경 대상이 같은 행·같은 소유자라는 것은 {@code hold_id} 가 홀드마다
 * 유일하고 재사용되지 않으며, {@code hold_id} 와 {@code held_by} 가 항상 함께 쓰이고
 * 함께 지워진다는 I-8 전이 규칙에 의존한다.
 */
public record HoldAttribution(UserId userId, SaleEventId saleEventId) {

    public HoldAttribution {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(saleEventId, "saleEventId");
    }
}
