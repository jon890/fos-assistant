package com.bifos.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * V35 가 {@code agent_token.user_id} 를 지우되, profile 이 비고 폐기 안 된 토큰이 남았으면 칸을 지우기 전에 실패하는지
 * 본다(ADR-032).
 *
 * <p>다른 검사들은 엔티티로 스키마를 만들어 이 마이그레이션을 지나지 않는다. 마이그레이션은 H2 의 MySQL 모드와
 * MySQL 에서 함께 돌아야 한다.
 */
class AgentTokenUserIdDropMigrationTest {

    @Test
    @DisplayName("폐기 안 된 토큰이 모두 profile 에 묶였으면 user id 칸을 지우고 줄은 남긴다")
    void dropsUserIdColumnKeepingRowsWhenAllTokensAreBoundToProfile() throws SQLException {
        String url = migratedTo34();
        execute(
                url,
                "INSERT INTO agent_token (user_id, profile_name, token_hash, label, created_at) "
                        + "VALUES (NULL, 'shared-group', 'bound-hash', 'bound', CURRENT_TIMESTAMP(6))");
        execute(
                url,
                "INSERT INTO agent_token (user_id, profile_name, token_hash, label, created_at, revoked_at) "
                        + "VALUES (7, NULL, 'revoked-hash', 'revoked', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))");

        migrateToLatest(url);

        assertThat(tokenHashes(url)).as("V35 뒤에 남은 토큰").containsExactlyInAnyOrder("bound-hash", "revoked-hash");
        assertThat(userIdColumns(url)).as("agent_token.user_id 칸 수").isZero();
    }

    @Test
    @DisplayName("profile 이 비고 폐기 안 된 토큰이 있으면 실패하고 user id 칸을 그대로 둔다")
    void failsAndKeepsUserIdColumnWhenProfileBlankAndUnrevokedTokenExists() throws SQLException {
        String url = migratedTo34();
        execute(
                url,
                "INSERT INTO agent_token (user_id, profile_name, token_hash, label, created_at) "
                        + "VALUES (7, NULL, 'unbound-hash', 'unbound', CURRENT_TIMESTAMP(6))");

        assertThatThrownBy(() -> migrateToLatest(url))
                .isInstanceOf(FlywayException.class)
                .satisfies(ex -> assertThat(messages(ex))
                        .as("예외 사슬의 메시지")
                        .anyMatch(message -> message.contains("profile 이 묶이지 않은 폐기 안 된 MCP 토큰이 1 개")));
        assertThat(userIdColumns(url)).as("agent_token.user_id 칸 수").isEqualTo(1);
        assertThat(tokenHashes(url)).containsExactly("unbound-hash");
    }

    private static String migratedTo34() {
        String url = "jdbc:h2:mem:migration-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .target("34")
                .load()
                .migrate();
        return url;
    }

    private static void migrateToLatest(String url) {
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private static void execute(String url, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    /** H2 는 이름을 대문자로 둘 수 있어 대문자로 견준다. */
    private static int userIdColumns(String url) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE UPPER(TABLE_NAME) = 'AGENT_TOKEN' AND UPPER(COLUMN_NAME) = 'USER_ID'")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static List<String> tokenHashes(String url) throws SQLException {
        List<String> hashes = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT token_hash FROM agent_token")) {
            while (rows.next()) {
                hashes.add(rows.getString(1));
            }
        }
        return hashes;
    }

    private static List<String> messages(Throwable error) {
        List<String> messages = new ArrayList<>();
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null) {
                messages.add(cause.getMessage());
            }
        }
        return messages;
    }
}
