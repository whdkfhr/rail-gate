package com.railgate.reservation.application.expiry;

import com.railgate.reservation.expiry.ExpiryCursor;
import com.railgate.reservation.seat.SeatId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 만료 배치 한 번의 결과. {@code int} 하나로는 부족하다 (TASK-002G-F §16).
 *
 * <p>회수 0 이 "후보가 없었다" 인지 "전부 stale 했다" 인지 "모든 그룹이 실패했다" 인지 구분해야
 * 알람이 가리킬 곳이 생긴다.
 *
 * <p><b>{@link #succeeded} 는 그룹 트랜잭션이 커밋된 뒤에만 기록된다.</b> 커밋 전 값을 세면
 * 롤백된 회수를 성공으로 보고한다.
 *
 * @param candidatesFound 후보 조회가 돌려준 행 수 (손상 포함). 현재 상태의 진실이 아니다
 * @param reclaimed       실제 회수 수. 커밋된 그룹의 {@code affected_rows} 합
 * @param succeeded       커밋된 그룹과 각 회수 수 (0 인 그룹은 전부 stale)
 * @param failed          롤백된 그룹과 원인
 * @param staleCandidates 정상 후보였지만 회수되지 않은 수 = 성공 그룹의 (후보 − 회수)
 * @param corrupt         손상 후보. 회수·정산하지 않았다
 * @param totalAttempts   모든 그룹의 시도 횟수 합. 재시도가 없으면 그룹 수와 같다
 * @param pagesRead       이 실행이 읽은 후보 페이지 수. 예산({@code maxPages})을 넘지 않는다
 * @param nextCursor      <b>비어 있으면 순회 완료</b>(뒤에 후보가 없다). 값이 있으면 예산이
 *                        소진됐고 뒤에 더 있을 수 있다는 뜻이며, 호출자가
 *                        {@code ExpirySweepRequest.continueAfter} 로 이어가야 한다.
 *                        <b>이 값을 버리고 처음부터 다시 부르는 것도 유효한 선택</b>이며,
 *                        그 경우 앞의 실패 후보를 다시 확인한다
 */
public record ExpirySweepResult(
        int candidatesFound,
        int reclaimed,
        Map<QuotaGroupKey, Integer> succeeded,
        List<GroupFailure> failed,
        int staleCandidates,
        List<CorruptCandidate> corrupt,
        int totalAttempts,
        int pagesRead,
        Optional<ExpiryCursor> nextCursor) {

    public ExpirySweepResult {
        succeeded = Map.copyOf(Objects.requireNonNull(succeeded, "succeeded"));
        failed = List.copyOf(Objects.requireNonNull(failed, "failed"));
        corrupt = List.copyOf(Objects.requireNonNull(corrupt, "corrupt"));
        Objects.requireNonNull(nextCursor, "nextCursor");
    }

    /** 뒤에 더 볼 후보가 남아 있을 수 있는가. 참이면 {@link #nextCursor} 로 이어갈 수 있다. */
    public boolean hasMore() {
        return nextCursor.isPresent();
    }

    /** 롤백된 그룹. {@code attempts} 는 실제 시도 횟수다 (재시도 불가 원인이면 1). */
    public record GroupFailure(QuotaGroupKey key, GroupFailureKind kind, String detail, int attempts) {
    }

    /** 회수하지 않은 손상 후보. 감사 로그·메트릭이 생기면 그 지점에서 알려야 한다 (후속). */
    public record CorruptCandidate(SeatId seatId, String reason) {
    }

    public boolean hasFailures() {
        return !failed.isEmpty() || !corrupt.isEmpty();
    }
}
