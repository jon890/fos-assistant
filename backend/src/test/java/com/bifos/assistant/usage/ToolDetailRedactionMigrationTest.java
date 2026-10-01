package com.bifos.assistant.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ToolDetailRedactionMigrationTest {
    private static final String FIRST_ID = "12345678-1234-5678-9012-123456789abc";
    private static final String SECOND_ID = "abcdef01-1234-5678-9012-123456789abc";

    @Test
    @DisplayName("기존 도구 내용은 실행마다 같은 UUID 번호를 쓰고 연결용 내용은 전체를 가린다")
    void redactsExistingToolRowsAndKeepsNonToolRowsAndMetadata() throws SQLException {
        String url = "jdbc:h2:mem:tool-redaction-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        migrate(url, "40");
        seedExecutions(url);
        insertEvent(url, 1, 801, 1, "TOOL_STARTED", FIRST_ID + " " + SECOND_ID);
        insertEvent(url, 2, 801, 2, "TOOL_COMPLETED", SECOND_ID + " " + FIRST_ID + " Bearer tiny-secret");
        insertEvent(url, 3, 801, 3, "SUBAGENT_STARTED", FIRST_ID);
        insertEvent(url, 4, 802, 1, "TOOL_STARTED", "short private value");
        insertEvent(url, 5, 803, 1, "TOOL_STARTED", "short private value without agent id");
        insertEvent(url, 6, 804, 1, "TOOL_STARTED", SECOND_ID);
        insertEvent(url, 7, 804, 2, "TOOL_COMPLETED", "금액 12,000원, 2026-10-01, id=abc-12");
        insertEvent(url, 8, 804, 3, "TOOL_STARTED", null);
        insertEvent(url, 9, 804, 4, "TOOL_STARTED", "{\"token\":\"tiny-secret\",\"price\":12000}");
        insertEvent(url, 10, 9999, 1, "TOOL_STARTED", "Bearer orphan-secret");

        migrate(url, "41");

        Map<Long, String> details = new LinkedHashMap<>();
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement query = connection.createStatement();
                ResultSet rows = query.executeQuery(
                        "SELECT id, detail, tool_name, duration_ms, failed FROM execution_event ORDER BY id")) {
            while (rows.next()) {
                details.put(rows.getLong("id"), rows.getString("detail"));
                assertThat(rows.getString("tool_name")).isEqualTo("test-tool");
                assertThat(rows.getLong("duration_ms")).isEqualTo(100);
                assertThat(rows.getBoolean("failed")).isFalse();
            }
        }
        assertThat(details)
                .containsEntry(1L, "[항목 1] [항목 2]")
                .containsEntry(2L, "[항목 2] [항목 1] [가림]")
                .containsEntry(3L, FIRST_ID)
                .containsEntry(4L, "[연결 도구 내용 가림]")
                .containsEntry(5L, "[연결 도구 내용 가림]")
                .containsEntry(6L, "[항목 1]")
                .containsEntry(7L, "금액 12,000원, 2026-10-01, id=abc-12")
                .containsEntry(8L, null)
                .containsEntry(9L, "{\"token\":\"[가림]\",\"price\":12000}")
                .containsEntry(10L, "[가림]");
    }

    private static void migrate(String url, String version) {
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .target(version)
                .load()
                .migrate();
    }

    private static void seedExecutions(String url) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO agent (id, code, name, hermes_profile, api_base_url, cost_mode,
                        credential_scope, visibility, enabled, created_at, connector_managed)
                    VALUES (901, 'plain', '일반', 'plain-profile', 'http://localhost', 'SUBSCRIPTION',
                        'SHARED_HOUSEHOLD', 'PRIVATE', TRUE, CURRENT_TIMESTAMP(6), FALSE),
                        (902, 'connector', '연결', 'connector-profile', 'http://localhost', 'SUBSCRIPTION',
                        'SHARED_HOUSEHOLD', 'PRIVATE', TRUE, CURRENT_TIMESTAMP(6), TRUE)
                    """);
            statement.executeUpdate("""
                    INSERT INTO agent_execution (id, user_id, agent_id, profile_name, cost_mode, status, started_at)
                    VALUES (801, 1, 901, 'plain-profile', 'SUBSCRIPTION', 'SUCCEEDED', CURRENT_TIMESTAMP(6)),
                        (802, 1, 902, 'connector-profile', 'SUBSCRIPTION', 'SUCCEEDED', CURRENT_TIMESTAMP(6)),
                        (803, 1, NULL, 'connector-profile', 'SUBSCRIPTION', 'SUCCEEDED', CURRENT_TIMESTAMP(6)),
                        (804, 1, 901, 'plain-profile', 'SUBSCRIPTION', 'SUCCEEDED', CURRENT_TIMESTAMP(6))
                    """);
        }
    }

    private static void insertEvent(String url, long id, long executionId, int sequence, String type, String detail)
            throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO execution_event (id, execution_id, sequence, event_type,
                            tool_name, detail, duration_ms, failed, occurred_at)
                        VALUES (?, ?, ?, ?, 'test-tool', ?, 100, FALSE, CURRENT_TIMESTAMP(6))
                        """)) {
            insert.setLong(1, id);
            insert.setLong(2, executionId);
            insert.setInt(3, sequence);
            insert.setString(4, type);
            insert.setString(5, detail);
            insert.executeUpdate();
        }
    }
}
