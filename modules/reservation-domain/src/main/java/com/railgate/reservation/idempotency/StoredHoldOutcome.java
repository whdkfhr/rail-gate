package com.railgate.reservation.idempotency;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.hold.SeatCount;
import com.railgate.reservation.saleevent.SaleEventId;
import java.util.Objects;

/**
 * 선점 요청의 <b>저장된 최초 결과</b>. 같은 키·같은 요청의 재요청은 이 값을 그대로 재생한다 (규칙 17).
 *
 * <p><b>재생은 현재 상태가 아니다.</b> 재생한 {@code holdId} 는 이미 해제·만료됐을 수 있다.
 */
public sealed interface StoredHoldOutcome {

    /** 선점 성공. 본 작업과 같은 트랜잭션에서 기록한다. */
    record Succeeded(HoldId holdId, SaleEventId saleEventId, int seatCount) implements StoredHoldOutcome {

        public Succeeded {
            Objects.requireNonNull(holdId, "holdId");
            Objects.requireNonNull(saleEventId, "saleEventId");
            new SeatCount(seatCount); // 범위 검증
        }
    }

    /** 저장할 업무 실패. 본 작업이 롤백된 뒤 별도 트랜잭션에서 기록한다. */
    record Rejected(HoldRejectionCode code) implements StoredHoldOutcome {

        public Rejected {
            Objects.requireNonNull(code, "code");
        }
    }
}
