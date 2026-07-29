package com.railgate.reservation.seat;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.ReservationId;
import com.railgate.reservation.UserId;
import java.time.Instant;

/**
 * 저장된 좌석 한 행의 값. {@code seat_inventory} 의 컬럼과 1:1 로 대응한다.
 *
 * <p><b>왜 {@code Seat.restore(id, seatNo, status, holdId, ...)} 형태의 다인자 팩터리가 아니라
 * 스냅샷 타입인가.</b>
 * <ul>
 *   <li>복원에 필요한 값이 7개고 그중 4개가 nullable 이다. 위치 인자로 넘기면
 *       호출부에서 순서를 틀려도 컴파일러가 잡지 못하는 조합이 생긴다.</li>
 *   <li>"좌석이 가질 수 있는 필드 조합" 이라는 규칙에 이름을 붙일 곳이 생긴다.
 *       DEVELOPMENT_GUIDE 의 값 객체 원칙대로 <b>잘못된 스냅샷은 아예 존재할 수 없다.</b></li>
 *   <li>검증이 {@link Seat} 밖에 있으므로, 엔티티는 전이 규칙에만 집중한다.</li>
 * </ul>
 *
 * <p>허용되는 조합은 도메인 전이가 실제로 만들어내는 상태와 정확히 같다.
 * 즉 <b>도메인이 만들 수 없는 데이터는 복원할 수도 없다.</b>
 *
 * <pre>
 *             holdId   heldBy   expiresAt   reservationId
 * AVAILABLE   null     null     null        null
 * HELD        필수      필수      필수         null
 * PAYING      필수      필수      필수         null
 * SOLD        null     null     null        필수
 * </pre>
 *
 * <p>{@code SOLD} 에 홀드 정보와 만료 시각이 모두 없어야 하는 이유는 <b>상태 조합을 명확히
 * 유지하기 위해서다.</b> 확정은 홀드를 소멸시키므로 활성 홀드가 남아 있을 수 없고
 * (홀드 이력은 {@code seat_state_log} 담당), 만료될 일이 없는 좌석에 만료 시각이 있을 이유도 없다.
 *
 * <p>이 규칙은 I-11 의 방어 수단이 <b>아니다.</b> I-11 은 스위퍼의
 * {@code WHERE status IN ('HELD','PAYING')} 상태 술어가 지키며, 그 술어는 {@code expiresAt}
 * 값과 무관하게 SOLD 를 배제한다. 여기서의 검증은 그런 행이 애초에 저장되지 않았는지를
 * 조회 시점에 확인하는 것이다.
 */
public record SeatSnapshot(
        SeatId id,
        SeatNo seatNo,
        SeatStatus status,
        HoldId holdId,
        UserId heldBy,
        Instant expiresAt,
        ReservationId reservationId) {

    public SeatSnapshot {
        require(id != null, "좌석 식별자가 없다");
        require(seatNo != null, "좌석 번호가 없다");
        require(status != null, "좌석 상태가 없다");

        switch (status) {
            case AVAILABLE -> {
                requireAbsent(holdId, status, "holdId");
                requireAbsent(heldBy, status, "heldBy");
                requireAbsent(expiresAt, status, "expiresAt");
                requireAbsent(reservationId, status, "reservationId");
            }
            case HELD, PAYING -> {
                requirePresent(holdId, status, "holdId");
                requirePresent(heldBy, status, "heldBy");
                requirePresent(expiresAt, status, "expiresAt");
                requireAbsent(reservationId, status, "reservationId");
            }
            case SOLD -> {
                // 확정은 홀드를 소멸시킨다. 확정된 좌석에 활성 홀드는 존재하지 않는다.
                requireAbsent(holdId, status, "holdId");
                requireAbsent(heldBy, status, "heldBy");
                // 만료될 일이 없는 좌석에 만료 시각이 있을 이유가 없다.
                // (I-11 은 스위퍼의 status 술어가 지킨다. 이건 상태 조합 검증이다)
                requireAbsent(expiresAt, status, "expiresAt");
                // V-5(고아 상태): SOLD 인데 예약이 없는 행.
                requirePresent(reservationId, status, "reservationId");
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new SeatRestoreException("좌석을 복원할 수 없다: " + message);
        }
    }

    private static void requirePresent(Object value, SeatStatus status, String field) {
        require(value != null, "%s 상태에는 %s 가 있어야 한다".formatted(status, field));
    }

    private static void requireAbsent(Object value, SeatStatus status, String field) {
        require(value == null, "%s 상태에는 %s 가 없어야 한다".formatted(status, field));
    }
}
