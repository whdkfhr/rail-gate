package com.railgate.reservation.infra.saleevent;

import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * <b>테스트 전용</b> 스키마와 후보 SQL (Task 2G-E-B1).
 *
 * <h2>운영 테이블이 아니다</h2>
 *
 * <p>운영 마이그레이션(V4)을 쓰기 전에 <b>되돌리기 어려운 두 결정</b>을 실제 MySQL 에서
 * 먼저 확인하려고 만든 것이다. 테스트 소스 트리에만 있고 이름에 {@code task2geb1_} 접두사를
 * 붙여 운영 이름과 구분한다. {@code Task2gQuotaCounter} 와 같은 방식이다.
 *
 * <p><b>운영 {@code seat_inventory} 를 ALTER 하지 않는다.</b> 같은 컨테이너를 공유하는
 * 다른 테스트의 스키마를 건드리면 그 테스트들이 검증하는 것이 달라진다.
 * 그래서 좌석 테이블도 실험용 사본을 따로 만든다.
 *
 * <h2>후보 SQL 을 상수로 두는 이유</h2>
 *
 * <p>테스트가 EXPLAIN 하는 SQL 과 문서에 적는 SQL 이 조용히 어긋나는 것을 막는다.
 * {@code JdbcSeatExpiryRepository.FIND_CANDIDATES_SQL} 을 그대로 EXPLAIN 하는
 * 기존 테스트와 같은 원칙이다.
 */
final class Task2geb1Schema {

    static final String SALE_EVENT = "task2geb1_sale_event";
    static final String TRAIN_SCHEDULE = "task2geb1_train_schedule";
    static final String SEAT_NORMALIZED = "task2geb1_seat_normalized";
    static final String SEAT_DENORMALIZED = "task2geb1_seat_denormalized";

    /** 판매 회차 수. 대안 비교에는 회차 수가 아니라 운행편·좌석 수가 중요하다. */
    static final int SALE_EVENTS = 3;

    /** 작은 규모: 운행편 20 × 좌석 20 = 400석. */
    static final int SMALL_SCHEDULES = 20;
    static final int SMALL_SEATS_PER_SCHEDULE = 20;

    /**
     * 큰 규모: 운행편 2,000 × 좌석 30 = 60,000석.
     *
     * <p>이 크기를 고른 이유는 <b>"전체 크기가 커져도 대상 스캔이 제한되는가"</b> 를 보려면
     * 작은 규모 대비 두 자릿수 배수가 필요하고(400 → 60,000, 150배), 동시에 CI 에서
     * 한 번의 {@code INSERT ... SELECT} 로 몇 백 ms 안에 만들 수 있어야 하기 때문이다.
     * 절대 시간이 아니라 <b>규모 사이의 스캔 행 수 차이</b>가 판단 근거이므로,
     * 이보다 더 키워도 결론이 달라지지 않는다면 키울 이유가 없다.
     */
    static final int LARGE_SCHEDULES = 2_000;
    static final int LARGE_SEATS_PER_SCHEDULE = 30;

    /** P-2. 한 요청이 잡을 수 있는 좌석 수 상한. 조회 대상 크기의 상한이기도 하다. */
    static final int MAX_SEATS_PER_REQUEST = 4;

    private Task2geb1Schema() {
    }

    // ------------------------------------------------------------------
    // 후보 SQL — 테스트와 문서가 같은 문자열을 본다
    // ------------------------------------------------------------------

    /**
     * Q1. 요청이 {@code scheduleId} 를 이미 알고 있는 경우.
     *
     * <p>두 대안이 완전히 같다. 좌석 테이블을 거치지 않기 때문이다.
     */
    static final String LOOKUP_BY_SCHEDULE_ID = """
            SELECT sale_event_id
              FROM %s
             WHERE id = ?
            """.formatted(TRAIN_SCHEDULE);

    /**
     * Q2. 정규화 — 좌석에서 판매 회차를 조인으로 얻는다.
     *
     * <p>{@code DISTINCT} 인 이유: 선점 경로가 알아야 하는 것은 "이 좌석들의 판매 회차" 이고,
     * 회차가 둘 이상이면 <b>요청 자체를 거부</b>해야 한다 (2G-D §4: 이벤트 간 quota 는 독립).
     * 행 수가 1 이 아니면 교차 요청이다.
     */
    static String normalizedLookupBySeat(int seatCount) {
        return """
                SELECT DISTINCT ts.sale_event_id
                  FROM %s s
                  JOIN %s ts ON ts.id = s.schedule_id
                 WHERE s.id IN (%s)
                """.formatted(SEAT_NORMALIZED, TRAIN_SCHEDULE, placeholders(seatCount));
    }

    /** Q3. 비정규화 — 좌석 행이 판매 회차를 직접 갖는다. 비교를 위해 형태를 같게 둔다. */
    static String denormalizedLookupBySeat(int seatCount) {
        return """
                SELECT DISTINCT sale_event_id
                  FROM %s
                 WHERE id IN (%s)
                """.formatted(SEAT_DENORMALIZED, placeholders(seatCount));
    }

    /**
     * 비정규화를 택했을 때 필요한 drift 검사.
     *
     * <p>비정규화의 비용은 조회가 아니라 <b>이 쿼리가 존재해야 한다는 사실 자체</b>다.
     * 좌석 행의 값과 운행편의 값이 어긋난 행을 주기적으로 찾아야 한다.
     */
    static final String DRIFT_CHECK = """
            SELECT s.id, s.sale_event_id AS seat_value, ts.sale_event_id AS schedule_value
              FROM %s s
              JOIN %s ts ON ts.id = s.schedule_id
             WHERE s.sale_event_id <> ts.sale_event_id
            """.formatted(SEAT_DENORMALIZED, TRAIN_SCHEDULE);

    private static String placeholders(int count) {
        return IntStream.range(0, count).mapToObj(i -> "?").collect(Collectors.joining(", "));
    }

    // ------------------------------------------------------------------
    // 스키마
    // ------------------------------------------------------------------

    /**
     * 실험 테이블을 만든다.
     *
     * <p><b>두 대안에 같은 조건을 준다.</b> 양쪽 좌석 테이블은 {@code seat_inventory}(V1) 와
     * 같은 PK·UNIQUE 를 갖고, 비정규화 쪽에는 <b>{@code sale_event_id} 인덱스를 추가로</b> 준다.
     * 한쪽에만 인덱스를 빠뜨리면 비교가 성립하지 않는다.
     */
    static void create(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    id        BIGINT       NOT NULL,
                    name      VARCHAR(100) NOT NULL,
                    opens_at  DATETIME(3)  NOT NULL,
                    closes_at DATETIME(3)  NULL,
                    status    VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                    PRIMARY KEY (id),
                    CONSTRAINT chk_%s_status CHECK (status IN ('SCHEDULED', 'OPEN', 'CLOSED'))
                ) ENGINE = InnoDB
                """.formatted(SALE_EVENT, SALE_EVENT));

        // 2G-D §5 초안 그대로. sale_event_id 는 NOT NULL 이고 인덱스를 갖는다.
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    id            BIGINT NOT NULL,
                    sale_event_id BIGINT NOT NULL,
                    PRIMARY KEY (id),
                    KEY idx_%s_sale_event (sale_event_id)
                ) ENGINE = InnoDB
                """.formatted(TRAIN_SCHEDULE, TRAIN_SCHEDULE));

        // 정규화 좌석: seat_inventory(V1) 의 키 구성을 그대로 옮긴다.
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    id          BIGINT      NOT NULL,
                    schedule_id BIGINT      NOT NULL,
                    seat_no     VARCHAR(10) NOT NULL,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_%s_seat (schedule_id, seat_no)
                ) ENGINE = InnoDB
                """.formatted(SEAT_NORMALIZED, SEAT_NORMALIZED));

        // 비정규화 좌석: 위와 같고 sale_event_id 컬럼과 그 인덱스가 더 있다.
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    id            BIGINT      NOT NULL,
                    schedule_id   BIGINT      NOT NULL,
                    seat_no       VARCHAR(10) NOT NULL,
                    sale_event_id BIGINT      NOT NULL,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_%s_seat (schedule_id, seat_no),
                    KEY idx_%s_sale_event (sale_event_id)
                ) ENGINE = InnoDB
                """.formatted(SEAT_DENORMALIZED, SEAT_DENORMALIZED, SEAT_DENORMALIZED));
    }

    static void drop(JdbcTemplate jdbc) {
        jdbc.execute("DROP TABLE IF EXISTS " + SEAT_DENORMALIZED);
        jdbc.execute("DROP TABLE IF EXISTS " + SEAT_NORMALIZED);
        jdbc.execute("DROP TABLE IF EXISTS " + TRAIN_SCHEDULE);
        jdbc.execute("DROP TABLE IF EXISTS " + SALE_EVENT);
    }

    // ------------------------------------------------------------------
    // 데이터 생성
    // ------------------------------------------------------------------

    /**
     * 0..9999 를 만드는 시퀀스 CTE.
     *
     * <p>재귀 깊이를 10 으로 두고 자리수를 교차 결합한다. 깊이 2,000 짜리 재귀는
     * {@code cte_max_recursion_depth}(기본 1000) 를 넘어 세션 변수 조정이 필요한데,
     * 커넥션 풀에서는 그 세션이 이 문장과 같은 커넥션이라는 보장이 없다.
     */
    private static final String SEQUENCE_CTE = """
            WITH RECURSIVE digit(d) AS (
                SELECT 0 UNION ALL SELECT d + 1 FROM digit WHERE d < 9
            ),
            seq(n) AS (
                SELECT a.d * 1000 + b.d * 100 + c.d * 10 + e.d
                  FROM digit a, digit b, digit c, digit e
            )
            """;

    /**
     * 주어진 규모로 두 대안을 <b>같은 데이터</b>로 채운다.
     *
     * <p>운행편 id 는 {@code 1..schedules}, 좌석 id 는
     * {@code (scheduleId - 1) * seatsPerSchedule + 좌석순번} 으로 결정적으로 배정한다.
     * 그래서 <b>좌석 id 1..4 는 어느 규모에서든 운행편 1 에 속한다</b> —
     * 규모만 바꿔 같은 조회를 반복할 수 있다.
     *
     * <p>판매 회차 배정은 {@code 1 + (scheduleId % SALE_EVENTS)} 다. 한 회차에 운행편이
     * 몰리거나 흩어지게 조작하지 않는다.
     */
    static void seed(JdbcTemplate jdbc, int schedules, int seatsPerSchedule) {
        truncate(jdbc);

        for (int i = 1; i <= SALE_EVENTS; i++) {
            jdbc.update("INSERT INTO %s (id, name, opens_at, closes_at, status) VALUES (?, ?, ?, ?, ?)"
                            .formatted(SALE_EVENT),
                    saleEventId(i), "판매 회차 " + i, "2026-08-01 00:00:00.000", null, "OPEN");
        }

        jdbc.update("""
                INSERT INTO %s (id, sale_event_id)
                %s
                SELECT n, %d + (n %% %d)
                  FROM seq
                 WHERE n BETWEEN 1 AND ?
                """.formatted(TRAIN_SCHEDULE, SEQUENCE_CTE, SALE_EVENT_ID_BASE, SALE_EVENTS),
                schedules);

        jdbc.update("""
                INSERT INTO %s (id, schedule_id, seat_no)
                %s
                SELECT (ts.id - 1) * ? + seat.n, ts.id, CONCAT(seat.n, 'A')
                  FROM %s ts
                  JOIN seq seat ON seat.n BETWEEN 1 AND ?
                """.formatted(SEAT_NORMALIZED, SEQUENCE_CTE, TRAIN_SCHEDULE),
                seatsPerSchedule, seatsPerSchedule);

        jdbc.update("""
                INSERT INTO %s (id, schedule_id, seat_no, sale_event_id)
                SELECT s.id, s.schedule_id, s.seat_no, ts.sale_event_id
                  FROM %s s
                  JOIN %s ts ON ts.id = s.schedule_id
                """.formatted(SEAT_DENORMALIZED, SEAT_NORMALIZED, TRAIN_SCHEDULE));

        jdbc.execute("ANALYZE TABLE " + TRAIN_SCHEDULE);
        jdbc.execute("ANALYZE TABLE " + SEAT_NORMALIZED);
        jdbc.execute("ANALYZE TABLE " + SEAT_DENORMALIZED);
    }

    private static final int SALE_EVENT_ID_BASE = 9000;

    static long saleEventId(int index) {
        return SALE_EVENT_ID_BASE + index;
    }

    /**
     * 한 운행편 안의 좌석 id. 규모와 무관하게 결정적이다.
     *
     * <p>같은 열차에서 나란한 좌석을 잡는 <b>가장 흔한 요청 형태</b>다.
     * 조인 대상이 운행편 한 행뿐이라 정규화에 유리한 분포이기도 하다.
     */
    static Object[] seatIdsWithinOneSchedule(int count, int seatsPerSchedule) {
        if (count > seatsPerSchedule) {
            throw new IllegalArgumentException("한 운행편의 좌석 수를 넘을 수 없다");
        }
        return IntStream.rangeClosed(1, count).mapToObj(i -> (Object) (long) i).toArray();
    }

    /**
     * 서로 다른 운행편의 좌석 id.
     *
     * <p><b>정규화에 불리한 분포를 일부러 만든다.</b> 조인 대상이 좌석마다 다른 행이 되어
     * {@code eq_ref} 조회가 좌석 수만큼 서로 다른 행을 찾아간다.
     * 한 운행편 안의 좌석만으로 비교하면 정규화 쪽에 유리하게 기울 수 있다.
     *
     * <p>왕복·다구간 요청이 실제로 이 형태이기도 하다 (2G-D §4).
     */
    static Object[] seatIdsAcrossSchedules(int count, int seatsPerSchedule) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(scheduleId -> (Object) ((long) (scheduleId - 1) * seatsPerSchedule + 1))
                .toArray();
    }

    static void truncate(JdbcTemplate jdbc) {
        jdbc.execute("DELETE FROM " + SEAT_DENORMALIZED);
        jdbc.execute("DELETE FROM " + SEAT_NORMALIZED);
        jdbc.execute("DELETE FROM " + TRAIN_SCHEDULE);
        jdbc.execute("DELETE FROM " + SALE_EVENT);
    }
}
