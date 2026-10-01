package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;

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
 * V45 가 그때까지 {@code READY} 이던 연결만 내리는지 본다.
 *
 * <p>테스트 DB 는 엔티티로 스키마를 만들므로, 운영과 같은 Flyway 스키마는 여기서 따로 확인한다. 앞선 상태의 행을
 * 넣으려고 V45 앞의 마이그레이션까지만 올린 뒤 V45 를 올린다.
 */
class ConnectorPolicyMigrationTest {
    private static final int READY = 901;
    private static final int PENDING = 902;
    private static final int DISCONNECTED = 903;
    /** 연결이 없는 보통 에이전트다. */
    private static final int PLAIN = 904;
    /** READY 가 아닌 연결인데 에이전트가 켜져 있고 사진을 받는 경우다. V45 가 연결의 상태를 보고 고르는지 본다. */
    private static final int PENDING_ON = 905;

    private String url;

    @BeforeEach
    void setUp() throws SQLException {
        url = "jdbc:h2:mem:connector-policy-migration-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        migrate("44");
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            for (int id = READY; id <= PENDING_ON; id++) {
                boolean on = id == READY || id == PLAIN || id == PENDING_ON;
                statement.executeUpdate("INSERT INTO app_user (id, email, display_name, group_id, role, created_at)"
                        + " VALUES (" + id + ", 'u" + id + "@example.com', 'u" + id + "', 1, 'MEMBER',"
                        + " CURRENT_TIMESTAMP(6))");
                statement.executeUpdate("INSERT INTO agent (id, code, name, hermes_profile, api_base_url, cost_mode,"
                        + " credential_scope, visibility, owner_user_id, enabled, created_at, connector_managed,"
                        + " connector_attachments)"
                        + " VALUES (" + id + ", 'c" + id + "', 'n', 'p" + id + "', 'http://localhost',"
                        + " 'SUBSCRIPTION', 'SHARED_HOUSEHOLD', 'PRIVATE', " + id + ", " + on + ","
                        + " CURRENT_TIMESTAMP(6), " + (id != PLAIN) + ", "
                        + (id == READY || id == PENDING_ON) + ")");
            }
            // READY 는 재시작 대기가 풀린 상태다. PENDING 은 재시작 대기로 두어 그 값이 남는지 본다.
            statement.executeUpdate(row(READY, "READY", false, true));
            statement.executeUpdate(row(PENDING, "PENDING", true, true));
            statement.executeUpdate(row(DISCONNECTED, "DISCONNECTED", false, false));
            statement.executeUpdate(row(PENDING_ON, "PENDING", false, true));
        }
        migrate("45");
    }

    @Test
    @DisplayName("V45는 READY 이던 연결을 PENDING 으로 내리고 재시작 대기와 활성화 후보는 그대로 둔다")
    void v41LowersReadyConnectionToPendingWithoutTouchingRestart() throws SQLException {
        assertThat(connection(READY)).isEqualTo(new Row("PENDING", false, true, 0));
    }

    @Test
    @DisplayName("V45는 READY 가 아니던 연결의 상태와 재시작 대기를 바꾸지 않는다")
    void v41LeavesOtherConnectionsAsTheyWere() throws SQLException {
        assertThat(connection(PENDING)).isEqualTo(new Row("PENDING", true, true, 0));
        assertThat(connection(DISCONNECTED)).isEqualTo(new Row("DISCONNECTED", false, false, 0));
    }

    @Test
    @DisplayName("V45는 READY 이던 연결의 에이전트만 끄고 사진 받기를 내린다")
    void v41DisablesOnlyAgentOfReadyConnection() throws SQLException {
        assertThat(agent(READY)).isEqualTo(new AgentRow(false, false));
        assertThat(agent(PENDING)).isEqualTo(new AgentRow(false, false));
        assertThat(agent(DISCONNECTED)).isEqualTo(new AgentRow(false, false));
        // 연결이 없는 에이전트는 켜진 채로 남는다.
        assertThat(agent(PLAIN)).isEqualTo(new AgentRow(true, false));
    }

    @Test
    @DisplayName("V45는 READY 가 아니던 연결의 에이전트가 켜져 있고 사진을 받아도 그대로 둔다")
    void v41LeavesEnabledAgentOfConnectionThatWasNotReady() throws SQLException {
        assertThat(agent(PENDING_ON)).isEqualTo(new AgentRow(true, true));
        assertThat(connection(PENDING_ON)).isEqualTo(new Row("PENDING", false, true, 0));
    }

    private void migrate(String target) {
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .target(target)
                .load()
                .migrate();
    }

    private static String row(int id, String status, boolean restart, boolean desired) {
        return "INSERT INTO connector_connection (user_id, connector_id, agent_id, status, fields,"
                + " restart_required, desired_enabled, checked_at, created_at, updated_at) VALUES ("
                + id + ", 'demo-notes', " + id + ", '" + status + "', '{\"values\":{},\"secretPrefixes\":{}}', "
                + restart + ", " + desired + ", NULL, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))";
    }

    private record Row(String status, boolean restartRequired, boolean desiredEnabled, int undeclaredTools) {}

    private record AgentRow(boolean enabled, boolean attachments) {}

    private Row connection(int userId) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement();
                ResultSet row =
                        statement.executeQuery("SELECT status, restart_required, desired_enabled, undeclared_tools"
                                + " FROM connector_connection WHERE user_id = " + userId)) {
            assertThat(row.next()).as("사용자 %d 의 연결", userId).isTrue();
            return new Row(
                    row.getString("status"),
                    row.getBoolean("restart_required"),
                    row.getBoolean("desired_enabled"),
                    row.getInt("undeclared_tools"));
        }
    }

    private AgentRow agent(int id) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement();
                ResultSet row =
                        statement.executeQuery("SELECT enabled, connector_attachments FROM agent WHERE id = " + id)) {
            assertThat(row.next()).as("에이전트 %d", id).isTrue();
            return new AgentRow(row.getBoolean("enabled"), row.getBoolean("connector_attachments"));
        }
    }
}
