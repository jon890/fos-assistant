package com.bifos.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Flyway 스키마의 NULL 유일 제약과 상태별 슬롯 조건을 H2와 실제 MySQL에서 확인한다. */
class ToolsetRequestMigrationTest {
    private Database database;

    @BeforeEach
    void setUp() throws SQLException {
        database = createDatabase();
        Flyway.configure()
                .dataSource(database.url(), database.username(), database.password())
                .locations("classpath:db/migration")
                .outOfOrder(true)
                .load()
                .migrate();
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO app_user (id, email, display_name, group_id, role, created_at)
                    VALUES (1, 'request@example.com', '요청자', 1, 'MEMBER', CURRENT_TIMESTAMP)
                    """);
            statement.executeUpdate("""
                    INSERT INTO agent (id, code, name, hermes_profile, api_base_url, cost_mode,
                        credential_scope, visibility, owner_user_id, enabled, created_at)
                    VALUES (1, 'request-test', '검사', 'request-test', 'http://hermes.test',
                        'SUBSCRIPTION', 'SHARED_HOUSEHOLD', 'PRIVATE', 1, TRUE, CURRENT_TIMESTAMP)
                    """);
        }
    }

    Database createDatabase() {
        return new Database(
                "jdbc:h2:mem:toolset-request-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    }

    @Test
    @DisplayName("같은 그룹·에이전트·요청자·도구의 대기 요청은 하나만 저장한다")
    void rejectsDuplicatePendingAndKeepsFinishedHistory() throws SQLException {
        insert("PENDING", 1);
        assertThatThrownBy(() -> insert("PENDING", 1)).isInstanceOf(SQLException.class);
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE agent_toolset_request SET status = 'REJECTED', pending_slot = NULL");
        }
        insert("PENDING", 1);
        insert("REJECTED", null);
        insert("APPROVED", null);
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                var result = statement.executeQuery("SELECT COUNT(*) FROM agent_toolset_request")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(4);
        }
    }

    @Test
    @DisplayName("대기 요청은 슬롯 1이 필수이고 끝난 요청은 슬롯을 비워야 한다")
    void enforcesStateAndPendingSlotTogether() {
        assertThatThrownBy(() -> insert("PENDING", null)).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> insert("PENDING", 2)).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> insert("APPROVED", 1)).isInstanceOf(SQLException.class);
    }

    private void insert(String state, Integer slot) throws SQLException {
        UUID id = UUID.randomUUID();
        byte[] bytes = ByteBuffer.allocate(16)
                .putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits())
                .array();
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO agent_toolset_request (public_id, group_id, agent_id, requester_user_id,
                    toolset, status, pending_slot, requested_at)
                VALUES (?, 1, 1, 1, 'image_gen', ?, ?, CURRENT_TIMESTAMP)
                """)) {
            statement.setBytes(1, bytes);
            statement.setString(2, state);
            statement.setObject(3, slot);
            statement.executeUpdate();
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(database.url(), database.username(), database.password());
    }

    record Database(String url, String username, String password) {}
}
