package com.bifos.assistant.testsupport;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * bind값 없이 검색 SELECT 구조와 실제 JDBC timeout을 관측하는 공통 검사 도우미다.
 * capture를 켠 검사 스레드에서만 연결을 감싸며, 비활성 상태에서는 SQL과 연결을 그대로 통과시킨다.
 */
public class MemorySearchSqlProbe implements StatementInspector {
    private static final ThreadLocal<MemorySearchSqlProbe> CURRENT = ThreadLocal.withInitial(MemorySearchSqlProbe::new);
    private final List<String> selects = new ArrayList<>();
    private boolean capture;
    private boolean readOnly;
    private int timeout;
    private boolean delay;
    private boolean invalid;

    private static MemorySearchSqlProbe state() {
        return CURRENT.get();
    }

    public static void reset() {
        CURRENT.remove();
    }

    public static void capture() {
        state().capture = true;
    }

    public static void delay() {
        state().delay = true;
    }

    public static void invalidate() {
        state().invalid = true;
    }

    private static boolean enabled() {
        return state().capture;
    }

    public static List<String> selects() {
        return List.copyOf(state().selects);
    }

    public static int timeout() {
        return state().timeout;
    }

    public static boolean readOnly() {
        return state().readOnly;
    }

    @Override
    public String inspect(String sql) {
        if (!enabled() || !sql.startsWith("select ") || !sql.contains(" from memory ")) {
            return sql;
        }
        state().selects.add(sql);
        state().readOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        if (state().delay) {
            return sql.replace(" order by ", " and sleep(5)=0 order by ");
        }
        return state().invalid ? sql.replace(".title", ".nonexistent_search_column") : sql;
    }

    static BeanPostProcessor observeStatements() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (!(bean instanceof DataSource source)) {
                    return bean;
                }
                return new DelegatingDataSource(source) {
                    @Override
                    public Connection getConnection() throws SQLException {
                        Connection connection = super.getConnection();
                        return enabled() ? wrapConnection(connection) : connection;
                    }

                    @Override
                    public Connection getConnection(String username, String password) throws SQLException {
                        Connection connection = super.getConnection(username, password);
                        return enabled() ? wrapConnection(connection) : connection;
                    }
                };
            }
        };
    }

    private static Connection wrapConnection(Connection delegate) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    Object value = invoke(delegate, method, args);
                    if (value instanceof PreparedStatement statement && "prepareStatement".equals(method.getName())) {
                        return Proxy.newProxyInstance(
                                PreparedStatement.class.getClassLoader(),
                                new Class<?>[] {PreparedStatement.class},
                                (statementProxy, statementMethod, statementArgs) -> {
                                    if ("executeQuery".equals(statementMethod.getName()) && enabled()) {
                                        state().timeout = statement.getQueryTimeout();
                                    }
                                    return invoke(statement, statementMethod, statementArgs);
                                });
                    }
                    return value;
                });
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException ex) {
            throw ex.getCause();
        }
    }
}
