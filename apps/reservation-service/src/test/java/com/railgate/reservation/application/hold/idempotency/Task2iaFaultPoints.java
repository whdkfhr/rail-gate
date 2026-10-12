package com.railgate.reservation.application.hold.idempotency;

/**
 * 테스트 전용 실패 주입·순서 고정 지점. 테스트가 래치로 멈추거나 {@link SimulatedCrash} 를 던진다.
 *
 * <p>실험용 구현 안에만 있다. <b>운영 코드에 테스트 훅을 넣지 않는다.</b>
 */
@FunctionalInterface
interface Task2iaFaultPoints {

    enum Point {
        /** TX0(키 확보) 커밋 직후, TX1 시작 전. */
        AFTER_TX0,
        /** TX1 안, 본 작업 직전. 아직 아무 잠금도 쥐지 않았다. */
        TX1_BEFORE_WORK,
        /** TX1 안, 본 작업과 (선택 구현이면) 완료 기록 직후, 커밋 전. quota·좌석·키 행 잠금을 쥐고 있다. */
        TX1_BEFORE_COMMIT,
        /** TX1 이 끝난 직후(커밋 또는 업무 실패 롤백), 결과를 돌려주거나 TX2 를 열기 전. */
        AFTER_TX1_COMMIT,
        /** TX2(결과 기록) 시작 전. */
        BEFORE_TX2,
        /** TX2 안, 기록 문장 직전. 여기서 예외를 던지면 TX2 가 롤백된다. */
        IN_TX2
    }

    Task2iaFaultPoints NONE = point -> { };

    void at(Point point);

    /**
     * 프로세스가 그 지점에서 죽었다는 표시. {@link Error} 이므로 구현의 {@code catch (RuntimeException)}
     * 이 잡지 않고 <b>그 뒤의 어떤 코드도 실행되지 않는다.</b> 열려 있던 트랜잭션은 롤백된다 —
     * 실제 크래시에서 커넥션이 끊겨 미커밋 트랜잭션이 롤백되는 것과 같다.
     */
    final class SimulatedCrash extends Error {

        private static final long serialVersionUID = 1L;

        SimulatedCrash(Point point) {
            super("simulated crash at " + point);
        }
    }
}
