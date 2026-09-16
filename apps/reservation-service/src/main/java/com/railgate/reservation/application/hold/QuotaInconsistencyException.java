package com.railgate.reservation.application.hold;

/**
 * 좌석과 quota 카운터가 어긋나 있어 유스케이스를 완료할 수 없다.
 *
 * <h2>언제 나는가</h2>
 *
 * <ul>
 *   <li>활성 좌석을 <b>실제로 해제했는데</b> 그 사용자·회차의 카운터 행이 없다</li>
 *   <li>감소가 거절됐다 — 카운터가 실제 해제 수보다 작아 음수가 될 요청이다</li>
 * </ul>
 *
 * <p>둘 다 <b>정상 흐름에서는 일어나지 않는다.</b> 선점 경로가 quota 확보와 좌석 선점을
 * 같은 트랜잭션에 묶기 때문이다. 일어난다면 drift 가 이미 있다는 뜻이다 —
 * 활성화 전 backfill 을 거치지 않은 기존 홀드거나, 아직 연동되지 않은 확정·만료 경로가
 * 남긴 흔적이거나, 운영 SQL 로 직접 고친 값이다.
 *
 * <h2>★ 왜 조용히 넘기지 않는가</h2>
 *
 * <p>좌석만 풀고 카운터를 그대로 두면 사용자는 좌석 없이 상한을 소진한 채 남는다.
 * 카운터를 0 으로 맞추거나 행을 만들어 넘기면 drift 의 <b>증거가 사라진다.</b>
 * 그래서 예외로 전파해 <b>좌석 해제까지 롤백</b>시킨다. 해제는 사용자가 다시 시도할 수 있지만
 * 사라진 증거는 되돌릴 수 없다.
 *
 * <p>HTTP 매핑은 정하지 않았다. 경합(409)도 상한(미정)도 아닌 <b>서버 측 정합성 문제</b>이므로
 * 그것들과 같은 분류로 다뤄서는 안 된다는 점만 적어 둔다.
 */
public class QuotaInconsistencyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public QuotaInconsistencyException(String message) {
        super(message);
    }
}
