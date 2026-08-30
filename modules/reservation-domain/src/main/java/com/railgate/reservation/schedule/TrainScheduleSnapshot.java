package com.railgate.reservation.schedule;

import com.railgate.reservation.saleevent.SaleEventId;

/**
 * 저장된 운행편 한 행의 값. 운영 스키마 초안의 {@code train_schedule} 과 대응한다
 * (TASK-002G-D §5).
 *
 * <p><b>두 필드 모두 필수다.</b> {@code sale_event_id} 가 {@code NOT NULL} 인 것과 같은 계약이며,
 * 이 Task 에서는 그 계약을 도메인 쪽에서도 강제한다. 소속 없는 운행편은 존재할 수 없다.
 *
 * <p>열차 번호·출발 시각·구간 같은 운행 정보는 <b>의도적으로 없다.</b>
 * 이 Task 가 확정하는 것은 quota 범위를 결정하는 <b>소속 관계</b>뿐이고,
 * 쓰이지 않는 필드를 미리 만들면 아직 정하지 않은 것을 정한 것처럼 보이게 된다.
 */
public record TrainScheduleSnapshot(TrainScheduleId id, SaleEventId saleEventId) {

    public TrainScheduleSnapshot {
        require(id != null, "운행편 식별자가 없다");
        require(saleEventId != null, "판매 회차 소속이 없다");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new TrainScheduleRestoreException("운행편을 복원할 수 없다: " + message);
        }
    }
}
