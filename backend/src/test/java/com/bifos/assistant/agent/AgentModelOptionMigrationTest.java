package com.bifos.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/**
 * 마이그레이션이 이미 있는 에이전트의 값을 1순위로 옮기는지 본다.
 *
 * <p>테스트는 엔티티로 스키마를 만들고 운영은 Flyway 가 만든다. 그래서 다른 검사들은 이 옮김을 한
 * 번도 지나지 않는다. 옮기지 않으면 배포 직후 모든 실행이 쓸 모델을 찾지 못한다.
 */
class AgentModelOptionMigrationTest {

    @Test
    void V14_가_기존_에이전트의_provider_와_모델을_1순위로_옮긴다() throws SQLException {
        String url = "jdbc:h2:mem:migration-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";

        // V14 바로 앞까지 올린 뒤 그 시점의 에이전트 한 줄을 만든다.
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .target("13")
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
                        'dad', 'Dad', 'dad', 'http://runtime.test/p/dad', 'openai-codex', 'example-model', NULL,
                        'SUBSCRIPTION', 'SHARED_HOUSEHOLD', 'PRIVATE', NULL, TRUE, CURRENT_TIMESTAMP(6)
                    )
                    """);
        }

        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        """
                        SELECT o.option_rank, o.provider, o.model
                        FROM agent_model_option o
                        INNER JOIN agent a ON a.id = o.agent_id
                        WHERE a.code = 'dad'
                        """)) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt("option_rank")).isEqualTo(1);
            assertThat(rows.getString("provider")).isEqualTo("openai-codex");
            assertThat(rows.getString("model")).isEqualTo("example-model");
            assertThat(rows.next()).isFalse();
        }
    }
}
