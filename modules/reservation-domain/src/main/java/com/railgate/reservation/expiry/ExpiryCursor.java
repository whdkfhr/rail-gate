package com.railgate.reservation.expiry;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 만료 후보 탐색의 <b>위치 표식</b> — {@code (expires_at, id)} 키셋 커서.
 *
 * <h2>왜 OFFSET 이 아니라 키셋인가</h2>
 *
 * <p>{@code OFFSET} 은 "앞의 N 행을 건너뛴다" 는 뜻이라 <b>그 N 행이 그대로 있을 때만</b>
 * 옳다. 만료 배치는 앞 페이지의 좌석을 회수해 후보 집합에서 <b>없애면서</b> 진행하므로,
 * 다음 페이지에서 {@code OFFSET} 을 쓰면 회수된 수만큼 <b>정상 후보를 건너뛴다.</b>
 * 키셋은 "이 키보다 큰 것" 이라 앞의 행이 사라져도 위치가 흔들리지 않는다.
 *
 * <h2>{@code expires_at} 을 담는 것이 규칙 7 위반이 아닌 이유</h2>
 *
 * <p>CLAUDE.md 규칙 7 은 <b>만료 판정</b>에 애플리케이션 시각을 쓰지 말라는 것이다.
 * 판정은 여전히 DB {@code NOW(3)} 이 한다 — 이 값은 판정에 쓰이지 않고
 * <b>DB 에서 읽은 행의 정렬 키를 그대로 되돌려 주는 위치 표식</b>일 뿐이다.
 * 클럭 드리프트가 개입할 자리가 없다.
 *
 * <p>{@link LocalDateTime} 인 이유는 컬럼이 {@code DATETIME(3)}(무시간대)이기 때문이다.
 * 시간대 변환을 거치면 왕복에서 값이 어긋날 수 있다.
 *
 * @param expiresAt 마지막으로 본 후보의 {@code expires_at}
 * @param seatId    같은 {@code expires_at} 안의 tie-breaker. 이것이 없으면 만료 시각이 같은
 *                  좌석들이 페이지 경계에서 누락되거나 무한히 반복된다
 */
public record ExpiryCursor(LocalDateTime expiresAt, long seatId) {

    public ExpiryCursor {
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    @Override
    public String toString() {
        return "(%s, %d) 이후".formatted(expiresAt, seatId);
    }
}
