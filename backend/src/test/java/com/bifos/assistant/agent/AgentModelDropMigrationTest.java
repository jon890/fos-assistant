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
    void V26_시점의_에이전트를_두고_끝까지_올린다() throws SQLException {
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
            statement.executeUpdate(
                    """
                    INSERT INTO agent (
                        code, name, hermes_profile, api_base_url, provider, model, model_synced_at,
                        cost_mode, credential_scope, visibility, owner_user_id, enabled, created_at
                    ) VALUES (
                        'dad', 'Dad', 'dad-profile', 'http://runtime.test/p/dad', 'openai-codex', 'example-model',
                        CURRENT_TIMESTAMP(6), 'SUBSCRIPTION', 'SHARED_HOUSEHOLD', 'PRIVATE', NULL, TRUE,
                        CURRENT_TIMESTAMP(6)
                    )
                    """);
            statement.executeUpdate(
                    """
                    INSERT INTO agent_model_option (agent_id, option_rank, provider, model, created_at)
                    SELECT id, 1, 'openai-codex', 'example-model', CURRENT_TIMESTAMP(6) FROM agent
                    """);
            statement.executeUpdate(
                    """
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
    void V27_뒤에는_모델_목록과_막힌_provider_표가_없다() throws SQLException {
        List<String> tables = strings(
                """
                SELECT UPPER(TABLE_NAME) FROM INFORMATION_SCHEMA.TABLES
                WHERE UPPER(TABLE_NAME) IN ('AGENT_MODEL_OPTION', 'PROVIDER_STATE', 'AGENT')
                """);

        assertThat(tables)
                .as("V27 뒤에 남은 표")
                .containsExactly("AGENT");
    }

    @Test
    void V27_뒤에는_agent_의_모델_칸이_없고_다른_칸은_남는다() throws SQLException {
        List<String> columns = strings(
                """
                SELECT UPPER(COLUMN_NAME) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE UPPER(TABLE_NAME) = 'AGENT'
                """);

        assertThat(columns)
                .as("V27 뒤 agent 의 칸")
                .doesNotContain("PROVIDER", "MODEL", "MODEL_SYNCED_AT")
                .contains("CODE", "HERMES_PROFILE");
    }

    @Test
    void V27_은_이미_있던_에이전트_줄을_지우지_않는다() throws SQLException {
        assertThat(strings("SELECT code FROM agent"))
                .as("V27 뒤 남은 에이전트")
                .containsExactly("dad");
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
