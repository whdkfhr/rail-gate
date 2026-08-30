package com.railgate.reservation.schedule;

/**
 * 저장된 운행편 데이터가 도메인이 만들 수 없는 조합이라 복원을 거부했다.
 *
 * <p>여기서 가장 중요한 손상은 <b>판매 회차 소속이 없는 운행편</b>이다.
 * 그런 행이 존재하면 그 운행편의 좌석은 어느 quota 카운터에도 귀속되지 않아
 * I-12 의 상한 밖에 놓인다. 조회 시점에 즉시 드러나게 하는 것이 이 예외의 목적이다.
 *
 * <p>운영에서는 서버 측 정합성 오류로 다뤄야 한다. 구체적인 HTTP 매핑과 알람 정책은
 * 애플리케이션 계층의 후속 결정이다 (Task 2H).
 */
public class TrainScheduleRestoreException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TrainScheduleRestoreException(String message) {
        super(message);
    }
}
