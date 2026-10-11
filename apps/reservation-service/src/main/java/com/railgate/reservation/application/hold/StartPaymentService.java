package com.railgate.reservation.application.hold;

import com.railgate.reservation.hold.SeatPaymentOutcome;
import com.railgate.reservation.hold.SeatPaymentPort;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 단일 좌석 결제 시작 유스케이스 ({@code HELD → PAYING}). <b>이 클래스가 트랜잭션을 소유한다.</b>
 *
 * <h2>흐름</h2>
 *
 * <p>기존 저장소의 조건부 UPDATE <b>한 문장</b>이 전부다. 사전 조회도, quota 잠금도 없다.
 * {@code id}·{@code hold_id}·{@code status='HELD'}·{@code expires_at > NOW(3)} 를 검사하고
 * 만료 시각을 {@code GREATEST(기존 값, NOW(3) + 결제 창)} 으로 바꾸는 일이 같은 문장에서 끝나며,
 * 성공 판정은 그 문장의 {@code affected_rows} 다. 만료 판단에 애플리케이션 시각을 쓰지 않는다 (규칙 7).
 *
 * <h2>★ quota 를 건드리지 않는 이유</h2>
 *
 * <p>I-12 의 카운터는 <b>활성 점유</b>({@code HELD} + {@code PAYING}) 좌석 수다. 결제 시작은
 * 활성 점유 안에서 상태만 바꾸므로 카운터 값이 바뀌지 않는다. 바뀌지 않는 값을 위해
 * quota 행을 조회하거나 잠그면 같은 사용자의 선점·해제·확정과 불필요하게 직렬화될 뿐이다.
 *
 * <h2>★ 잠금 순서가 다른 경로와 충돌하지 않는 근거</h2>
 *
 * <p>이 트랜잭션이 잠그는 것은 <b>좌석 행 하나</b>(PK)뿐이다. 데드락은 둘 이상의 자원을 서로
 * 다른 순서로 잡을 때 생기는데, 자원이 하나뿐이면 순서라는 것이 없다. 선점·해제·확정의
 * {@code quota → seat}, 만료의 {@code quota → seat(PK 오름차순)} 어느 것과 만나도 이 쪽은
 * 그 좌석 행 잠금을 기다리거나 먼저 잡고 곧바로 끝낼 뿐 다른 잠금을 쥔 채 기다리지 않는다.
 * <b>단, 호출자가 바깥 트랜잭션에서 다른 행을 이미 잠갔다면</b> 그 순서는 호출자의 책임이다.
 *
 * <h2>이 서비스가 하지 않는 것</h2>
 *
 * <ul>
 *   <li><b>결제 요청·승인</b> — PG 를 부르지 않는다. 결제 승인 검증(I-15)도 없다.</li>
 *   <li><b>인증·인가</b> — {@code holdId} 일치는 로그인 사용자가 홀드 소유자인지 확인하지 않는다.</li>
 *   <li><b>멱등성</b> — 이미 {@code PAYING} 인 재요청은 {@code NOT_STARTED} 이고 만료를 다시
 *       늘리지 않는다. 그것은 CAS 결과이지 최초 응답 재생이 아니다 (규칙 17).</li>
 *   <li>다좌석 결제 시작, REST API, 감사 로그(규칙 32), 메트릭(규칙 35).</li>
 * </ul>
 *
 * <p><b>반환값은 아직 최종 커밋이 아닐 수 있다.</b> {@code REQUIRED} 로 참여하므로 바깥 트랜잭션이
 * 있으면 그 안의 결과이며, 바깥이 롤백하면 상태·만료 시각·{@code version} 이 모두 되돌아간다.
 */
@Service
public class StartPaymentService {

    private final SeatPaymentPort seats;

    public StartPaymentService(SeatPaymentPort seats) {
        this.seats = Objects.requireNonNull(seats, "seats");
    }

    /**
     * 선점한 좌석의 결제 단계를 확보한다.
     *
     * @return {@link SeatPaymentOutcome#NOT_STARTED} 는 정상 동작이다. 원인(HELD 아님·다른 홀드·
     *         만료됨·없는 좌석·이미 PAYING)은 구분되지 않는다
     */
    @Transactional
    public SeatPaymentOutcome start(StartPaymentCommand command) {
        Objects.requireNonNull(command, "command");
        return seats.startPayment(command.seatId(), command.holdId());
    }
}
