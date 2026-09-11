package com.railgate.reservation.application.hold;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.hold.SeatHoldPort;
import com.railgate.reservation.quota.QuotaAcquireOutcome;
import com.railgate.reservation.quota.UserHoldQuotaPort;
import com.railgate.reservation.saleevent.SaleEventId;
import com.railgate.reservation.saleevent.SaleEventScopeException;
import com.railgate.reservation.saleevent.SaleEventScopePort;
import com.railgate.reservation.seat.SeatUnavailableException;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 좌석 선점 유스케이스. <b>이 클래스가 트랜잭션을 소유한다.</b>
 *
 * <h2>★ 왜 여기가 트랜잭션 경계인가</h2>
 *
 * <p>저장소들은 트랜잭션을 열지 않는다 (Task 2F·2G-G). 열려 있는 트랜잭션에 참여할 뿐이고,
 * 참여하지 않았으면 SQL 을 보내기 전에 거부한다. <b>따라서 누군가는 경계를 열어야 하고,
 * 그 자리가 여기다.</b> 저장소가 각자 열면 quota 와 좌석이 서로 다른 시점에 확정된다.
 *
 * <p>경계가 하나여야 하는 이유는 실패했을 때 드러난다.
 * <ul>
 *   <li>좌석 선점이 경합으로 실패 → quota 증가도 <b>함께</b> 사라져야 한다.
 *       그러지 않으면 사용자는 잡지도 못한 좌석 때문에 상한을 소진한다</li>
 *   <li>quota 가 상한을 넘음 → 좌석을 <b>건드리지도 않아야</b> 한다</li>
 * </ul>
 *
 * <h2>흐름</h2>
 *
 * <ol>
 *   <li><b>요청 검증</b> — {@link HoldSeatsCommand} 생성자가 한다. 트랜잭션 밖이다</li>
 *   <li><b>판매 회차 해석</b> — 좌석으로부터 서버가 정한다. 클라이언트 값을 믿지 않는다</li>
 *   <li><b>quota 행 확보</b> — 없으면 만들고, 있으면 값을 보존한다</li>
 *   <li><b>quota 조건부 증가</b> — 검사와 증가를 한 문장으로 (규칙 8)</li>
 *   <li><b>다좌석 원자 선점</b> — 전부 또는 전무 (I-9)</li>
 * </ol>
 *
 * <p><b>순서가 계약이다.</b> quota 를 좌석보다 먼저 잠근다 (TASK-002G-B).
 * 뒤집으면 좌석을 잡은 트랜잭션이 quota 를 기다리고 quota 를 잡은 트랜잭션이 좌석을
 * 기다리는 순환이 생긴다. 좌석들 사이의 순서는 저장소가 PK 오름차순으로 유지한다 (규칙 2).
 *
 * <h2>★ 예외를 삼키지 않는다</h2>
 *
 * <p>이 서비스는 실패를 성공으로 바꾸지 않는다. {@link SeatUnavailableException} 을
 * 여기서 잡으면 좌석의 부분 갱신은 저장소의 savepoint 로 되돌아가는 반면
 * <b>quota 증가는 그대로 커밋된다.</b> 좌석 없이 카운터만 오르는 상태이며
 * drift 검사(V-7a)가 뒤늦게 잡아낼 뿐이다.
 *
 * <p>{@link QuotaExceededException} 도 마찬가지로 밖으로 나간다.
 * 그것이 트랜잭션을 롤백시켜 4단계의 증가를 없앤다 — 사실 이 경로에서는 증가가
 * 일어나지 않았지만, 롤백에 의존하지 않는 것과 롤백이 무해한 것은 다른 이야기다.
 *
 * <h2>이 서비스가 아직 하지 않는 것</h2>
 *
 * <ul>
 *   <li><b>멱등키</b> — 같은 요청을 재시도해도 <b>최초 성공 응답을 그대로 재생하지 못한다</b>
 *       (규칙 17). 첫 요청이 성공한 뒤 재시도하면 자신의 홀드 때문에 경합·상한 거절을
 *       받는다. 규칙 14~17 이 요구하는 저장소가 없다</li>
 *   <li><b>quota 감소</b> — 확정·해제·만료 어느 경로도 줄이지 않는다.
 *       <b>따라서 카운터는 실제보다 계속 커진다.</b> 세 경로 연동 전에는 운영에 켜면 안 된다</li>
 *   <li><b>감사 로그</b>(규칙 32) — {@code seat_state_log} 테이블이 없다</li>
 *   <li><b>메트릭</b>(규칙 35) — 선점 성공/상한 초과/경합 계측이 없다</li>
 *   <li><b>REST API</b> — 호출하는 진입점이 없다</li>
 * </ul>
 */
@Service
public class HoldSeatsService {

    private final SaleEventScopePort saleEventScope;
    private final UserHoldQuotaPort quota;
    private final SeatHoldPort seats;

    public HoldSeatsService(
            SaleEventScopePort saleEventScope, UserHoldQuotaPort quota, SeatHoldPort seats) {
        this.saleEventScope = Objects.requireNonNull(saleEventScope, "saleEventScope");
        this.quota = Objects.requireNonNull(quota, "quota");
        this.seats = Objects.requireNonNull(seats, "seats");
    }

    /**
     * 좌석을 선점한다. quota 확보와 좌석 선점이 <b>하나의 트랜잭션</b>으로 묶인다.
     *
     * <p>{@code @Transactional} 이 붙어 있으므로 <b>Spring 프록시를 통해</b> 호출해야
     * 경계가 열린다. 직접 {@code new} 로 만든 인스턴스에는 경계가 없고, 그 경우
     * 저장소들의 참여 검사가 {@link IllegalStateException} 으로 거부한다 —
     * 조용히 autocommit 되지 않는다.
     *
     * @throws SaleEventScopeException  좌석이 존재하지 않거나 서로 다른 회차에 걸쳐 있는 경우
     * @throws QuotaExceededException   1인당 상한(4석)을 넘는 경우
     * @throws SeatUnavailableException 요청 좌석 중 하나 이상이 이미 남의 것인 경우
     * @throws IllegalStateException    저장소가 트랜잭션에 참여하지 못한 경우 (배선 실수)
     */
    @Transactional
    public HoldSeatsResult hold(HoldSeatsCommand command) {
        Objects.requireNonNull(command, "command");

        UserId userId = command.userId();
        int seatCount = command.seatIds().size();

        // 2. 좌석에서 회차를 해석한다. 존재하지 않는 좌석과 회차 혼합이 여기서 걸러진다.
        //    같은 회차의 여러 운행편은 하나의 회차로 해석되므로 quota 가 자연히 합산된다.
        SaleEventId saleEventId = saleEventScope.resolve(command.seatIds());

        // 3~4. quota 를 좌석보다 먼저 잠근다 (TASK-002G-B).
        quota.ensureRow(saleEventId, userId);
        if (quota.tryAcquire(saleEventId, userId, seatCount) != QuotaAcquireOutcome.ACQUIRED) {
            // 좌석을 건드리기 전이다. 롤백할 좌석 변경이 없다.
            throw new QuotaExceededException(
                    "사용자 %s 는 판매 회차 %s 에서 %d석을 더 선점할 수 없다 (1인당 상한 초과)"
                            .formatted(userId.value(), saleEventId.value(), seatCount));
        }

        HoldId holdId = HoldId.newId();

        // 5. 전부 또는 전무. 실패하면 SeatUnavailableException 이 밖으로 나가
        //    이 트랜잭션 전체가 롤백되고 4단계의 quota 증가도 사라진다.
        //    여기서 잡지 않는다 — 잡으면 좌석 없이 카운터만 오른다.
        seats.holdAll(command.seatIds(), holdId, userId);

        return new HoldSeatsResult(holdId, saleEventId, seatCount);
    }
}
