package com.railgate.reservation.hold;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.seat.SeatId;
import com.railgate.reservation.seat.SeatUnavailableException;
import java.util.List;

/**
 * 여러 좌석을 하나의 홀드로 <b>전부-또는-전무</b> 선점한다 (I-9).
 *
 * <h2>성공을 나타내는 반환값이 없다</h2>
 *
 * <p>정상 종료가 곧 "요청 좌석 전부를 선점했다" 는 뜻이다. 부분 성공이라는 결과가
 * 존재하지 않으므로 성공을 표현할 값이 하나뿐이고, 값이 하나뿐인 결과 타입은
 * 아무 정보도 전달하지 않는다.
 *
 * <h2>★ 경합 예외를 트랜잭션 안에서 삼키지 말 것</h2>
 *
 * <p>{@link SeatUnavailableException} 을 트랜잭션 <b>안</b>에서 잡아 성공으로 처리하면,
 * 좌석의 부분 갱신은 구현의 savepoint 로 되돌아가는 반면 같은 트랜잭션의
 * <b>quota 증가는 그대로 커밋된다.</b> 좌석 없이 카운터만 오르는 상태다.
 * HTTP 매핑(규칙 21 의 409)은 <b>트랜잭션 경계 밖</b>에서 한다.
 */
public interface SeatHoldPort {

    /**
     * 좌석 전부를 하나의 홀드로 선점한다.
     *
     * @throws IllegalArgumentException 좌석 수가 1~4석이 아니거나 중복이 있는 경우
     * @throws IllegalStateException    호출자의 트랜잭션에 참여하지 않은 경우
     * @throws SeatUnavailableException 요청 좌석 중 하나 이상을 선점할 수 없는 경우.
     *                                  <b>예상 가능한 정상 실패다</b> (규칙 21)
     */
    void holdAll(List<SeatId> seatIds, HoldId holdId, UserId userId);
}
