package com.bifos.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * V29 가 토큰에 profile 칸을 더하고 {@code user_id} 를 비워 둘 수 있게 하되 옛 줄은 그대로 두는지 본다.
 *
 * <p>다른 검사들은 엔티티로 스키마를 만들어 이 마이그레이션을 지나지 않는다. 마이그레이션은 H2 의 MySQL 모드와
 * MySQL 에서 함께 돌아야 한다.
 *
 * <p>V34 까지만 올린다. 이 검사는 V29 뒤의 {@code user_id} 를 읽고 폐기 안 된 profile 없는 줄을 넣으므로, 최신까지
 * 올리면 V35 가 그 줄 때문에 실패한다.
 */
class AgentTokenProfileMigrationTest {

    @Test
    @DisplayName("V29 는 옛 토큰을 바꾸지 않고 profile 만 있는 새 토큰을 받는다")
    void v29KeepsOldTokensAndIssuesNewTokensWithOnlyProfile() throws SQLException {
        String url = "jdbc:h2:mem:migration-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .target("28")
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO agent_token (user_id, token_hash, label, created_at) "
                    + "VALUES (7, 'old-hash', 'old', CURRENT_TIMESTAMP(6))");
        }

        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .target("34")
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery(
                    "SELECT user_id, profile_name FROM agent_token WHERE token_hash = 'old-hash'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong("user_id")).isEqualTo(7L);
                assertThat(rows.getString("profile_name"))
                        .as("옛 토큰의 profile 은 채우지 않는다")
                        .isNull();
            }

            statement.executeUpdate("INSERT INTO agent_token (user_id, profile_name, token_hash, label, created_at) "
                    + "VALUES (NULL, 'shared-group', 'new-hash', 'new', CURRENT_TIMESTAMP(6))");
            try (ResultSet rows = statement.executeQuery(
                    "SELECT user_id, profile_name FROM agent_token WHERE token_hash = 'new-hash'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getObject("user_id")).isNull();
                assertThat(rows.getString("profile_name")).isEqualTo("shared-group");
            }
        }
    }
}
