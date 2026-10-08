package com.railgate.reservation.application.expiry;

/**
 * 만료 배치 한 번을 실행하는 능력. {@link ExpireHoldsService} 가 구현한다.
 *
 * <p><b>스케줄러가 이것만 보도록 두는 이유.</b> 어댑터가 서비스 구체 타입에 묶이면 호출 계약
 * 테스트가 실제 DB·트랜잭션을 끌고 와야 한다. 여기서 검증할 것은 "어떤 요청으로 몇 번 부르는가"
 * 하나이므로 그 경계만 남긴다.
 *
 * <p>{@code sweep(int)} 는 노출하지 않는다 — 스케줄러는 항상 예산과 커서를 명시한다.
 */
public interface ExpirySweeper {

    /**
     * 만료 배치를 한 번 실행한다.
     *
     * @throws IllegalStateException 활성 트랜잭션 안에서 호출된 경우, 또는 재시도 대기 중 인터럽트
     */
    ExpirySweepResult sweep(ExpirySweepRequest request);
}
