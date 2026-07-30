package com.railgate.reservation.hold;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.seat.Seat;
import com.railgate.reservation.seat.SeatAlreadyHeldException;
import com.railgate.reservation.seat.SeatId;
import com.railgate.reservation.seat.SeatStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 여러 좌석을 하나의 홀드로 묶어 선점하는 규칙 (FR-2.3 / I-9).
 *
 * <p>여러 엔티티에 걸친 규칙이므로 도메인 서비스로 둔다. Seat 하나가 스스로 판단할 수 없는
 * "전부 아니면 전무" 를 여기서 표현한다.
 *
 * <p><b>표현과 강제를 구분해야 한다.</b>
 * 이 클래스는 검사 후 변경(check-then-act)을 한다. 단일 스레드 안에서는 안전하지만
 * 동시성 보장이 전혀 없다. 실제 원자성은 인프라 계층의 단일 벌크 UPDATE 와
 * {@code affected_rows} 검증이 제공한다.
 * <pre>
 * UPDATE seat_inventory FORCE INDEX (PRIMARY)
 *    SET status='HELD', hold_id=?, ...
 *  WHERE id IN (정렬된 id) AND status='AVAILABLE';
 * -- affected_rows != 요청 좌석 수 → ROLLBACK
 * </pre>
 * 따라서 이 클래스의 존재가 CLAUDE.md 규칙 1(SELECT 후 UPDATE 금지)의 예외인 것이 아니다.
 * 그 규칙은 DB 접근 코드에 적용되며, 여기서는 적용 대상 자체가 없다.
 */
public final class SeatHoldPolicy {

    private SeatHoldPolicy() {
    }

    /**
     * 좌석 전부를 하나의 홀드로 선점한다. 하나라도 실패하면 어떤 좌석도 변경되지 않는다.
     *
     * @throws IllegalArgumentException  좌석 수가 1~4석이 아니거나 중복된 좌석이 있는 경우
     * @throws SeatAlreadyHeldException  하나라도 선점 불가능한 경우 (경합 실패, 409)
     */
    public static SeatCount holdAll(
            List<Seat> seats, HoldId holdId, UserId userId, Instant expiresAt) {

        Objects.requireNonNull(seats, "seats 는 null 일 수 없다");
        SeatCount count = new SeatCount(seats.size());
        requireNoDuplicates(seats);

        // 1단계: 전량 검사. 한 좌석이라도 불가하면 여기서 중단하므로 부분 변경이 남지 않는다.
        for (Seat seat : seats) {
            if (seat.status() != SeatStatus.AVAILABLE) {
                throw new SeatAlreadyHeldException(
                        "좌석 %d 를 선점할 수 없어 요청 전체가 실패한다 (현재 %s)"
                                .formatted(seat.id().value(), seat.status()));
            }
        }

        // 2단계: 전량 변경.
        // 잠금 순서와 같은 순서로 적용한다. 인메모리에서는 순서가 무관하지만,
        // 인프라 계층이 반드시 지켜야 하는 순서를 도메인이 그대로 드러내도록 맞춘다.
        seats.stream()
                .sorted(Comparator.comparing(Seat::id))
                .forEach(seat -> seat.hold(holdId, userId, expiresAt));

        return count;
    }

    /**
     * 여러 좌석을 잠글 때 사용할 순서를 돌려준다 (CLAUDE.md 규칙 2).
     *
     * <p>사용자 A 가 [12A, 12B] 순서로, B 가 [12B, 12A] 순서로 잠그면 데드락이다.
     * 모든 경로가 이 메서드가 정한 순서를 쓰면 교착이 생기지 않는다.
     */
    public static List<SeatId> lockOrder(Collection<SeatId> seatIds) {
        Objects.requireNonNull(seatIds, "seatIds 는 null 일 수 없다");
        return seatIds.stream().sorted().toList();
    }

    private static void requireNoDuplicates(List<Seat> seats) {
        Set<SeatId> distinct = seats.stream().map(Seat::id).collect(Collectors.toSet());
        if (distinct.size() != seats.size()) {
            throw new IllegalArgumentException("같은 좌석을 중복해서 선점할 수 없다");
        }
    }
}
