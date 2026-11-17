package com.railgate.reservation.application.hold;

/**
 * ★ D4 — TX1 안에서 좌석으로 해석한 판매 회차가 <b>인증된 입장 회차</b>({@link HoldSeatsCommand#expectedSaleEventId()})와
 * 다르다 (HOLD_HTTP_CONTRACT §8.1).
 *
 * <p>quota·좌석을 건드리기 전에 던져 TX1 을 롤백시킨다. <b>업무 실패가 아니다</b> — 멱등 조정자는 결과를 저장하지 않고 리스를 포기한
 * 뒤 전파하며, HTTP 계층이 403 {@code ADMISSION_EVENT_MISMATCH} 로 바꾼다. 서버 장애(5xx)로 세지 않는다.
 * 메시지에 사용자·회차 값을 넣지 않는다.
 */
public class AdmissionEventMismatchException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AdmissionEventMismatchException() {
        super("좌석의 판매 회차가 인증된 입장 회차와 다르다", null, false, false);
    }
}
