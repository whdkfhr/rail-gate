package com.railgate.reservation.infra.quota;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * <b>테스트 전용</b> quota drift 탐지 쿼리 (Task 2G-F 실험 G).
 *
 * <h2>읽기 전용이다</h2>
 *
 * <p>이 클래스는 <b>탐지만 한다.</b> 보정 SQL 을 실행하지 않는다.
 * 좌석과 카운터 중 무엇이 진실의 원천인지 근거 없이 정할 수 없기 때문이다 —
 * 어느 쪽이 틀렸는지는 drift 의 원인에 따라 다르고, 그 원인은 아직 조사되지 않았다.
 *
 * <p>2G-C 가 "drift 탐지·복구 없음: {@code held_seats} 와 실제 활성 좌석을 대조하는 쿼리가 없다"
 * 로 남긴 항목이다. 여기서 그 쿼리를 만든다.
 *
 * <h2>활성 좌석의 정의</h2>
 *
 * <p>{@code status IN ('HELD','PAYING')} 이다. {@code SOLD} 와 {@code AVAILABLE} 은 활성
 * 선점이 아니다 (2G-D §4). 회차는 {@code seat_inventory → train_schedule} 조인으로 얻는다 —
 * 2G-E-B2 가 만든 경로이며 좌석 행에 회차를 복제하지 않는다.
 */
final class Task2gfDriftDetection {

    /** drift 한 건. 어느 방향으로 얼마나 어긋났는지 함께 담는다. */
    record Drift(long saleEventId, long userId, Integer quotaHeldSeats, long activeSeats,
            Kind kind) {

        enum Kind {
            /** 카운터 행은 있는데 활성 좌석이 없다. 감소가 누락됐을 수 있다 */
            QUOTA_WITHOUT_SEATS,
            /** 활성 좌석은 있는데 카운터 행이 없다. 상한 검사가 무력해진다 */
            SEATS_WITHOUT_QUOTA,
            /** 카운터가 실제보다 크다. 그 사용자는 실제보다 적게 잡을 수 있다 */
            QUOTA_GREATER,
            /** 카운터가 실제보다 작다. 상한을 넘겨 잡을 수 있다 */
            QUOTA_LESS
        }

        @Override
        public String toString() {
            return "drift[%s 회차=%d 사용자=%d quota=%s 활성=%d]"
                    .formatted(kind, saleEventId, userId, quotaHeldSeats, activeSeats);
        }
    }

    /**
     * 카운터와 실제 활성 좌석 수를 대조한다.
     *
     * <p><b>{@code FULL OUTER JOIN} 이 없는 MySQL 이라 두 방향을 각각 본다.</b>
     * 한쪽만 보면 "카운터 없이 좌석만 있는" 경우를 놓친다 — 그것이 상한 검사를
     * 완전히 무력화하는 가장 위험한 방향이다.
     */
    private static final String DRIFT_SQL = """
            SELECT q.quota_scope_id AS sale_event_id,
                   q.user_id        AS user_id,
                   q.held_seats     AS quota_held,
                   COALESCE(a.active_seats, 0) AS active_seats
              FROM %s q
              LEFT JOIN (
                    SELECT ts.sale_event_id, s.held_by AS user_id, COUNT(*) AS active_seats
                      FROM seat_inventory s
                      STRAIGHT_JOIN train_schedule ts ON ts.id = s.schedule_id
                     WHERE s.status IN ('HELD', 'PAYING')
                       AND s.held_by IS NOT NULL
                     GROUP BY ts.sale_event_id, s.held_by
                   ) a ON a.sale_event_id = q.quota_scope_id AND a.user_id = q.user_id
             WHERE q.held_seats <> COALESCE(a.active_seats, 0)

            UNION ALL

            SELECT a.sale_event_id, a.user_id, NULL AS quota_held, a.active_seats
              FROM (
                    SELECT ts.sale_event_id, s.held_by AS user_id, COUNT(*) AS active_seats
                      FROM seat_inventory s
                      STRAIGHT_JOIN train_schedule ts ON ts.id = s.schedule_id
                     WHERE s.status IN ('HELD', 'PAYING')
                       AND s.held_by IS NOT NULL
                     GROUP BY ts.sale_event_id, s.held_by
                   ) a
              LEFT JOIN %s q
                     ON q.quota_scope_id = a.sale_event_id AND q.user_id = a.user_id
             WHERE q.user_id IS NULL

             ORDER BY sale_event_id, user_id
            """.formatted(Task2gQuotaCounter.TABLE, Task2gQuotaCounter.TABLE);

    /**
     * 소유자를 알 수 없는 활성 좌석.
     *
     * <p>어느 카운터에도 집계되지 않으므로 위 대조 쿼리가 잡지 못한다. <b>따로 세야 한다.</b>
     */
    private static final String ORPHAN_HOLDER_SQL = """
            SELECT s.id
              FROM seat_inventory s
             WHERE s.status IN ('HELD', 'PAYING')
               AND s.held_by IS NULL
             ORDER BY s.id
            """;

    private Task2gfDriftDetection() {
    }

    static List<Drift> detect(JdbcTemplate jdbc) {
        return jdbc.query(DRIFT_SQL, (rs, rowNum) -> {
            Integer quotaHeld = rs.getObject("quota_held", Integer.class);
            long active = rs.getLong("active_seats");
            long saleEventId = rs.getLong("sale_event_id");
            long userId = rs.getLong("user_id");
            return new Drift(saleEventId, userId, quotaHeld, active, kindOf(quotaHeld, active));
        });
    }

    /** {@code held_by} 가 NULL 인 활성 좌석 id. 위 대조로는 보이지 않는 drift 다. */
    static List<Long> seatsWithoutHolder(JdbcTemplate jdbc) {
        return jdbc.queryForList(ORPHAN_HOLDER_SQL, Long.class);
    }

    private static Drift.Kind kindOf(Integer quotaHeld, long activeSeats) {
        if (quotaHeld == null) {
            return Drift.Kind.SEATS_WITHOUT_QUOTA;
        }
        if (activeSeats == 0) {
            return Drift.Kind.QUOTA_WITHOUT_SEATS;
        }
        return quotaHeld > activeSeats ? Drift.Kind.QUOTA_GREATER : Drift.Kind.QUOTA_LESS;
    }
}
