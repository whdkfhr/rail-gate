package com.railgate.reservation.infra.quota;

/**
 * <b>테스트 전용</b> SQL 호출 수와 트랜잭션 수 (Task 2G-F §15).
 *
 * <p>2G-C 가 "SQL 수 증가 비용" 을 미측정으로 남겼다. 실행 시간은 환경에 좌우되므로
 * <b>구조적인 값</b>인 호출 수로 비교한다 (CLAUDE.md 규칙 30·31).
 * 이 값들은 테스트가 assertion 으로 고정할 수 있다.
 *
 * <p>스위퍼가 자기가 보낸 문장을 직접 센다. DataSource 를 감싸 프록시로 세지 않는 이유는,
 * 그러면 트랜잭션 관리자가 보내는 {@code SET autocommit} 같은 문장까지 섞여
 * <b>비교하려는 값이 흐려지기 때문</b>이다.
 */
record Task2gfSqlCounts(
        int candidateSelects,
        int quotaLockSelects,
        int seatUpdates,
        int quotaUpdates,
        int transactions) {

    int total() {
        return candidateSelects + quotaLockSelects + seatUpdates + quotaUpdates;
    }

    @Override
    public String toString() {
        return "SQL[후보SELECT=%d, quota잠금=%d, 좌석UPDATE=%d, quota감소=%d, 합=%d, TX=%d]"
                .formatted(candidateSelects, quotaLockSelects, seatUpdates, quotaUpdates,
                        total(), transactions);
    }
}
