package com.railgate.reservation.infra.saleevent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * 실행 계획과 <b>실제로 읽은 행 수</b>를 재는 도구 (Task 2G-E-B1).
 *
 * <h2>왜 벽시계 시간이 아닌가</h2>
 *
 * <p>실행 시간은 CI 머신 부하와 버퍼 풀 상태에 좌우된다. 되돌리기 어려운 스키마 결정을
 * 그 수치로 하면 안 된다 (CLAUDE.md 규칙 30·31). 판단 근거는 세 가지다.
 *
 * <ol>
 *   <li><b>access type 과 선택된 인덱스</b> — {@code EXPLAIN}</li>
 *   <li><b>실제로 읽은 행 수</b> — {@code Handler_read_*} 세션 카운터</li>
 *   <li><b>전체 크기를 키웠을 때 그 값이 변하는가</b> — 같은 조회를 두 규모에서 반복</li>
 * </ol>
 *
 * <h2>{@code Handler_read_*} 를 쓰는 이유</h2>
 *
 * <p>{@code EXPLAIN} 의 {@code rows} 는 옵티마이저의 <b>추정</b>이다. 통계에 따라 흔들리고
 * 조인 루프 횟수를 곱해주지도 않는다. {@code Handler_read_*} 는 스토리지 엔진이 실제로
 * 넘긴 행 수의 누적값이라 <b>하드웨어와 무관한 정수</b>다.
 *
 * <p>{@code SHOW SESSION STATUS} 자체도 카운터를 올린다. 그래서 <b>아무것도 하지 않은
 * 구간을 같은 방식으로 한 번 재서 그 값을 빼고</b>, 커넥션 풀이 다른 커넥션을 주지 않도록
 * 세 문장을 <b>하나의 {@link Connection} 에서</b> 실행한다.
 */
final class QueryPlanProbe {

    /** 한 줄의 {@code EXPLAIN} 결과 중 판단에 쓰는 항목만 남긴다. */
    record PlanRow(String table, String type, String key, long rowsEstimate, String extra) {

        boolean isFullScan() {
            return "ALL".equals(type);
        }

        boolean usesFilesort() {
            return extra != null && extra.contains("filesort");
        }

        @Override
        public String toString() {
            return "%s: type=%s key=%s rows=%d extra=%s".formatted(table, type, key, rowsEstimate,
                    extra == null || extra.isBlank() ? "-" : extra);
        }
    }

    private final DataSource dataSource;

    QueryPlanProbe(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    List<PlanRow> explain(String sql, Object... params) {
        List<PlanRow> rows = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = prepare(connection, "EXPLAIN " + sql, params);
                ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                rows.add(new PlanRow(
                        rs.getString("table"),
                        rs.getString("type"),
                        rs.getString("key"),
                        rs.getLong("rows"),
                        rs.getString("Extra")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("EXPLAIN 실패: " + sql, e);
        }
        return rows;
    }

    /** 사람이 읽을 실행 계획. 문서에 그대로 옮기기 위한 것이며 단정에는 쓰지 않는다. */
    String explainAnalyze(String sql, Object... params) {
        StringBuilder text = new StringBuilder();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = prepare(connection, "EXPLAIN ANALYZE " + sql, params);
                ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                text.append(rs.getString(1)).append('\n');
            }
        } catch (SQLException e) {
            throw new IllegalStateException("EXPLAIN ANALYZE 실패: " + sql, e);
        }
        return text.toString();
    }

    /**
     * 이 조회가 스토리지 엔진에서 실제로 읽어간 행 수.
     *
     * <p>측정 노이즈({@code SHOW SESSION STATUS} 자체의 비용)를 같은 커넥션에서 한 번 재서 뺀다.
     */
    long rowsRead(String sql, Object... params) {
        try (Connection connection = dataSource.getConnection()) {
            long noise = delta(connection, () -> {
            });
            long total = delta(connection, () -> execute(connection, sql, params));
            return Math.max(0, total - noise);
        } catch (SQLException e) {
            throw new IllegalStateException("행 수 측정 실패: " + sql, e);
        }
    }

    private long delta(Connection connection, ThrowingRunnable work) throws SQLException {
        Map<String, Long> before = handlerReads(connection);
        work.run();
        Map<String, Long> after = handlerReads(connection);

        long sum = 0;
        for (Map.Entry<String, Long> entry : after.entrySet()) {
            sum += entry.getValue() - before.getOrDefault(entry.getKey(), 0L);
        }
        return sum;
    }

    private static Map<String, Long> handlerReads(Connection connection) throws SQLException {
        Map<String, Long> values = new LinkedHashMap<>();
        try (PreparedStatement statement =
                        connection.prepareStatement("SHOW SESSION STATUS LIKE 'Handler_read%'");
                ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                values.put(rs.getString(1), rs.getLong(2));
            }
        }
        return values;
    }

    private static void execute(Connection connection, String sql, Object... params) {
        try (PreparedStatement statement = prepare(connection, sql, params);
                ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                // 결과를 끝까지 읽어야 실제 스캔이 모두 일어난다.
                rs.getObject(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("실행 실패: " + sql, e);
        }
    }

    private static PreparedStatement prepare(Connection connection, String sql, Object... params)
            throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        for (int i = 0; i < params.length; i++) {
            statement.setObject(i + 1, params[i]);
        }
        return statement;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws SQLException;
    }
}
