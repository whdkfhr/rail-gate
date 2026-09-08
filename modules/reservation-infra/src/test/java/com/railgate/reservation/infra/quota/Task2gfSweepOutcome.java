package com.railgate.reservation.infra.quota;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * <b>테스트 전용</b> 만료 배치 결과 (Task 2G-F 실험 §16).
 *
 * <h2>왜 {@code int} 하나로 부족한가</h2>
 *
 * <p>운영 {@code JdbcSeatExpiryRepository.sweep} 은 회수 행 수 하나만 돌려준다.
 * 그 값으로는 다음을 구분할 수 없다.
 *
 * <ul>
 *   <li><b>0 이 정상인가 이상인가</b> — 만료 후보가 없었던 것과 전부 stale 했던 것과
 *       모든 그룹이 drift 로 실패한 것이 모두 0 이다.</li>
 *   <li><b>어느 사용자가 실패했는가</b> — 알람이 가리킬 대상이 없다.</li>
 *   <li><b>재시도가 몇 번 일어났는가</b> — 경합이 늘고 있는지 알 수 없다.</li>
 *   <li><b>손상 행이 몇 개 남아 있는가</b> — 영원히 회수되지 않는 좌석의 존재를 모른다.</li>
 * </ul>
 *
 * <p><b>운영 타입 이름을 여기서 확정하지 않는다.</b> Task 2H 가 애플리케이션 서비스를 만들 때
 * 필요한 최소 정보가 무엇인지를 이 실험이 답하고, 이름과 패키지는 그때 정한다.
 *
 * @param candidatesFound 후보 SELECT 가 돌려준 행 수. <b>현재 상태의 진실이 아니다</b>
 * @param reclaimed       실제 회수된 좌석 수. 모든 그룹의 {@code affected_rows} 합
 * @param succeeded       커밋된 그룹과 그 회수 수
 * @param failed          롤백된 그룹과 실패 원인
 * @param staleCandidates 후보였지만 회수되지 않은 수 ({@code candidatesFound - reclaimed - 실패 그룹 후보})
 * @param corrupt         정산할 수 없는 손상 후보 (예: {@code held_by} 가 NULL)
 * @param totalAttempts   모든 그룹의 시도 횟수 합. 재시도가 없으면 그룹 수와 같다
 * @param sql             SQL 호출 수와 트랜잭션 수
 */
record Task2gfSweepOutcome(
        int candidatesFound,
        int reclaimed,
        Map<Task2gfExpirySweeper.GroupKey, Integer> succeeded,
        List<Task2gfExpirySweeper.GroupFailure> failed,
        int staleCandidates,
        List<Task2gfExpirySweeper.CorruptCandidate> corrupt,
        int totalAttempts,
        Instant startedAt,
        Instant finishedAt,
        Task2gfSqlCounts sql) {

    Duration elapsed() {
        return Duration.between(startedAt, finishedAt);
    }

    boolean hasFailures() {
        return !failed.isEmpty() || !corrupt.isEmpty();
    }

    @Override
    public String toString() {
        return ("SweepOutcome[후보=%d, 회수=%d, 성공그룹=%d, 실패그룹=%d, stale=%d, 손상=%d, "
                + "시도=%d, %s]")
                .formatted(candidatesFound, reclaimed, succeeded.size(), failed.size(),
                        staleCandidates, corrupt.size(), totalAttempts, sql);
    }
}
