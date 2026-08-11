package com.railgate.reservation.infra.seat;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.seat.SeatId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 홀드 단위 자발적 해제 (FR-2.5).
 *
 * <h2>해제의 단위는 좌석이 아니라 홀드다</h2>
 *
 * <p>사용자는 "12A 를 놓겠다" 가 아니라 <b>"내가 잡은 것을 놓겠다"</b> 고 요청하며,
 * 그 홀드에 묶인 1~4석이 함께 풀린다. 좌석 단위로 해제를 노출하면
 * 다좌석 홀드의 일부만 풀린 어중간한 상태가 생긴다.
 *
 * <h2>왜 대상 기준이 아니라 홀드 기준인가</h2>
 *
 * <p>해제 요청은 네트워크를 타고 오며 <b>응답은 유실될 수 있다.</b> 사용자가 재시도할 무렵
 * 그 좌석은 이미 만료되어 다른 사람이 잡았거나, 결제를 마쳐 팔렸을 수 있다.
 * 좌석 ID 만으로 해제하면 그 상황을 구분하지 못하고 <b>남의 홀드를 파괴하거나
 * 확정된 좌석을 되돌린다</b> (CLAUDE.md 규칙 18).
 *
 * <p>그래서 최종 UPDATE 가 네 가지를 함께 검증한다.
 * <ul>
 *   <li>{@code id} — 잠금 순서를 PK 로 고정하기 위해</li>
 *   <li>{@code hold_id} — 그때 그 홀드가 여전히 이 좌석을 잡고 있는가</li>
 *   <li>{@code held_by} — 요청자가 실제 점유자인가</li>
 *   <li>{@code status IN ('HELD','PAYING')} — SOLD 를 구조적으로 배제</li>
 * </ul>
 *
 * <p>한 홀드의 좌석은 모두 같은 {@code hold_id} 를 가지므로,
 * 만료 스위퍼와 달리 {@code (id, hold_id)} 행 생성자 쌍이 필요 없다.
 * 스칼라 조건 하나가 모든 좌석에 동일하게 적용된다.
 *
 * <h2>자연 멱등</h2>
 *
 * <p>같은 요청을 반복하면 두 번째부터는 조건이 불일치해 0 건이 된다.
 *
 * <p><b>0 건의 의미를 과장하지 않는다.</b> 그것은
 * "현재 요청 조건에 맞는 해제 가능한 활성 좌석이 없다" 는 사실 하나이며,
 * <b>자연 멱등 계약상 성공 no-op 으로 처리한다</b> (규칙 18).
 * 원인은 여럿이고 구분되지 않는다 — 이미 해제됐거나, 존재하지 않는 홀드거나,
 * 다른 사용자의 홀드거나, 이미 SOLD 이거나, 조건에 맞는 행이 없는 경우다.
 * 따라서 <b>0 건이 좌석의 현재 상태가 AVAILABLE 임을 증명하지 않고,
 * 요청자가 정당한 소유자임을 뜻하지도 않는다.</b>
 *
 * <p>앱 계층의 해제 정책은 이를 204 로 매핑할 수 있다 (규칙 18). 아직 그 계층이 없다.
 * 멱등키 인프라 없이도 성립하는 성질이다.
 *
 * <h2>후보 조회와 최종 CAS 의 분리</h2>
 *
 * <p>홀드로 좌석을 찾는 조건({@code hold_id}, {@code held_by})과 잠금 순서를 정하는
 * 기준(PK)이 다르다. 홀드 인덱스 순서로 곧장 잠그면 확정·만료 경로(PK 순서)와 교차해
 * 데드락이 날 수 있다 (규칙 2). 그래서 후보를 먼저 읽고 PK 오름차순으로 정렬한 뒤 갱신한다.
 *
 * <p>후보는 <b>조회 시점의 스냅샷일 뿐 현재 상태의 진실이 아니다.</b>
 * 그 사이 확정·만료·재선점이 일어날 수 있으므로 최종 UPDATE 가 모든 조건을 다시 검사한다.
 *
 * <h2>전부-또는-전무가 아니다</h2>
 *
 * <p>다좌석 선점(I-9)과 반대다. 후보 하나가 stale 하다고 나머지를 롤백하면
 * 사용자는 아무것도 놓지 못한 채 남는다. 유효한 좌석은 해제하고 stale 한 좌석은
 * {@code affected_rows} 에서 빠진다. 후보 수와 해제 수가 다른 것은 정상이다.
 *
 * <p>그래서 자체 {@code TransactionTemplate} 을 만들지 않는다. 되돌릴 것이 없다.
 * 다만 {@link JdbcTemplate} 은 {@code DataSourceUtils} 를 통해 커넥션을 얻으므로,
 * 같은 {@link DataSource} 위에 활성 트랜잭션이 있으면 <b>그 트랜잭션에 참여한다.</b>
 * 외부에서 롤백하면 해제도 롤백된다. 이 동작은 테스트로 검증했다.
 * 향후 quota 감소와 같은 트랜잭션으로 묶는다면 경계는 애플리케이션 서비스가 소유해야 하며,
 * 그 트랜잭션 안에 외부 I/O 를 넣지 않는다 (규칙 10).
 *
 * <h2>이 클래스가 하지 않는 것</h2>
 *
 * <ul>
 *   <li><b>REST API 와 멱등키 저장소</b> — 저장소 수준 구현이다.
 *       <b>FR-2.5 가 완성된 것이 아니다.</b> 앱 배선은 후속이다.</li>
 *   <li><b>I-12</b> quota 감소 — 반환값(해제된 좌석 수)이 그 연동에 쓰일 값이지만
 *       {@code user_hold_quota} 테이블 자체가 없다.</li>
 *   <li><b>규칙 32</b> 감사 로그, <b>규칙 35</b> 메트릭.</li>
 * </ul>
 */
public class JdbcSeatReleaseRepository {

    /**
     * 후보 조회가 사용하는 인덱스 (V3). 정의는 {@code (hold_id)} 하나다.
     *
     * <p>기존 {@code (held_by, status)} 로도 답은 나오지만, 그 인덱스는 대상 사용자의
     * <b>활성 좌석을 전부 스캔한 뒤</b> {@code hold_id} 로 걸러낸다. 즉 비용이
     * {@code O(홀드 좌석 수)} 가 아니라 {@code O(사용자 활성 좌석 수)} 다.
     *
     * <p>사용자별 활성 좌석의 상한은 I-12 가 강제할 값인데 <b>아직 구현되지 않았다.</b>
     * 따라서 "사용자별 활성 좌석은 항상 적다" 를 전제로 삼을 수 없다.
     *
     * <p>MySQL 8.4, 좌석 100,000행 / 활성 20,000행 / 홀드 5,000개(홀드당 4석) 실측.
     * 두 경우 모두 대상 홀드는 4석이고 다른 것은 그 사용자의 활성 좌석 수뿐이다.
     * <pre>
     *   사용자 활성 좌석 4석      (held_by, status)     4행 스캔 → 4행 반환
     *                            (hold_id)             4행 스캔 → 4행 반환
     *   사용자 활성 좌석 2,000석  (held_by, status) 2,000행 스캔 → 4행 반환  ← 선형 증가
     *                            (hold_id)             4행 스캔 → 4행 반환
     * </pre>
     * 판단 근거는 스캔 행 수와 데이터 증가 시의 복잡도다. 실행 시간은 보조 수치로만 봤다.
     *
     * <p><b>이 인덱스는 탐색용이지 전체 쿼리의 covering index 가 아니다.</b>
     * 후보 조회가 읽는 값은 {@code id} 뿐이고 InnoDB 보조 인덱스에는 PK 가 암묵적으로
     * 포함되므로 {@code id} 를 키 컬럼으로 다시 적을 필요가 없다. 반면 조회는
     * {@code held_by} 와 {@code status} 도 검사하는데 이 인덱스는 그 두 컬럼을 갖지 않는다.
     * 잠금 순서 역시 이 인덱스가 아니라 {@link #release} 의 정렬과
     * {@code FORCE INDEX (PRIMARY)} 가 보장한다.
     */
    static final String HOLD_INDEX = "idx_seat_inventory_hold";

    /**
     * 홀드에 묶인 해제 가능 좌석을 찾는다. 행 잠금을 잡지 않는다.
     *
     * <p>테스트가 이 상수를 그대로 EXPLAIN 한다. 복사한 SQL 과 운영 SQL 이 어긋나는 것을 막는다.
     */
    static final String FIND_SEATS_SQL = """
            SELECT id
              FROM seat_inventory FORCE INDEX (idx_seat_inventory_hold)
             WHERE hold_id = ?
               AND held_by = ?
               AND status  IN ('HELD', 'PAYING')
            """;

    private final JdbcTemplate jdbc;

    public JdbcSeatReleaseRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
    }

    /**
     * 해제 가능한 좌석을 찾는다. 결과는 조회 시점의 스냅샷이며 현재 상태의 진실이 아니다.
     *
     * <p><b>package-private 인 이유.</b> 이 메서드는 {@link #releaseAll} 의 내부 협력 단계이지
     * 외부 진입점이 아니다. 공개하면 호출자가 후보 목록을 임의로 골라
     * {@link #release} 에 넘길 수 있고, 그러면 <b>홀드의 일부만 해제되는 상태</b>가 만들어진다.
     * 해제의 단위는 좌석이 아니라 홀드라는 계약을 API 가시성으로 강제한다.
     */
    List<SeatId> findReleasableSeats(HoldId holdId, UserId userId) {
        Objects.requireNonNull(holdId, "holdId");
        Objects.requireNonNull(userId, "userId");

        return jdbc.query(FIND_SEATS_SQL,
                (rs, rowNum) -> new SeatId(rs.getLong("id")),
                holdId.asString(), userId.value());
    }

    /**
     * 후보 좌석을 해제한다. 각 좌석에 대해 홀드 소유권·요청자·상태를 <b>다시</b> 검사한다.
     *
     * <p><b>package-private 인 이유는 {@link #findReleasableSeats} 와 같다.</b>
     * 임의의 좌석 목록을 받는 이 메서드가 공개되면 홀드 일부만 해제할 수 있게 된다.
     * 외부 호출자는 {@link #releaseAll} 만 쓴다.
     *
     * @return 실제로 해제된 좌석 수. 후보 수보다 적을 수 있으며 그것은 오류가 아니다.
     *         향후 I-12 quota 감소에 쓸 값이며, <b>0 이면 감소도 수행하지 않는다.</b>
     */
    int release(HoldId holdId, UserId userId, List<SeatId> seatIds) {
        Objects.requireNonNull(holdId, "holdId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(seatIds, "seatIds");
        if (seatIds.isEmpty()) {
            return 0;
        }

        // 원본 리스트를 정렬하지 않는다. 호출자의 컬렉션을 바꾸는 것도,
        // List.of(...) 같은 불변 리스트에서 터지는 것도 피해야 한다.
        List<SeatId> lockOrdered = new ArrayList<>(seatIds);
        lockOrdered.sort(Comparator.comparingLong(SeatId::value));

        List<Object> params = new ArrayList<>();
        params.add(holdId.asString());
        params.add(userId.value());
        lockOrdered.forEach(seatId -> params.add(seatId.value()));

        return jdbc.update(releaseSql(lockOrdered.size()), params.toArray());
    }

    /**
     * 홀드에 묶인 좌석을 찾아 곧바로 해제한다. <b>이 클래스의 유일한 외부 진입점이다.</b>
     *
     * <p>후보 조회와 조건부 UPDATE 는 내부 협력 단계이며 package-private 이다.
     * 그래야 "해제의 단위는 홀드" 라는 계약이 API 형태로 강제된다.
     *
     * @return 실제로 해제된 좌석 수.
     *         <b>0 은 "현재 요청 조건에 맞는 해제 가능한 활성 좌석이 없다" 는 뜻이며,
     *         자연 멱등 계약상 성공 no-op 으로 처리한다.</b>
     *         원인은 구분되지 않는다 — 이미 해제됐거나, 존재하지 않는 홀드거나,
     *         다른 사용자의 홀드거나, 이미 SOLD 이거나, 그 밖에 조건에 맞는 행이 없는 경우다.
     *         <b>0 이 좌석이 지금 AVAILABLE 임을 증명하지 않으며 인증 성공을 뜻하지도 않는다.</b>
     */
    public int releaseAll(HoldId holdId, UserId userId) {
        return release(holdId, userId, findReleasableSeats(holdId, userId));
    }

    // ------------------------------------------------------------------

    /**
     * 해제 UPDATE 를 만든다. <b>구조(플레이스홀더 개수)만 만들고 값은 전부 바인딩한다.</b>
     *
     * <p>{@code FORCE INDEX (PRIMARY)} 는 잠금 순서를 위한 것이다. 옵티마이저가
     * {@code idx_seat_inventory_held_by} 같은 다른 인덱스를 고르면 정렬해 넘긴 의미가 사라진다
     * (CLAUDE.md 규칙 3).
     *
     * <p>{@code reservation_id = NULL} 은 방어적 초기화다. HELD/PAYING 에는 원래 없지만,
     * 해제 후 AVAILABLE 의 필드 조합을 확실히 맞춘다.
     */
    static String releaseSql(int seatCount) {
        String placeholders = IntStream.range(0, seatCount)
                .mapToObj(i -> "?")
                .collect(Collectors.joining(", "));

        return """
                UPDATE seat_inventory FORCE INDEX (PRIMARY)
                   SET status         = 'AVAILABLE',
                       hold_id        = NULL,
                       held_by        = NULL,
                       held_at        = NULL,
                       expires_at     = NULL,
                       reservation_id = NULL,
                       version        = version + 1
                 WHERE hold_id = ?
                   AND held_by = ?
                   AND id      IN (%s)
                   AND status  IN ('HELD', 'PAYING')
                """.formatted(placeholders);
    }
}
