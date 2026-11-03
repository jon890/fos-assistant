package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 상시 허락과 대화 허락을 옛 두 표에서 허락을 준 {@code connector_action} 줄의 칸으로 옮기는 마이그레이션을 본다
 * (ADR-20261102 / table-reduce-r2).
 *
 * <p>테스트 DB 는 엔티티로 스키마를 만들므로 운영과 같은 Flyway 스키마는 여기서 따로 만든다. 옮긴 뒤의 허락이 옮기기 전보다 넓게
 * 통하지 않는지를 먼저 본다.
 */
class ConnectorGrantToActionMigrationTest {

    /** 칸을 더하는 마이그레이션 바로 앞 버전이다. */
    private static final String BEFORE = "20261102074325";

    /** 처음 옮기는 마이그레이션이다. 이 버전까지는 옛 표가 남아 있다. */
    private static final String MOVED = "20261102074327";


    /** 같은 검사를 실제 MySQL 에서 돌리는 하위 클래스가 바꿔 끼운다. */
    Database createDatabase() {
        return new Database(
            "jdbc:h2:mem:connector-grant-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    }


    @Test
    @DisplayName("대화 허락은 허락을 준 승인 줄로 기간과 거둔 시각을 그대로 옮기고 열쇠가 다른 줄은 옮기지 않으며, 옛 표는 그대로 둔다")
    void movesConversationGrantsOntoTheirAction() throws SQLException {
        Database database = createDatabase();
        migrate(database, BEFORE);
        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            seedOwner(statement);
            statement.executeUpdate(action(11, 1, 10, "'WRITE'", "'2026-11-01 00:00:00'"));
            statement.executeUpdate(action(12, null, 10, "'WRITE'", "'2026-11-01 00:00:00'"));
            statement.executeUpdate(action(13, 1, 10, "'WRITE'", "'2026-11-01 00:00:00'"));
            statement.executeUpdate(action(14, 1, 10, "'WRITE'", "'2026-11-01 00:00:00'"));
            statement.executeUpdate("""
                    INSERT INTO connector_conversation_grant
                        (id, user_id, conversation_id, agent_id, connector_id, tool_name, action_id, expires_at, created_at, revoked_at)
                    VALUES
                        (1, 1, 10, 1, 'demo', 'write_note', 11, '2026-11-02 00:00:00.123456', '2026-11-01 00:00:00', NULL),
                        (2, 1, 10, 7, 'demo', 'write_note', 12, '2026-11-02 00:00:00', '2026-11-01 00:00:00', '2026-11-01 01:00:00'),
                        (3, 1, 20, 1, 'demo', 'write_note', 13, '2026-11-02 00:00:00', '2026-11-01 00:00:00', NULL),
                        (4, 1, 10, 7, 'demo', 'write_note', 14, '2026-11-02 00:00:00', '2026-11-01 00:00:00', NULL)
                    """);
        }

        migrate(database, MOVED);

        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            assertThat(rows(statement, """
                    SELECT id, grant_scope, grant_expires_at, grant_revoked_at
                      FROM connector_action ORDER BY id
                    """))
                .as("13 은 대화가, 14 는 에이전트가 승인 줄과 달라 옮기지 않는다. 12 는 에이전트를 정리해 승인 줄 쪽이 비었다")
                .containsExactly(
                    List.of("11", "CONVERSATION", "2026-11-02T00:00:00.123456", "-"),
                    List.of("12", "CONVERSATION", "2026-11-02T00:00", "2026-11-01T01:00"),
                    List.of("13", "-", "-", "-"),
                    List.of("14", "-", "-", "-"));
            assertThat(strings(statement, "SELECT id FROM connector_conversation_grant ORDER BY id"))
                .as("직전 이미지로 되돌릴 수 있게 옛 표는 지우지 않는다")
                .containsExactly("1", "2", "3", "4");
        }
    }


    @Test
    @DisplayName("상시 허락은 승인 시각이 허락을 만든 시각에 가장 가까운 같은 도구의 승인 줄로 옮기고, 그런 줄이 없으면 옮기지 않는다")
    void movesToolGrantsOntoTheClosestApproval() throws SQLException {
        Database database = createDatabase();
        migrate(database, BEFORE);
        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            seedOwner(statement);
            statement.executeUpdate(action(21, 1, 10, "'WRITE'", "'2026-11-01 00:00:00'"));
            statement.executeUpdate(action(22, 1, 10, "'WRITE'", "'2026-11-01 00:00:59.900000'"));
            statement.executeUpdate(action(23, 1, 10, "'WRITE'", "'2026-11-01 00:01:00.050000'"));
            statement.executeUpdate(action(24, 1, 10, "'MAIL'", "'2026-11-01 05:00:00'"));
            statement.executeUpdate(action(25, 1, 10, "'NOTE'", "'2026-11-01 06:00:00'"));
            statement.executeUpdate("""
                    INSERT INTO connector_conversation_grant
                        (id, user_id, conversation_id, agent_id, connector_id, tool_name, action_id, expires_at, created_at)
                    VALUES (1, 1, 10, 1, 'demo', 'note_note', 25, '2026-11-02 06:00:00', '2026-11-01 06:00:00')
                    """);
            statement.executeUpdate("""
                    INSERT INTO connector_tool_grant (id, user_id, connector_id, tool_name, expires_at, created_at, revoked_at) VALUES
                        (1, 1, 'demo', 'write_note', '2026-12-01 00:00:00', '2026-11-01 00:01:00', NULL),
                        (2, 1, 'demo', 'mail_note', '2026-12-01 00:00:00', '2026-11-01 05:03:00', NULL),
                        (3, 1, 'demo', 'note_note', '2026-12-01 00:00:00', '2026-11-01 06:00:00', '2026-11-01 07:00:00')
                    """);
        }

        migrate(database, MOVED);

        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            assertThat(rows(statement, """
                    SELECT id, grant_scope, grant_expires_at
                      FROM connector_action ORDER BY id
                    """))
                .as("23 이 22 보다 50ms 가깝다. 24 는 1분 밖이고 25 는 이미 대화 허락을 갖는다")
                .containsExactly(
                    List.of("21", "-", "-"),
                    List.of("22", "-", "-"),
                    List.of("23", "TOOL", "2026-12-01T00:00"),
                    List.of("24", "-", "-"),
                    List.of("25", "CONVERSATION", "2026-11-02T06:00"));
            assertThat(strings(statement, "SELECT id FROM connector_tool_grant ORDER BY id"))
                .as("옛 표는 지우지 않는다")
                .containsExactly("1", "2", "3");
        }
    }


    @Test
    @DisplayName("옮길 허락이 없으면 아무 승인 줄도 바꾸지 않는다")
    void changesNothingWithoutGrants() throws SQLException {
        Database database = createDatabase();
        migrate(database, null);
        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            assertThat(strings(statement, "SELECT COUNT(*) FROM connector_action WHERE grant_scope IS NOT NULL"))
                .containsExactly("0");
        }
    }


    @Test
    @DisplayName("직전 이미지가 옛 표에 새로 쓴 허락만 다시 옮기고 이미 옮긴 허락은 되살리지 않은 뒤 옛 허락 두 표와 옛 벡터 표를 지운다")
    void remigratesOnlyGrantsWrittenByPreviousImageThenDropsOldTables() throws SQLException {
        Database database = createDatabase();
        migrate(database, BEFORE);
        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            seedOwner(statement);
            statement.executeUpdate(action(31, 1, 10, "'WRITE'", "'2026-11-01 00:00:00'"));
            statement.executeUpdate(action(32, 1, 10, "'WRITE'", "'2026-11-01 00:00:30'"));
            statement.executeUpdate("""
                    INSERT INTO connector_tool_grant (id, user_id, connector_id, tool_name, expires_at, created_at, revoked_at)
                    VALUES (1, 1, 'demo', 'write_note', '2026-12-01 00:00:00', '2026-11-01 00:00:00', NULL)
                    """);
        }
        migrate(database, MOVED);
        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            // 새 코드가 31번 승인 줄의 허락을 거뒀다. 옛 표의 1번 줄을 다시 옮기면 32번 줄에 되살아난다
            statement.executeUpdate("UPDATE connector_action SET grant_revoked_at = '2026-11-02 00:00:00' WHERE id = 31");
            // 직전 이미지로 되돌린 동안 옛 코드가 옛 표에 쓴 허락이다
            statement.executeUpdate(action(33, 1, 10, "'MAIL'", "'2099-01-01 00:00:10'"));
            statement.executeUpdate(action(34, 1, 10, "'NOTE'", "'2099-01-01 00:00:20'"));
            statement.executeUpdate("""
                    INSERT INTO connector_tool_grant (id, user_id, connector_id, tool_name, expires_at, created_at, revoked_at)
                    VALUES (2, 1, 'demo', 'mail_note', '2099-02-01 00:00:00', '2099-01-01 00:00:00', NULL)
                    """);
            statement.executeUpdate("""
                    INSERT INTO connector_conversation_grant
                        (id, user_id, conversation_id, agent_id, connector_id, tool_name, action_id, expires_at, created_at, revoked_at)
                    VALUES (1, 1, 10, 1, 'demo', 'note_note', 34, '2099-01-02 00:00:00', '2099-01-01 00:00:20', '2099-01-01 01:00:00')
                    """);
        }

        migrate(database, null);

        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            assertThat(rows(statement, """
                    SELECT id, grant_scope, grant_expires_at, grant_revoked_at
                      FROM connector_action WHERE id >= 31 ORDER BY id
                    """))
                .as("32 는 이미 옮긴 1번 줄 때문에 되살아나지 않는다. 33 과 34 는 새로 쓴 줄이라 옮긴다")
                .containsExactly(
                    List.of("31", "TOOL", "2026-12-01T00:00", "2026-11-02T00:00"),
                    List.of("32", "-", "-", "-"),
                    List.of("33", "TOOL", "2099-02-01T00:00", "-"),
                    List.of("34", "CONVERSATION", "2099-01-02T00:00", "2099-01-01T01:00"));
            for (String table : List.of("connector_tool_grant", "connector_conversation_grant", "memory_embedding")) {
                assertThatThrownBy(() -> statement.executeQuery("SELECT COUNT(*) FROM " + table))
                    .as("옛 표 " + table + " 는 지운다").isInstanceOf(SQLException.class);
            }
        }
    }


    private static void seedOwner(Statement statement) throws SQLException {
        statement.executeUpdate("""
                INSERT INTO app_user (id, email, display_name, group_id, role, created_at)
                VALUES (1, 'grant@example.test', 'grant', 1, 'MEMBER', '2026-11-01 00:00:00')
                """);
        statement.executeUpdate("""
                INSERT INTO agent (id, code, name, hermes_profile, api_base_url, cost_mode, credential_scope, visibility,
                    owner_user_id, enabled, created_at)
                VALUES (1, 'grant', 'grant', 'grant', 'http://agent-runtime.test', 'SUBSCRIPTION', 'SHARED_HOUSEHOLD',
                    'PRIVATE', 1, 1, '2026-11-01 00:00:00')
                """);
        statement.executeUpdate("""
                INSERT INTO conversation (id, user_id, agent_id, hermes_session_id, title, created_at, updated_at, public_id,
                    hermes_root_session_id, auto_turn_count, purpose)
                VALUES (10, 1, 1, 's10', 't', '2026-11-01 00:00:00', '2026-11-01 00:00:00',
                        X'0000000000000000000000000000000A', 'r10', 0, 'CHAT'),
                       (20, 1, 1, 's20', 't', '2026-11-01 00:00:00', '2026-11-01 00:00:00',
                        X'00000000000000000000000000000014', 'r20', 0, 'CHAT')
                """);
    }


    /**
     * 승인한 줄 하나를 넣는 문장이다. 도구 이름은 {@code <tool 소문자>_note} 다.
     *
     * @param agentId 판정한 실행의 에이전트. 에이전트를 정리한 줄이면 null
     */
    private static String action(long id, Integer agentId, long conversationId, String tool, String decidedAt) {
        return """
                INSERT INTO connector_action (id, public_id, user_id, agent_id, connector_id, tool_name, hermes_tool, risk,
                    approval_mode, decision, passed, status, origin_execution_id, conversation_id, dedupe_key, args_sha256,
                    created_at, decided_at, check_tree)
                VALUES (%d, X'%032X', 1, %s, 'demo', CONCAT(LOWER(%s), '_note'), 'demo_note', 'WRITE',
                    'REQUIRED', 'NEEDS_APPROVAL', 0, 'SUCCEEDED', 1, %d, 'grant-%d', 'sha', %s, %s, 0)
                """.formatted(id, id, agentId == null ? "NULL" : agentId.toString(), tool, conversationId, id, decidedAt,
            decidedAt);
    }


    private static void migrate(Database database, String target) {
        var configuration = Flyway.configure()
            .dataSource(database.url(), database.username(), database.password())
            .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }


    private static List<String> strings(Statement statement, String query) throws SQLException {
        List<String> values = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery(query)) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }


    /** 줄마다 칸의 값을 문자열로 읽는다. NULL 은 {@code -} 이고 시각은 DB 와 상관없이 {@link LocalDateTime} 의 글자다. */
    private static List<List<String>> rows(Statement statement, String query) throws SQLException {
        List<List<String>> values = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery(query)) {
            int columns = rows.getMetaData().getColumnCount();
            while (rows.next()) {
                List<String> row = new ArrayList<>();
                for (int column = 1; column <= columns; column++) {
                    Object value = rows.getMetaData().getColumnType(column) == Types.TIMESTAMP
                        ? rows.getObject(column, LocalDateTime.class)
                        : rows.getString(column);
                    row.add(value == null ? "-" : value.toString());
                }
                values.add(row);
            }
        }
        return values;
    }


    private static Connection connect(Database database) throws SQLException {
        return DriverManager.getConnection(database.url(), database.username(), database.password());
    }


    record Database(String url, String username, String password) {
    }
}
