package com.railgate.reservation.infra.seat;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * <b>의도적으로 잘못 만든 만료 구현. 테스트 전용이며 운영 코드에 존재하지 않는다.</b>
 *
 * <p>후보 조회 결과를 <b>현재 상태의 진실로 간주</b>하고 좌석 ID 만으로 UPDATE 한다.
 * CLAUDE.md 규칙 1(조건부 UPDATE)과 규칙 4(만료 UPDATE 에 hold_id 조건 포함)를 모두 어긴다.
 *
 * <pre>
 * SELECT id, hold_id ... WHERE status IN ('HELD','PAYING') AND expires_at &lt;= NOW(3)
 *   ...  ← 창(window). 이 사이에 확정이 끼어든다
 * UPDATE seat_inventory SET status='AVAILABLE' ... WHERE id IN (...)   -- 조건 없음
 * </pre>
 *
 * <p><b>왜 실제 장애에서 가능한 인터리빙인가.</b>
 * 후보 SELECT 는 행 잠금을 잡지 않는다. 스위퍼가 후보를 모으고 UPDATE 를 실행하기까지의
 * 시간 동안 다른 세션은 자유롭게 그 행을 바꿀 수 있다. 특히
 * {@link JdbcSeatPaymentRepository#confirm} 의 조건에는 {@code expires_at} 이 없으므로,
 * <b>이미 만료된 PAYING 좌석도 확정될 수 있다.</b> 즉 스위퍼가 회수하려는 바로 그 좌석이
 * 회수 직전에 팔리는 상황은 예외적인 것이 아니라 정상 경로에서 발생한다.
 *
 * <p>그 결과가 I-11 위반이다. 확정에 성공해 사용자에게 좌석을 판 뒤,
 * 스위퍼가 그 좌석을 다시 판매 가능 상태로 되돌린다.
 *
 * @see JdbcSeatExpiryRepository 올바른 구현
 */
final class NaiveSeatExpiryRepository {

    private final JdbcTemplate jdbc;

    NaiveSeatExpiryRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /**
     * 후보의 좌석 ID 만 믿고 되돌린다.
     * 상태도, 홀드 소유권도, 만료 여부도 다시 확인하지 않는다.
     */
    int expire(List<ExpiryCandidate> candidates) {
        if (candidates.isEmpty()) {
            return 0;
        }
        String placeholders = IntStream.range(0, candidates.size())
                .mapToObj(i -> "?")
                .collect(Collectors.joining(", "));

        Object[] ids = candidates.stream()
                .map(c -> (Object) c.seatId().value())
                .toArray();

        return jdbc.update("""
                UPDATE seat_inventory
                   SET status='AVAILABLE', hold_id=NULL, held_by=NULL, held_at=NULL,
                       expires_at=NULL, reservation_id=NULL, version=version+1
                 WHERE id IN (%s)
                """.formatted(placeholders), ids);
    }
}
