package com.railgate.reservation.infra.quota;

import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * <b>테스트 전용</b> quota 카운터 후보 구현 (Task 2G-A).
 *
 * <h2>운영 코드가 아니다</h2>
 *
 * <p>I-12 를 운영에 넣기 전에 <b>경쟁 조건과 SQL 형태를 먼저 검증</b>하려고 만든 것이다.
 * 테스트 소스 트리에만 있고, 테이블도 {@code task2g_} 접두사를 붙여 운영 이름과 구분한다.
 * 운영 마이그레이션·저장소·서비스는 이 Task 에서 만들지 않는다.
 *
 * <h2>왜 집계가 아니라 카운터 행인가</h2>
 *
 * <p>{@code SELECT COUNT(*) FROM seat_inventory WHERE held_by = ?} 로 검증하면
 * <b>다른 트랜잭션의 미커밋 홀드가 보이지 않는다.</b> 같은 사용자가 3석 요청 2개를 동시에
 * 보내면 둘 다 "내 것 0석" 만 보고 통과해 6석을 점유한다 (CLAUDE.md 규칙 8).
 * {@code UserHoldQuotaRaceReproductionTest} 가 그것을 결정적으로 재현한다.
 *
 * <p>카운터 행은 <b>검사와 증가를 한 문장으로</b> 만든다. 같은 사용자의 요청끼리만
 * 그 행에서 직렬화되므로 다른 사용자에게는 영향이 없다.
 *
 * <h2>범위 키를 {@code quota_scope_id} 로 둔 이유</h2>
 *
 * <p>INVARIANTS.md 예시는 {@code event_id} 를 쓰지만 <b>reservation 쪽에 event 모델이 아직 없다</b>
 * ({@code seat_inventory} 는 {@code schedule_id} 만 가진다). 근거 없이 둘을 동일시하지 않기 위해
 * 실험에서는 중립적인 이름을 쓴다. 결정은 실험 문서 §9 에 정리했다.
 */
final class Task2gQuotaCounter {

    /** P-2. 1인당 동시 선점 상한. */
    static final int MAX_SEATS = 4;

    static final String TABLE = "task2g_user_hold_quota";

    /**
     * 카운터 행을 원자적으로 확보한다.
     *
     * <p><b>SELECT 후 INSERT 를 쓰지 않는다.</b> 확인과 삽입 사이가 열려 있는 TOCTOU 이며,
     * 동시 첫 요청에서 두 트랜잭션이 모두 "행이 없다" 를 보고 각자 INSERT 하면 중복 키 경쟁이 된다.
     *
     * <p><b>중복 키 예외를 정상적인 quota 흐름 제어로 쓰지 않는다.</b> 예외를 잡아 분기하려면
     * 드라이버·번역기가 그 오류를 어떤 타입으로 올리는지에 계약이 묶이고, 호출부마다 그 처리를
     * 반복해야 한다. 행 확보 자체를 원자적으로 만들면 그 분기가 사라진다.
     *
     * <p>{@code ON DUPLICATE KEY UPDATE held_seats = held_seats} 는 <b>값을 바꾸지 않는</b>
     * no-op 갱신이다. 행이 없으면 만들고, 있으면 그대로 두면서 그 행의 잠금을 얻는다.
     *
     * <p>{@code INSERT IGNORE} 를 쓰지 않는 이유는 이 클래스의 테스트가 실측으로 보여준다 —
     * 중복 키뿐 아니라 <b>CHECK 위반 같은 다른 오류까지 경고로 낮춰 삼킨다.</b>
     */
    private static final String ENSURE_ROW_SQL = """
            INSERT INTO %s (user_id, quota_scope_id, held_seats)
            VALUES (?, ?, 0)
            ON DUPLICATE KEY UPDATE held_seats = held_seats
            """.formatted(TABLE);

    /**
     * 검사와 증가를 한 문장으로 수행한다 (CLAUDE.md 규칙 8).
     *
     * <p>{@code affected_rows = 1} 이면 확보 성공, {@code 0} 이면 상한 초과다.
     * 조건이 WHERE 절 안에 있으므로 읽고 판단하는 구간이 존재하지 않는다.
     */
    private static final String TRY_ACQUIRE_SQL = """
            UPDATE %s
               SET held_seats = held_seats + ?
             WHERE user_id        = ?
               AND quota_scope_id = ?
               AND held_seats + ? <= %d
            """.formatted(TABLE, MAX_SEATS);

    /**
     * 감소. <b>요청 좌석 수가 아니라 실제로 변경된 좌석 수만큼</b> 줄여야 한다.
     *
     * <p>{@code held_seats - ? >= 0} 조건은 음수 방어다. 감소가 중복 호출되거나
     * 감소량이 과대 계산되면 카운터가 음수가 되어 이후 상한 검사가 무의미해진다.
     */
    private static final String RELEASE_SQL = """
            UPDATE %s
               SET held_seats = held_seats - ?
             WHERE user_id        = ?
               AND quota_scope_id = ?
               AND held_seats - ? >= 0
            """.formatted(TABLE);

    private final JdbcTemplate jdbc;

    Task2gQuotaCounter(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    void ensureRow(long userId, long scopeId) {
        jdbc.update(ENSURE_ROW_SQL, userId, scopeId);
    }

    /** @return 확보 성공 여부. {@code false} 는 상한 초과이며 오류가 아니다. */
    boolean tryAcquire(long userId, long scopeId, int seats) {
        return jdbc.update(TRY_ACQUIRE_SQL, seats, userId, scopeId, seats) == 1;
    }

    /** @return 실제로 감소했는지. {@code false} 면 음수가 될 요청이라 거부됐다. */
    boolean release(long userId, long scopeId, int seats) {
        return jdbc.update(RELEASE_SQL, seats, userId, scopeId, seats) == 1;
    }

    Integer heldSeats(long userId, long scopeId) {
        return jdbc.queryForObject(
                "SELECT held_seats FROM " + TABLE + " WHERE user_id = ? AND quota_scope_id = ?",
                Integer.class, userId, scopeId);
    }

    long rowCount(long userId, long scopeId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + TABLE + " WHERE user_id = ? AND quota_scope_id = ?",
                Long.class, userId, scopeId);
    }

    // ------------------------------------------------------------------
    // 스키마. DDL 은 MySQL 에서 암묵적 커밋을 일으키므로 트랜잭션 밖에서만 실행한다.

    static void createTable(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    user_id        BIGINT      NOT NULL,
                    quota_scope_id BIGINT      NOT NULL,
                    held_seats     SMALLINT    NOT NULL DEFAULT 0,
                    updated_at     DATETIME(3) NOT NULL
                                   DEFAULT CURRENT_TIMESTAMP(3)
                                   ON UPDATE CURRENT_TIMESTAMP(3),
                    PRIMARY KEY (user_id, quota_scope_id),
                    CONSTRAINT ck_task2g_quota_range
                        CHECK (held_seats >= 0 AND held_seats <= %d)
                )
                """.formatted(TABLE, MAX_SEATS));
    }

    static void truncate(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM " + TABLE);
    }
}
