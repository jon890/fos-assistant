package com.bifos.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * V27 이 에이전트의 모델 목록과 막힌 provider 표, 에이전트의 모델 칸을 지우는지 본다.
 *
 * <p>다른 검사들은 엔티티로 스키마를 만들어 이 마이그레이션을 한 번도 지나지 않는다. 칸이 남아 있으면 배포할 때
 * 엔티티에 없는 {@code NOT NULL} 칸 때문에 에이전트를 저장하지 못한다.
 */
class AgentModelDropMigrationTest {

    private String url;

    @BeforeEach
    void migrateUpFromV26WithAgent() throws SQLException {
        url = "jdbc:h2:mem:migration-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";

        // V27 바로 앞까지 올린 뒤 모델 칸이 채워진 에이전트와 그 모델 목록, 막힌 provider 를 넣는다.
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .target("26")
                .load()
                .migrate();
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO agent (
                        code, name, hermes_profile, api_base_url, provider, model, model_synced_at,
                        cost_mode, credential_scope, visibility, owner_user_id, enabled, created_at
                    ) VALUES (
                        'dad', 'Dad', 'dad-profile', 'http://runtime.test/p/dad', 'openai-codex', 'example-model',
                        CURRENT_TIMESTAMP(6), 'SUBSCRIPTION', 'SHARED_HOUSEHOLD', 'PRIVATE', NULL, TRUE,
                        CURRENT_TIMESTAMP(6)
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO agent_model_option (agent_id, option_rank, provider, model, created_at)
                    SELECT id, 1, 'openai-codex', 'example-model', CURRENT_TIMESTAMP(6) FROM agent
                    """);
            statement.executeUpdate("""
                    INSERT INTO provider_state (provider, blocked_until, blocked_reason, updated_at)
                    VALUES ('openai-codex', CURRENT_TIMESTAMP(6), 'rate limit', CURRENT_TIMESTAMP(6))
                    """);
        }

        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    @DisplayName("V27 뒤에는 모델 목록과 막힌 provider 표가 없다")
    void v27DropsModelListAndBlockedProviderTable() throws SQLException {
        List<String> tables = strings("""
                SELECT UPPER(TABLE_NAME) FROM INFORMATION_SCHEMA.TABLES
                WHERE UPPER(TABLE_NAME) IN ('AGENT_MODEL_OPTION', 'PROVIDER_STATE', 'AGENT')
                """);

        assertThat(tables).as("V27 뒤에 남은 표").containsExactly("AGENT");
    }

    @Test
    @DisplayName("V27 뒤에는 agent 의 모델 칸이 없고 다른 칸은 남는다")
    void v27DropsAgentModelColumnKeepingOthers() throws SQLException {
        List<String> columns = strings("""
                SELECT UPPER(COLUMN_NAME) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE UPPER(TABLE_NAME) = 'AGENT'
                """);

        assertThat(columns)
                .as("V27 뒤 agent 의 칸")
                .doesNotContain("PROVIDER", "MODEL", "MODEL_SYNCED_AT")
                .contains("CODE", "HERMES_PROFILE");
    }

    @Test
    @DisplayName("V27 은 이미 있던 에이전트 줄을 지우지 않는다")
    void v27KeepsExistingAgentRows() throws SQLException {
        assertThat(strings("SELECT code FROM agent")).as("V27 뒤 남은 에이전트").containsExactly("dad");
    }

    private List<String> strings(String sql) throws SQLException {
        List<String> result = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                result.add(rows.getString(1));
            }
        }
        return result;
    }
}
