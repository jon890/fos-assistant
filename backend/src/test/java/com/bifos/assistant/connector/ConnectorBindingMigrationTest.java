package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 바인딩 표를 만든 뒤 이미 있는 연결마다 그 전용 에이전트와의 바인딩이 채워지는지 본다(ADR-083).
 *
 * <p>테스트 DB 는 엔티티로 스키마를 만들므로, 운영과 같은 Flyway 스키마는 여기서 따로 확인한다. 옛 연결에 행을 넣으려고
 * V76 까지만 올린 뒤 V78 까지 올린다.
 */
class ConnectorBindingMigrationTest {
    private static final Timestamp CREATED_AT = Timestamp.valueOf("2026-09-01 00:00:00");
    private static final Timestamp CHECKED_AT = Timestamp.valueOf("2026-09-02 00:00:00");
    private static final Timestamp UPDATED_AT = Timestamp.valueOf("2026-09-03 00:00:00");

    private Database database;

    @BeforeEach
    void setUp() throws SQLException {
        database = createDatabase();
        migrate("76");
        seed();
        migrate("78");
    }

    /** 같은 검사를 실제 MySQL 에서 돌리는 하위 클래스가 바꿔 끼운다. */
    Database createDatabase() {
        return new Database(
                "jdbc:h2:mem:connector-binding-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    }

    @Test
    @DisplayName("재시작 대기가 없는 READY 연결은 같은 상태와 칸의 바인딩 하나가 되고 대기 시작 시각은 비어 있다")
    void backfillsReadyConnectionWithoutRestart() throws SQLException {
        List<Binding> bindings = bindingsOf(901);

        assertThat(bindings).as("연결 901 의 바인딩 수").hasSize(1);
        Binding binding = bindings.getFirst();
        assertThat(binding.agentId()).isEqualTo(901L);
        assertThat(binding.mcpServer()).as("마이그레이션은 서버 이름을 알 수 없다").isNull();
        assertThat(binding.status()).isEqualTo("READY");
        assertThat(binding.restartRequired()).isFalse();
        assertThat(binding.restartRequiredSince()).isNull();
        assertThat(binding.desiredEnabled()).isTrue();
        assertThat(binding.checkedAt()).isEqualTo(CHECKED_AT);
        assertThat(binding.createdAt()).isEqualTo(CREATED_AT);
        assertThat(binding.updatedAt()).isEqualTo(UPDATED_AT);
    }

    @Test
    @DisplayName("재시작 대기인 PENDING 연결의 바인딩은 대기 시작 시각이 그 연결의 updated_at 이다")
    void backfillsPendingConnectionWithRestartSinceUpdatedAt() throws SQLException {
        List<Binding> bindings = bindingsOf(902);

        assertThat(bindings).as("연결 902 의 바인딩 수").hasSize(1);
        Binding binding = bindings.getFirst();
        assertThat(binding.agentId()).isEqualTo(902L);
        assertThat(binding.status()).isEqualTo("PENDING");
        assertThat(binding.restartRequired()).isTrue();
        assertThat(binding.restartRequiredSince()).isEqualTo(UPDATED_AT);
        assertThat(binding.desiredEnabled()).isFalse();
        assertThat(binding.checkedAt()).isNull();
        assertThat(binding.createdAt()).isEqualTo(CREATED_AT);
        assertThat(binding.updatedAt()).isEqualTo(UPDATED_AT);
    }

    @Test
    @DisplayName("해제된 연결은 바인딩을 만들지 않는다")
    void skipsDisconnectedConnection() throws SQLException {
        assertThat(bindingsOf(903)).isEmpty();
        assertThat(count("SELECT COUNT(*) FROM agent_connector_binding"))
                .as("READY 와 PENDING 연결 둘의 바인딩만 있다")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("모든 연결의 vault_stored 가 거짓으로 생긴다")
    void addsVaultStoredFalseToEveryConnection() throws SQLException {
        assertThat(count("SELECT COUNT(*) FROM connector_connection")).isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM connector_connection WHERE vault_stored = FALSE"))
                .as("vault_stored 가 거짓인 연결 수")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("에이전트 없는 연결을 넣을 수 있고 한 에이전트와 연결의 바인딩은 둘이 되지 않는다")
    void allowsConnectionWithoutAgentAndKeepsBindingUnique() throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO connector_connection (user_id, connector_id, agent_id, status,"
                    + " fields, created_at, updated_at) VALUES (901, 'other-notes', NULL, 'PENDING',"
                    + " '{\"values\":{},\"secretPrefixes\":{}}', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))");

            boolean duplicateRejected = false;
            try {
                statement.executeUpdate("INSERT INTO agent_connector_binding (agent_id, connection_id, status,"
                        + " created_at, updated_at) VALUES (901, " + connectionId(901)
                        + ", 'PENDING', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))");
            } catch (SQLException expected) {
                duplicateRejected = true;
            }
            assertThat(duplicateRejected).as("같은 에이전트와 연결의 두 번째 바인딩은 거절된다").isTrue();
        }
        assertThat(count("SELECT COUNT(*) FROM connector_connection WHERE agent_id IS NULL"))
                .isEqualTo(1);
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
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            for (int id = 901; id <= 903; id++) {
                statement.executeUpdate("INSERT INTO app_user (id, email, display_name, group_id, role, created_at)"
                        + " VALUES (" + id + ", 'u" + id + "@example.com', 'u" + id + "', 1, 'MEMBER',"
                        + " CURRENT_TIMESTAMP(6))");
                statement.executeUpdate("INSERT INTO agent (id, code, name, hermes_profile, api_base_url, cost_mode,"
                        + " credential_scope, visibility, owner_user_id, enabled, created_at, connector_managed)"
                        + " VALUES (" + id + ", 'c" + id + "', 'n', 'p" + id + "', 'http://localhost',"
                        + " 'SUBSCRIPTION', 'SHARED_HOUSEHOLD', 'PRIVATE', " + id + ", FALSE,"
                        + " CURRENT_TIMESTAMP(6), TRUE)");
            }
            // 재시작 대기 없는 READY, 재시작 대기인 PENDING, 해제된 연결이다.
            statement.executeUpdate(connectionRow(901, "READY", false, true, "TIMESTAMP '2026-09-02 00:00:00'"));
            statement.executeUpdate(connectionRow(902, "PENDING", true, false, "NULL"));
            statement.executeUpdate(connectionRow(903, "DISCONNECTED", true, false, "NULL"));
        }
    }

    private static String connectionRow(
            int id, String status, boolean restartRequired, boolean desiredEnabled, String checkedAt) {
        return "INSERT INTO connector_connection (user_id, connector_id, agent_id, status, fields,"
                + " restart_required, desired_enabled, checked_at, created_at, updated_at) VALUES ("
                + id + ", 'demo-notes', " + id + ", '" + status + "', '{\"values\":{},\"secretPrefixes\":{}}', "
                + restartRequired + ", " + desiredEnabled + ", " + checkedAt
                + ", TIMESTAMP '2026-09-01 00:00:00', TIMESTAMP '2026-09-03 00:00:00')";
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(database.url(), database.username(), database.password());
    }

    private long connectionId(int userId) throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery("SELECT id FROM connector_connection WHERE user_id = " + userId
                        + " AND connector_id = 'demo-notes'")) {
            assertThat(row.next()).as("사용자 %d 의 연결", userId).isTrue();
            return row.getLong(1);
        }
    }

    private int count(String sql) throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery(sql)) {
            assertThat(row.next()).isTrue();
            return row.getInt(1);
        }
    }

    /** 사용자의 옛 연결에 걸린 바인딩을 모두 읽는다. */
    private List<Binding> bindingsOf(int userId) throws SQLException {
        List<Binding> result = new ArrayList<>();
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("""
                        SELECT b.agent_id, b.mcp_server, b.status, b.restart_required, b.restart_required_since,
                            b.desired_enabled, b.checked_at, b.created_at, b.updated_at
                        FROM agent_connector_binding b
                        JOIN connector_connection c ON c.id = b.connection_id
                        WHERE c.user_id = %d
                        """.formatted(userId))) {
            while (rows.next()) {
                result.add(new Binding(
                        rows.getLong(1),
                        rows.getString(2),
                        rows.getString(3),
                        rows.getBoolean(4),
                        rows.getTimestamp(5),
                        rows.getBoolean(6),
                        rows.getTimestamp(7),
                        rows.getTimestamp(8),
                        rows.getTimestamp(9)));
            }
        }
        return result;
    }

    private record Binding(
            long agentId,
            String mcpServer,
            String status,
            boolean restartRequired,
            Timestamp restartRequiredSince,
            boolean desiredEnabled,
            Timestamp checkedAt,
            Timestamp createdAt,
            Timestamp updatedAt) {}

    record Database(String url, String username, String password) {}
}
