package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 주문 상한 두 표의 줄을 {@code app_user} 와 {@code connector_action} 의 컬럼으로 옮기고 옛 표를 지우는지 본다
 * (ADR-20261015 / financial-user-limits).
 */
class FinancialLimitsIntoOwnersMigrationTest {
    private Database database;
    private long owner;
    private long untouched;
    private long change;
    private long other;


    @BeforeEach
    void setUp() throws SQLException {
        database = createDatabase();
        migrate("20261015104355");
        seed();
        Flyway.configure()
            .dataSource(database.url(), database.username(), database.password())
            .locations("classpath:db/migration")
            .load()
            .migrate();
    }


    /** 같은 검사를 실제 MySQL 에서 돌리는 하위 클래스가 바꿔 끼운다. */
    Database createDatabase() {
        return new Database(
            "jdbc:h2:mem:financial-limits-into-owners-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    }


    @Test
    @DisplayName("사용자의 상한 줄은 app_user 컬럼으로 옮겨지고, 빈 컬럼과 줄이 없던 사용자는 비어 있다")
    void movesOrderLimitsIntoAppUser() throws SQLException {
        assertThat(userLimits(owner)).containsExactly(null, new BigDecimal("300.500000"), BigDecimal.ZERO.setScale(6), null,
            new BigDecimal("900.000000"), "set");
        assertThat(userLimits(untouched)).containsExactly(null, null, null, null, null, null);
    }


    @Test
    @DisplayName("상한 변경 줄은 승인 줄의 컬럼으로 옮겨지고, 다른 승인 줄은 비어 있다")
    void movesLimitChangeIntoAction() throws SQLException {
        assertThat(actionLimits(change)).containsExactly("ORDER_AMOUNT_USD", new BigDecimal("500.000000"), new BigDecimal("300.500000"));
        assertThat(actionLimits(other)).containsExactly(null, null, null);
    }


    @Test
    @DisplayName("옮긴 뒤 옛 두 표는 없다")
    void dropsOldTables() throws SQLException {
        try (Connection connection = connect(); ResultSet tables = connection.getMetaData().getTables(connection.getCatalog(), null, "%",
            new String[] {"TABLE"})) {
            var names = new ArrayList<String>();
            while (tables.next()) {
                names.add(tables.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
            }
            assertThat(names).contains("app_user", "connector_action").doesNotContain("financial_order_limit", "financial_limit_change");
        }
    }


    private void migrate(String version) {
        Flyway.configure()
            .dataSource(database.url(), database.username(), database.password())
            .locations("classpath:db/migration")
            .target(version)
            .load()
            .migrate();
    }


    private void seed() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            owner = user(statement, "limit-owner@example.com");
            untouched = user(statement, "limit-none@example.com");
            statement.executeUpdate("""
                INSERT INTO financial_order_limit (user_id, order_amount_krw, order_amount_usd, order_quantity, daily_amount_krw,
                    daily_amount_usd, updated_at)
                VALUES (%d, NULL, 300.5, 0, NULL, 900, TIMESTAMP '2026-10-15 01:00:00')
                """.formatted(owner));
            change = action(statement, owner, "11", "order_limit_change", "fos_order_limit_change");
            other = action(statement, owner, "22", "write_note", "mcp__demo__write_note");
            statement.executeUpdate("""
                INSERT INTO financial_limit_change (action_id, user_id, limit_kind, requested_value, previous_value, created_at)
                VALUES (%d, %d, 'ORDER_AMOUNT_USD', 500, 300.5, TIMESTAMP '2026-10-15 01:00:00')
                """.formatted(change, owner));
        }
    }


    private static long user(Statement statement, String email) throws SQLException {
        statement.executeUpdate("""
            INSERT INTO app_user (email, display_name, group_id, role, created_at)
            VALUES ('%s', 'Dad', 1, 'ADMIN', CURRENT_TIMESTAMP(6))
            """.formatted(email));
        return single(statement, "SELECT id FROM app_user WHERE email = '" + email + "'");
    }


    private static long action(Statement statement, long userId, String key, String tool, String hermesTool) throws SQLException {
        statement.executeUpdate("""
            INSERT INTO connector_action (public_id, user_id, connector_id, tool_name, hermes_tool, risk, approval_mode, decision,
                passed, status, origin_execution_id, dedupe_key, args_sha256, expires_at, created_at)
            VALUES (X'%s', %d, 'tossinvest', '%s', '%s', 'WRITE', 'ALWAYS', 'NEEDS_APPROVAL', FALSE, 'PENDING', 0, '%s', 'sha',
                TIMESTAMP '2026-10-16 01:00:00', TIMESTAMP '2026-10-15 01:00:00')
            """.formatted(key.repeat(16), userId, tool, hermesTool, key));
        return single(statement, "SELECT id FROM connector_action WHERE dedupe_key = '" + key + "'");
    }


    private static long single(Statement statement, String sql) throws SQLException {
        try (ResultSet row = statement.executeQuery(sql)) {
            assertThat(row.next()).isTrue();
            return row.getLong(1);
        }
    }


    private List<Object> userLimits(long userId) throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement();
            ResultSet row = statement.executeQuery("""
                SELECT limit_order_amount_krw, limit_order_amount_usd, limit_order_quantity, limit_daily_amount_krw,
                    limit_daily_amount_usd, limit_updated_at
                FROM app_user WHERE id = %d
                """.formatted(userId))) {
            assertThat(row.next()).isTrue();
            var values = new ArrayList<Object>();
            for (int column = 1; column <= 5; column++) {
                values.add(row.getBigDecimal(column));
            }
            values.add(row.getTimestamp(6) == null ? null : "set");
            return values;
        }
    }


    private List<Object> actionLimits(long actionId) throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement();
            ResultSet row = statement.executeQuery(
                "SELECT limit_kind, limit_requested, limit_previous FROM connector_action WHERE id = " + actionId)) {
            assertThat(row.next()).isTrue();
            var values = new ArrayList<Object>();
            values.add(row.getString(1));
            values.add(row.getBigDecimal(2));
            values.add(row.getBigDecimal(3));
            return values;
        }
    }


    private Connection connect() throws SQLException {
        return DriverManager.getConnection(database.url(), database.username(), database.password());
    }


    record Database(String url, String username, String password) {
    }
}
