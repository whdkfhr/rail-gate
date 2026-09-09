package com.railgate.reservation.infra.quota;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * <b>테스트 전용</b> — 이 DataSource 로 실행된 SQL 문자열을 기록한다.
 *
 * <h2>왜 행 수 확인으로는 부족한가</h2>
 *
 * <p>"거부됐으니 quota 행이 0" 은 <b>SQL 이 나가지 않았다</b>는 증거가 아니다.
 * 나갔는데 롤백됐어도 0이고, 조건에 안 맞아 0행이 갱신돼도 0이다.
 * 거부가 <b>SQL 앞</b>에서 일어나는지 보려면 실제로 나간 문장을 봐야 한다.
 *
 * <p>{@link Connection} 을 동적 프록시로 감싸 {@code prepareStatement}/{@code createStatement}
 * 에 넘어온 SQL 을 모은다. <b>가드를 우회하려는 것이 아니라 관측용 계측</b>이다.
 *
 * <p>{@link DelegatingDataSource} 를 쓰는 이유: Spring 이 리소스 키를 정규화할 때
 * {@code InfrastructureProxy} 를 벗기는데, 그 인터페이스를 구현하지 <b>않는</b> 이 래퍼는
 * 벗겨지지 않아 <b>자기 자신이 리소스 키</b>가 된다. 그래서 저장소와 트랜잭션 관리자에
 * <b>같은 래퍼 인스턴스</b>를 줘야 정상 참여가 된다 — 테스트가 그렇게 배선한다.
 */
final class RecordingDataSource extends DelegatingDataSource {

    private final List<String> executedSql = new CopyOnWriteArrayList<>();

    RecordingDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return record(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return record(super.getConnection(username, password));
    }

    private Connection record(Connection target) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                new RecordingHandler(target));
    }

    /** 지금까지 이 DataSource 로 나간 SQL. */
    List<String> executedSql() {
        return List.copyOf(executedSql);
    }

    /** 관측 구간을 시작한다. 사전 바인딩용 SELECT 를 결과에서 빼기 위해 쓴다. */
    void clear() {
        executedSql.clear();
    }

    /** 지금까지 나간 SQL 중 이 테이블을 건드린 것. */
    List<String> statementsTouching(String table) {
        return executedSql.stream()
                .filter(sql -> sql.toLowerCase().contains(table.toLowerCase()))
                .toList();
    }

    private final class RecordingHandler implements InvocationHandler {

        private final Connection target;

        private RecordingHandler(Connection target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ((name.equals("prepareStatement") || name.equals("prepareCall")
                    || name.equals("nativeSQL"))
                    && args != null && args.length > 0 && args[0] instanceof String sql) {
                executedSql.add(sql);
            }
            try {
                Object result = method.invoke(target, args);
                // Statement.execute(sql) 계열도 잡으려면 Statement 까지 감싸야 하지만,
                // 이 저장소는 전부 PreparedStatement 경로다. 필요 이상으로 넓히지 않는다.
                return result;
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }
}
