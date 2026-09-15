package com.railgate.reservation.application.hold;

/**
 * 1인당 좌석 상한(I-12, P-2: 4석)을 넘어 quota 를 확보하지 못했다.
 *
 * <h2>왜 좌석 경합과 다른 타입인가</h2>
 *
 * <p>{@code SeatUnavailableException}(경합)과 원인도 대응도 다르다.
 * <ul>
 *   <li><b>경합</b> — 다른 사람이 먼저 가져갔다. 다른 좌석을 고르면 성공할 수 있다</li>
 *   <li><b>상한 초과</b> — 이미 가진 좌석 때문이다. 다른 좌석을 골라도 실패한다.
 *       기존 홀드를 풀거나 만료돼야 한다</li>
 * </ul>
 *
 * <p>하나의 타입으로 합치면 호출부가 둘을 구분할 수 없고, 사용자는 재시도해도
 * 계속 실패하는 이유를 알 수 없다.
 *
 * <h2>★ HTTP 매핑은 여기서 정하지 않는다</h2>
 *
 * <p>Task 2H-A 의 범위가 아니다. REST API 자체가 없다.
 * 결정할 때는 규칙 21(409 = 좌석 경합)과 규칙 22(429 = 입장 제한, {@code Retry-After} 필수)를
 * 함께 보고 <b>어느 쪽도 아닌 제3의 상태가 맞는지</b>까지 검토해야 한다 —
 * 상한 초과는 경합도 rate limit 도 아니다.
 *
 * <p>매핑은 반드시 <b>트랜잭션 경계 밖</b>에서 한다. 경계 안에서 잡으면
 * 좌석 선점 실패를 삼키고 quota 만 커밋하는 경로가 생긴다.
 */
public class QuotaExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public QuotaExceededException(String message) {
        super(message);
    }
}
