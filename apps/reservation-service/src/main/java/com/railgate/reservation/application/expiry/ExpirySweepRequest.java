package com.railgate.reservation.application.expiry;

import com.railgate.reservation.expiry.ExpiryCursor;
import java.util.Objects;
import java.util.Optional;

/**
 * 만료 배치 한 번의 <b>탐색 범위와 예산</b>.
 *
 * <h2>왜 두 숫자인가 — 페이지 크기와 실행 예산은 다른 것이다</h2>
 *
 * <ul>
 *   <li>{@code pageSize} — 후보 SELECT <b>한 번이 돌려주는 행 수의 상한</b>({@code LIMIT}).
 *       잠금 없는 조회다. <b>실제로 스캔하는 행 수의 상한이 아니고</b>(옵티마이저가 더 읽을 수
 *       있다) <b>벌크 UPDATE 크기의 상한도 아니다</b>(아래 참고).</li>
 *   <li>{@code maxPages} — <b>한 번의 실행이 issue 할 수 있는 후보 SQL 수.</b>
 *       실패 후보가 앞을 막고 있을 때 <b>몇 페이지까지 넘어가 볼 것인가</b>이며,
 *       한 실행이 검사하는 후보 수의 상한은 {@code pageSize × maxPages} 다.</li>
 * </ul>
 *
 * <p>둘을 하나로 합치면 안 된다. <b>실패 후보를 지나가기 위해 필요한 것은 페이지 수</b>이지
 * 페이지 크기가 아니다. 페이지를 키워도 앞을 막은 실패 후보는 여전히 앞에 있다.
 *
 * <h2>★ {@code pageSize} 는 벌크 UPDATE 크기를 정하지 않는다</h2>
 *
 * <p>구현은 <b>여러 페이지를 먼저 모은 뒤</b> {@code (saleEventId, userId)} 로 그룹화하고,
 * 한 번의 벌크 UPDATE 는 <b>수집된 그 그룹의 후보 전부</b>를 대상으로 한다. 따라서 한 그룹의
 * 좌석이 여러 페이지에 흩어져 있으면 UPDATE 크기가 {@code pageSize} 보다 커진다.
 * {@code maxPages} 를 늘리면 <b>수집량이 늘어 각 UPDATE 도 커질 수 있다</b> —
 * "페이지 수를 늘려도 각 UPDATE 크기는 그대로" 가 아니다.
 *
 * <p>배치 구현이 거는 유일한 크기 상한은 <b>한 실행의 수집 후보 수 {@code pageSize × maxPages}</b>
 * 다. 정상 데이터라면 한 그룹의 활성 좌석이 P-2(1인당 4석)를 넘지 않지만, 그것은
 * <b>데이터가 정상일 때 성립하는 성질</b>이지 배치가 강제하는 제한이 아니다.
 * drift 가 있으면 한 그룹이 4석을 넘을 수 있고, 그때도 UPDATE 는 수집된 만큼을 다룬다.
 *
 * <h2>★ 상태 없는 continuation 을 택한 이유</h2>
 *
 * <p>진행 위치를 어디에 둘지 두 가지를 검토했다.
 *
 * <table>
 *   <caption>진행 위치의 보관 주체</caption>
 *   <tr><th></th><th>서비스 내부 상태(필드)</th><th><b>명시적 continuation (채택)</b></th></tr>
 *   <tr><td>다중 스위퍼</td><td>인스턴스마다 다른 위치를 갖는다. 누가 어디까지 갔는지 서로 모르고,
 *       스케일 아웃하면 동작이 인스턴스 수에 좌우된다</td>
 *       <td>위치가 호출 인자다. 인스턴스는 무상태이고 어느 쪽이 불려도 같다</td></tr>
 *   <tr><td>재시작</td><td>프로세스가 죽으면 위치가 사라진다. 살아 있으면 <b>영원히 앞을
 *       다시 안 본다</b> — 손상 후보가 고쳐져도 모른다</td>
 *       <td>호출자가 처음부터 시작할지 이어갈지 매번 정한다</td></tr>
 *   <tr><td>후보 시각 변경</td><td>연장·재선점으로 순서가 바뀌면 내부 위치가 의미를 잃는데
 *       그것을 알 방법이 없다</td>
 *       <td>커서는 <b>한 순회 안에서만</b> 유효하다고 계약돼 있다. 순회를 끝내고 새로 시작하면
 *       커서를 버리므로 바뀐 순서를 다시 읽는다</td></tr>
 *   <tr><td>테스트</td><td>상태를 초기화해야 한다</td><td>인자만 바꾸면 된다</td></tr>
 * </table>
 *
 * <p>그래서 서비스는 <b>어떤 진행 상태도 보관하지 않는다.</b> 커서는
 * {@link ExpirySweepResult#nextCursor} 로 나가고 <b>호출자가 다음 호출에 넣어 준다.</b>
 *
 * <h2>순회 완료와 재시작</h2>
 *
 * <p><b>한 번의 호출은 한 순회의 한 구간이다.</b> 순회는 여러 호출에 걸쳐 이어지고,
 * 끝나면 다음 순회가 처음부터 시작한다.
 *
 * <ul>
 *   <li><b>예산 소진</b> — {@code maxPages} 를 다 쓰고도 마지막 페이지가 꽉 찼으면
 *       {@code nextCursor} 에 위치가 실려 나간다. <b>같은 순회를 이어가려면 그 커서를
 *       {@link #continueAfter} 로 다음 요청에 넘겨야 한다.</b></li>
 *   <li><b>순회 완료</b> — 어떤 페이지가 {@code pageSize} 보다 적게 돌아오면 뒤에 후보가
 *       없다는 뜻이다. {@code nextCursor} 가 <b>비어서</b> 나간다. 이때 <b>다음 순회를
 *       {@link #fromStart} 로 처음부터</b> 시작하면 지나쳤던 실패 후보를 다시 확인한다 —
 *       고쳐졌으면 회수되고, 그대로면 다시 실패로 보고된다. 숨겨지지 않는다.</li>
 * </ul>
 *
 * <h2>★ {@code sweep(int)} 반복만으로는 진행이 보장되지 않는다</h2>
 *
 * <p>{@code sweep(int)} 와 {@link #fromStart} 는 <b>매번 처음부터</b> 조회한다.
 * 따라서 <b>실패 후보가 실행 예산({@code pageSize × maxPages})을 채우면 반복 호출만으로는
 * 그 뒤의 정상 좌석에 끝내 도달하지 못한다</b> — 매 호출이 같은 실패 후보만 다시 읽는다.
 *
 * <p>진행은 <b>예산 소진 시 {@code nextCursor} 를 다음 요청에 전달</b>할 때만 보장된다.
 * 순회가 완료된 뒤에 처음부터 다시 시작하는 것은 <b>실패 후보 재확인</b>을 위한 것이지
 * 진행을 위한 것이 아니다. 이 차이는
 * {@code ExpirySweepProgressTest} 가 예산보다 많은 실패 후보로 고정한다.
 *
 * @param pageSize   후보 SQL 한 번의 {@code LIMIT}
 * @param maxPages   이 실행이 읽을 수 있는 최대 페이지 수
 * @param startAfter 이 커서 뒤부터. 비어 있으면 처음부터
 */
public record ExpirySweepRequest(int pageSize, int maxPages, Optional<ExpiryCursor> startAfter) {

    /**
     * 기본 페이지 예산.
     *
     * <p><b>측정으로 정한 값이 아니다.</b> 실패 후보가 앞을 막는 깊이를 모르는 상태에서
     * "한 실행이 무한히 길어지지 않으면서 앞쪽 실패 블록을 어느 정도 넘어간다" 를 만족하는
     * 작은 값으로 골랐다. 실패 블록이 이보다 깊으면 한 번의 실행으로는 통과하지 못하고,
     * 호출자가 {@code nextCursor} 로 이어가야 한다 — 그 계약이 진행을 보장한다.
     * 실제 손상·drift 비율을 측정하면 조정한다.
     */
    public static final int DEFAULT_MAX_PAGES = 4;

    public ExpirySweepRequest {
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize 는 양수여야 한다: " + pageSize);
        }
        if (maxPages <= 0) {
            throw new IllegalArgumentException("maxPages 는 양수여야 한다: " + maxPages);
        }
        Objects.requireNonNull(startAfter, "startAfter");
    }

    /** 처음부터. 지나쳤던 실패 후보를 다시 확인한다. */
    public static ExpirySweepRequest fromStart(int pageSize) {
        return new ExpirySweepRequest(pageSize, DEFAULT_MAX_PAGES, Optional.empty());
    }

    public static ExpirySweepRequest fromStart(int pageSize, int maxPages) {
        return new ExpirySweepRequest(pageSize, maxPages, Optional.empty());
    }

    /** 예산이 소진된 실행의 {@link ExpirySweepResult#nextCursor} 를 그대로 이어받는다. */
    public ExpirySweepRequest continueAfter(ExpiryCursor cursor) {
        return new ExpirySweepRequest(pageSize, maxPages,
                Optional.of(Objects.requireNonNull(cursor, "cursor")));
    }

    /** 이 실행이 검사할 수 있는 후보 수의 상한. */
    public int candidateBudget() {
        return pageSize * maxPages;
    }
}
