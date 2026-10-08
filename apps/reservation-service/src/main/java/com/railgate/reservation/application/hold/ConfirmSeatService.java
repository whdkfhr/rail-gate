package com.railgate.reservation.application.hold;

import com.railgate.reservation.hold.SeatConfirmationOutcome;
import com.railgate.reservation.hold.SeatConfirmationPort;
import com.railgate.reservation.quota.HoldAttribution;
import com.railgate.reservation.quota.HoldAttributionException;
import com.railgate.reservation.quota.QuotaReleaseOutcome;
import com.railgate.reservation.quota.UserHoldQuotaPort;
import com.railgate.reservation.saleevent.SaleEventScopePort;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 단일 좌석 확정 유스케이스 ({@code PAYING → SOLD}). <b>이 클래스가 트랜잭션을 소유한다.</b>
 *
 * <h2>흐름</h2>
 *
 * <ol>
 *   <li><b>귀속 조회</b> — 좌석 행에서 점유자와 회차를 읽는다. <b>잠금 없는 스냅숏</b>이다.
 *       확정 UPDATE 가 {@code hold_id}·{@code held_by} 를 지우므로 <b>UPDATE 전</b>에 확보한다</li>
 *   <li><b>quota 행 잠금</b> — {@code SELECT ... FOR UPDATE}. 좌석보다 먼저다</li>
 *   <li><b>조건부 확정</b> — 기존 {@code hold_id}·{@code PAYING} 조건 그대로. 만료 시각 조건은 없다</li>
 *   <li><b>quota 감소</b> — 3 이 성공한 <b>1석만</b>. {@code NOT_CONFIRMED} 면 호출하지 않는다</li>
 * </ol>
 *
 * <h2>★ 잠금 순서 — quota → seat</h2>
 *
 * <p>선점·해제와 같다 (TASK-002G-B). 1 이 잠금 없는 조회여야 하는 이유가 이것이다.
 *
 * <h2>★ 스냅숏과 최종 변경 대상이 같다는 근거</h2>
 *
 * <p>1 은 {@code (id, hold_id, status='PAYING')} 으로 읽고, 3 은 <b>같은 조건</b>으로 갱신한다.
 * 3 이 1 건을 바꿨다면 그 행은 여전히 같은 {@code hold_id} 를 가진 행이다.
 * 그 행의 {@code held_by}·회차가 1 에서 읽은 값과 같다는 것은 다음에 의존한다.
 * <ul>
 *   <li>{@code hold_id} 는 홀드마다 유일하고 <b>재사용되지 않는다</b> ({@code HoldId.newId()})</li>
 *   <li>{@code hold_id} 와 {@code held_by} 는 항상 <b>함께 쓰이고 함께 지워진다</b> (I-8 전이 규칙).
 *       {@code hold_id} 만 남기고 {@code held_by} 를 바꾸는 경로가 없다</li>
 *   <li>좌석의 운행편 소속과 운행편의 회차 소속은 <b>변하지 않는다</b> (TASK-002G-E-A 계약)</li>
 * </ul>
 * 이 셋 중 하나가 깨지면 스냅숏은 다른 사람의 카운터를 가리킬 수 있다.
 * 그 사이 상태가 바뀌어 3 이 0 건이면 감소하지 않으므로 stale 스냅숏 자체는 무해하다.
 *
 * <h2>★ 정합성 오류는 SOLD 전환까지 롤백한다</h2>
 *
 * <p>확정됐는데 카운터가 없거나 감소가 거절되면 {@link QuotaInconsistencyException} 을 전파한다.
 * 그러면 {@code SOLD}·{@code reservation_id}·홀드 정보 삭제가 <b>모두</b> 되돌아간다 —
 * 좌석은 다시 {@code PAYING} 이며 홀드는 살아 있다. {@code ensureRow}·자동 보정·독립 커밋·
 * 예외 삼키기를 하지 않는다. 조용히 넘기면 drift 의 증거가 사라진다.
 *
 * <h2>★ 이 서비스가 보장하지 않는 것</h2>
 *
 * <ul>
 *   <li><b>I-15 결제 승인 검증</b> — 호출자가 승인을 확인했다는 <b>전제</b>로 동작한다.
 *       {@code payment} 테이블도 PG 연동도 없다. <b>I-15 를 완성한 것이 아니다.</b></li>
 *   <li><b>멱등성</b> — 이미 {@code SOLD} 인 좌석에 같은 요청을 다시 보내면
 *       {@code NOT_CONFIRMED} 가 돌아오고 quota 는 다시 줄지 않는다. 그러나 그것은 CAS 결과이지
 *       <b>"최초 응답을 재생한 멱등 성공" 이 아니다</b> (규칙 17).</li>
 *   <li><b>I-12 운영 활성화</b> — 만료 경로의 quota 감소는 {@code ExpireHoldsService}(Task 2H-D)로
 *       연결됐지만 스케줄러가 기본 비활성이고(Task 2H-E) backfill 도 수행 전이다.</li>
 *   <li>다좌석 예약 확정, REST API, 감사 로그(규칙 32), 메트릭(규칙 35).</li>
 * </ul>
 *
 * <p><b>반환값은 아직 최종 커밋이 아닐 수 있다.</b> {@code REQUIRED} 로 참여하므로 바깥 트랜잭션이
 * 있으면 그 안의 결과이며, 바깥이 롤백하면 확정도 감소도 사라진다.
 */
@Service
public class ConfirmSeatService {

    private final SaleEventScopePort saleEventScope;
    private final UserHoldQuotaPort quota;
    private final SeatConfirmationPort seats;

    public ConfirmSeatService(
            SaleEventScopePort saleEventScope, UserHoldQuotaPort quota, SeatConfirmationPort seats) {
        this.saleEventScope = Objects.requireNonNull(saleEventScope, "saleEventScope");
        this.quota = Objects.requireNonNull(quota, "quota");
        this.seats = Objects.requireNonNull(seats, "seats");
    }

    /**
     * 좌석을 확정하고 성공 시 quota 를 같은 트랜잭션에서 1 줄인다.
     *
     * @return {@link SeatConfirmationOutcome#NOT_CONFIRMED} 는 정상 동작이며 quota 를 건드리지 않는다.
     *         원인(PAYING 아님·다른 홀드·없는 좌석·이미 SOLD)은 구분되지 않는다
     * @throws HoldAttributionException    대상은 있는데 점유자가 없는 손상 데이터. 쓰기 전에 거절
     * @throws QuotaInconsistencyException 확정됐는데 카운터가 없거나 감소가 거절됨. 확정까지 롤백
     * @throws IllegalStateException       저장소가 트랜잭션에 참여하지 못한 경우 (배선 실수)
     */
    @Transactional
    public SeatConfirmationOutcome confirm(ConfirmSeatCommand command) {
        Objects.requireNonNull(command, "command");

        // 1. 잠금 없는 스냅숏. 손상 데이터(held_by 없음)는 여기서 거절된다 — 아무것도 쓰기 전이다.
        Optional<HoldAttribution> attribution =
                saleEventScope.resolvePayingSeat(command.seatId(), command.holdId());
        if (attribution.isEmpty()) {
            // 조건에 맞는 대상이 없다. 다른 소유자의 quota 를 추측하거나 만들지 않는다.
            return SeatConfirmationOutcome.NOT_CONFIRMED;
        }
        HoldAttribution owner = attribution.get();

        // 2. quota 를 좌석보다 먼저 잠근다. 행이 없어도 아직 판단하지 않는다.
        Optional<Integer> counter = quota.lockRow(owner.saleEventId(), owner.userId());

        // 3. 기존 조건부 UPDATE. 스냅숏 이후 바뀌었으면 0 건이다.
        SeatConfirmationOutcome outcome =
                seats.confirm(command.seatId(), command.holdId(), command.reservationId());
        if (outcome != SeatConfirmationOutcome.CONFIRMED) {
            return outcome;
        }

        // 4. 확정된 1석만 줄인다. 행을 만들거나 값을 보정하지 않는다.
        if (counter.isEmpty()) {
            throw new QuotaInconsistencyException(
                    "좌석 %d 를 사용자 %d 의 예약 %d 로 확정했는데 판매 회차 %d 의 quota 행이 없다. 롤백한다"
                            .formatted(command.seatId().value(), owner.userId().value(),
                                    command.reservationId().value(), owner.saleEventId().value()));
        }
        if (quota.release(owner.saleEventId(), owner.userId(), 1) != QuotaReleaseOutcome.RELEASED) {
            throw new QuotaInconsistencyException(
                    "좌석 %d 를 사용자 %d 의 예약 %d 로 확정했는데 quota 감소가 거절됐다 (현재 %d). 롤백한다"
                            .formatted(command.seatId().value(), owner.userId().value(),
                                    command.reservationId().value(), counter.get()));
        }
        return outcome;
    }
}
