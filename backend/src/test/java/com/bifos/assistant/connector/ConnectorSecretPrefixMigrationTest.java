package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * V39 가 이미 저장된 비밀 앞부분을 모든 행에서 비우는지 본다.
 *
 * <p>테스트 DB 는 엔티티로 스키마를 만들므로, 운영과 같은 Flyway 스키마는 여기서 따로 확인한다. 옛 규칙의 행을 넣으려고
 * V38 까지만 올린 뒤 V39 를 올린다.
 */
class ConnectorSecretPrefixMigrationTest {
    private static final String WITH_PREFIX = "{\"values\":{\"family\":\"x\"},\"secretPrefixes\":{\"token\":\"fab_abcd\"}}";
    private static final String ALREADY_EMPTY = "{\"values\":{},\"secretPrefixes\":{}}";
    private static final String UPDATED_AT = "2026-01-02 03:04:05.000000";
    private String url;

    @BeforeEach
    void setUp() throws SQLException {
        url = "jdbc:h2:mem:connector-prefix-migration-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        migrate("38");
    }

    @Test
    @DisplayName("V39는 앞부분이 있는 행의 secretPrefixes 만 비우고 values 와 updated_at 은 그대로 둔다")
    void v39ClearsSecretPrefixesAndKeepsValuesAndUpdatedAt() throws SQLException {
        insert(901, WITH_PREFIX);

        migrate("39");

        assertThat(fields(901)).isEqualTo("{\"values\":{\"family\":\"x\"},\"secretPrefixes\":{}}");
        assertThat(updatedAt(901)).isEqualTo(Timestamp.valueOf(UPDATED_AT));
    }

    @Test
    @DisplayName("V39는 이미 빈 행을 그대로 둔다")
    void v39LeavesAlreadyEmptyRowUnchanged() throws SQLException {
        insert(901, WITH_PREFIX);
        insert(902, ALREADY_EMPTY);

        migrate("39");

        assertThat(fields(902)).isEqualTo(ALREADY_EMPTY);
        assertThat(updatedAt(902)).isEqualTo(Timestamp.valueOf(UPDATED_AT));
    }

    @Test
    @DisplayName("V39는 secretPrefixes 가 객체가 아닌 값이어도 빈 객체로 바꾼다")
    void v39ReplacesNonObjectSecretPrefixesWithEmptyObject() throws SQLException {
        insert(901, "{\"values\":{},\"secretPrefixes\":\"fab_abcd\"}");
        insert(902, "{\"values\":{},\"secretPrefixes\":null}");

        migrate("39");

        assertThat(fields(901)).isEqualTo(ALREADY_EMPTY);
        assertThat(fields(902)).isEqualTo(ALREADY_EMPTY);
    }

    @Test
    @DisplayName("V39는 연결이 하나도 없어도 끝난다")
    void v39SucceedsWithNoRows() throws SQLException {
        migrate("39");

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM connector_connection")) {
            row.next();
            assertThat(row.getLong(1)).isZero();
        }
    }

    @Test
    @DisplayName("V39는 JSON 으로 읽지 못하는 행에서 행 번호만 알리고 멈추며 어느 행도 고치지 않는다")
    void v39StopsOnUnreadableRowWithoutLeakingColumnText() throws SQLException {
        insert(901, WITH_PREFIX);
        insert(902, "fab_broken{");
        long brokenId = id(902);

        assertThatThrownBy(() -> migrate("39"))
                .hasStackTraceContaining("id " + brokenId + " 행")
                .satisfies(ex -> {
                    for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
                        assertThat(String.valueOf(cause.getMessage())).doesNotContain("fab_");
                    }
                });

        assertThat(fields(901)).isEqualTo(WITH_PREFIX);
        assertThat(fields(902)).isEqualTo("fab_broken{");
    }

    private void migrate(String target) {
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .target(target)
                .load()
                .migrate();
    }

    /** 사용자와 그 사용자의 커넥터 에이전트를 만들고 연결 한 행을 넣는다. */
    private void insert(int id, String fields) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO app_user (id, email, display_name, group_id, role, created_at)"
                    + " VALUES (" + id + ", 'u" + id + "@example.com', 'u" + id + "', 1, 'MEMBER',"
                    + " CURRENT_TIMESTAMP(6))");
            statement.executeUpdate("INSERT INTO agent (id, code, name, hermes_profile, api_base_url, cost_mode,"
                    + " credential_scope, visibility, owner_user_id, enabled, created_at, connector_managed)"
                    + " VALUES (" + id + ", 'c" + id + "', 'n', 'p" + id + "', 'http://localhost',"
                    + " 'SUBSCRIPTION', 'SHARED_HOUSEHOLD', 'PRIVATE', " + id + ", FALSE,"
                    + " CURRENT_TIMESTAMP(6), TRUE)");
            statement.executeUpdate("INSERT INTO connector_connection (user_id, connector_id, agent_id, status, fields,"
                    + " restart_required, desired_enabled, checked_at, created_at, updated_at) VALUES ("
                    + id + ", 'demo-notes', " + id + ", 'READY', '" + fields + "', FALSE, TRUE, NULL,"
                    + " TIMESTAMP '" + UPDATED_AT + "', TIMESTAMP '" + UPDATED_AT + "')");
        }
    }

    private String fields(int userId) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement();
                ResultSet row =
                        statement.executeQuery("SELECT fields FROM connector_connection WHERE user_id = " + userId)) {
            assertThat(row.next()).as("사용자 %d 의 연결 행", userId).isTrue();
            return row.getString(1);
        }
    }

    private Timestamp updatedAt(int userId) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery(
                        "SELECT updated_at FROM connector_connection WHERE user_id = " + userId)) {
            assertThat(row.next()).as("사용자 %d 의 연결 행", userId).isTrue();
            return row.getTimestamp(1);
        }
    }

    private long id(int userId) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement();
                ResultSet row =
                        statement.executeQuery("SELECT id FROM connector_connection WHERE user_id = " + userId)) {
            assertThat(row.next()).as("사용자 %d 의 연결 행", userId).isTrue();
            return row.getLong(1);
        }
    }
}
