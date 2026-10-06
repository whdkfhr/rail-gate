package com.railgate.reservation.application.expiry;

/**
 * 재시도 사이의 대기.
 *
 * <p><b>트랜잭션 밖에서 호출된다.</b> 실패한 시도의 트랜잭션은 이미 롤백·종료됐고 다음 시도는
 * 아직 시작하지 않았다. 트랜잭션 안에서 대기하면 잠금을 쥔 채 잠들어 상대를 더 오래 막는다.
 *
 * <p>테스트는 대기하지 않는 구현을 주입해 호출 횟수와 시점만 검증한다.
 */
@FunctionalInterface
public interface RetryBackoff {

    /**
     * @param attempt 방금 실패한 시도 번호 (1부터). 이 뒤에 {@code attempt + 1} 번째 시도가 온다
     * @throws InterruptedException 대기 중 인터럽트. <b>삼키지 않고 전파한다</b>
     */
    void await(int attempt) throws InterruptedException;

    /**
     * 기본 정책 — 상한이 있는 지수 백오프. {@code 50ms → 100ms}, 상한 {@code 200ms}.
     *
     * <p><b>왜 이 값인가.</b> 재시도 대상 넷 중 데드락(1213)은 즉시 재시도해도 대부분 성공한다 —
     * 피해자가 롤백된 순간 상대는 이미 잠금을 쥐고 진행 중이다. 잠금 대기 초과(1205)는 이미
     * 3초를 기다린 뒤이므로 긴 백오프를 더할 이유가 없다. 짧게 시작해 두 배로 늘리되 상한을 둔다.
     * 최대 시도가 3회이므로 백오프는 최대 2회, 누적 150ms 다.
     *
     * <p><b>측정으로 정한 값이 아니다.</b> 경합 프로파일을 측정하면 조정한다. 무한 증가하지 않고
     * 배치 한 번의 지연 상한이 예측 가능하다는 것만 이 값이 보장한다.
     */
    static RetryBackoff boundedExponential() {
        return attempt -> {
            long millis = Math.min(200L, 50L * (1L << Math.max(0, attempt - 1)));
            Thread.sleep(millis);
        };
    }
}
