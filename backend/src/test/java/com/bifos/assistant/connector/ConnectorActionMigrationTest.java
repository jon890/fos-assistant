package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * V43 가 만든 {@code connector_action} 이 판정 줄을 받고 같은 호출의 둘째 줄을 거절하는지 본다.
 *
 * <p>테스트 DB 는 엔티티로 스키마를 만들므로, 운영과 같은 Flyway 스키마는 여기서 따로 확인한다.
 */
class ConnectorActionMigrationTest {
    private static final int OWNER = 901;
    private static final String KEY_A = "a".repeat(64);
    private static final String KEY_B = "b".repeat(64);

    private String url;

    @BeforeEach
    void setUp() throws SQLException {
        url = "jdbc:h2:mem:connector-action-migration-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .target("43")
                .load()
                .migrate();
        execute("INSERT INTO app_user (id, email, display_name, group_id, role, created_at)" + " VALUES (" + OWNER
                + ", 'u@example.com', 'u', 1, 'MEMBER', CURRENT_TIMESTAMP(6))");
        execute("INSERT INTO agent (id, code, name, hermes_profile, api_base_url, cost_mode,"
                + " credential_scope, visibility, owner_user_id, enabled, created_at, connector_managed,"
                + " connector_attachments)"
                + " VALUES (" + OWNER + ", 'c', 'n', 'p', 'http://localhost', 'SUBSCRIPTION', 'SHARED_HOUSEHOLD',"
                + " 'PRIVATE', " + OWNER + ", TRUE, CURRENT_TIMESTAMP(6), TRUE, FALSE)");
    }

    @Test
    @DisplayName("V43는 승인 상태와 인자 원문과 원래 도구 이름이 빈 판정 줄을 받는다")
    void v42AcceptsDecisionRowWithoutApprovalColumns() throws SQLException {
        execute(row("01", KEY_A, OWNER, OWNER));

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery(
                        "SELECT decision, passed, status, args_json, tool_name, conversation_id FROM connector_action"
                                + " WHERE dedupe_key = '" + KEY_A + "'")) {
            assertThat(row.next()).as("넣은 줄").isTrue();
            assertThat(row.getString("decision")).isEqualTo("NEEDS_APPROVAL");
            assertThat(row.getBoolean("passed")).isTrue();
            assertThat(row.getString("status")).isNull();
            assertThat(row.getString("args_json")).isNull();
            assertThat(row.getString("tool_name")).isNull();
            assertThat(row.getObject("conversation_id")).isNull();
        }
    }

    @Test
    @DisplayName("V43는 dedupe_key 가 겹치는 둘째 줄을 거절한다")
    void v42RejectsSecondRowWithSameDedupeKey() throws SQLException {
        execute(row("01", KEY_A, OWNER, OWNER));

        assertThatThrownBy(() -> execute(row("02", KEY_A, OWNER, OWNER))).isInstanceOf(SQLException.class);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    @DisplayName("V43는 public_id 가 겹치는 둘째 줄을 거절한다")
    void v42RejectsSecondRowWithSamePublicId() throws SQLException {
        execute(row("01", KEY_A, OWNER, OWNER));

        assertThatThrownBy(() -> execute(row("01", KEY_B, OWNER, OWNER))).isInstanceOf(SQLException.class);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    @DisplayName("V43는 없는 사용자나 없는 에이전트를 가리키는 줄을 거절한다")
    void v42RejectsRowOfUnknownUserOrAgent() throws SQLException {
        assertThatThrownBy(() -> execute(row("01", KEY_A, OWNER + 1, OWNER))).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute(row("02", KEY_B, OWNER, OWNER + 1))).isInstanceOf(SQLException.class);
        assertThat(count()).isZero();
    }

    /** 승인 엔진 전의 승인 필요 줄이다. 채우지 않는 칸은 적지 않는다. */
    private static String row(String publicIdLastByte, String dedupeKey, int userId, int agentId) {
        return "INSERT INTO connector_action (public_id, user_id, agent_id, connector_id, hermes_tool, risk,"
                + " approval_mode, decision, passed, origin_execution_id, dedupe_key, args_sha256, created_at)"
                + " VALUES (X'000000000000000000000000000000" + publicIdLastByte + "', " + userId + ", " + agentId
                + ", 'demo-notes', 'mcp__demo__write_note', 'WRITE', 'REQUIRED', 'NEEDS_APPROVAL', TRUE, 1, '"
                + dedupeKey + "', '" + "0".repeat(64) + "', CURRENT_TIMESTAMP(6))";
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private int count() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM connector_action")) {
            row.next();
            return row.getInt(1);
        }
    }
}
