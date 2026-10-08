package com.railgate.reservation.hold;

/**
 * 결제 시작({@code HELD → PAYING}) 시도의 결과.
 *
 * <p>예외가 아니라 반환값인 이유는 선점 결과와 같다.
 * 실패는 오류가 아니라 정상 동작이며 API 계층이 생기면 409 로 매핑한다 (CLAUDE.md 규칙 21).
 *
 * <p>Task 2H-F 에서 {@code reservation-infra} 에서 이곳으로 <b>옮겼다</b> (복제하지 않았다).
 * 앱 서비스가 이 타입을 반환하므로 infra 에 두면 앱이 구현 모듈 타입에 의존하게 된다.
 * {@link SeatConfirmationOutcome} 을 옮긴 Task 2H-C 와 같은 이유다.
 *
 * <p><b>이 타입은 HTTP 를 모른다.</b>
 */
public enum SeatPaymentOutcome {

    /** 결제 시작에 성공했다. {@code affected_rows} 가 1 이었다. */
    STARTED,

    /**
     * 결제를 시작하지 못했다. {@code affected_rows} 가 0 이었다는 뜻이며,
     * 원인은 넷 중 하나다.
     * <ul>
     *   <li>좌석이 {@code HELD} 가 아니다 (이미 결제 중이거나 팔렸거나 판매 가능하다)</li>
     *   <li>{@code hold_id} 가 다르다 (남의 홀드다)</li>
     *   <li>선점이 이미 만료됐다</li>
     *   <li>좌석이 존재하지 않는다</li>
     * </ul>
     * 어느 쪽인지 구분하려면 추가 조회가 필요하지만, 그 조회는 또 하나의 창을 만들 뿐이므로
     * 하지 않는다. 호출자에게 필요한 것은 "내 것이 되었는가" 하나다.
     *
     * <p><b>이미 {@code PAYING} 인 좌석에 같은 요청을 다시 보내도 이 값이 돌아오며 만료 시각은
     * 다시 늘어나지 않는다.</b> 이는 CAS 결과일 뿐 최초 응답을 재생한 멱등 성공이 아니다 (규칙 17).
     */
    NOT_STARTED
}
