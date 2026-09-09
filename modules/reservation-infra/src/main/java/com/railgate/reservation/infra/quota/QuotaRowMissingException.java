package com.railgate.reservation.infra.quota;

/**
 * quota 카운터 행이 없다.
 *
 * <p>좌석은 그 사용자 것인데 카운터가 없다는 뜻이며 <b>이미 어긋난 상태다.</b>
 * 조용히 무시하면 그 사용자의 카운터가 영구히 실제와 달라지고, 자동 생성하면
 * 원인이 조사되지 않은 채 증상만 사라진다 (TASK-002G-F 실험 C).
 *
 * <p>{@link JdbcUserHoldQuotaRepository#lockAll} 이 던진다.
 * 한 행씩 다루는 {@link JdbcUserHoldQuotaRepository#lockRow} 는 예외 대신
 * 빈 {@code Optional} 을 돌려준다 — 호출자가 그룹 단위로 실패를 모으는 경우가 있어서다.
 *
 * <p>HTTP 매핑은 여기서 정하지 않는다. 애플리케이션 계층의 후속 결정이다 (Task 2H).
 */
public class QuotaRowMissingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public QuotaRowMissingException(String message) {
        super(message);
    }
}
