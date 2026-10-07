package com.bifos.assistant.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class EventObservationMigrationTest {

    @Test
    @DisplayName("사건 관측 마이그레이션은 과거 실행을 UNKNOWN 으로 남기고 기존 금액을 보존한다")
    void preservesHistoricalCostWithUnknownObservation() throws SQLException {
        String url = "jdbc:h2:mem:observation-" + UUID.randomUUID() + ";MODE=MySQL";
        try (var connection = DriverManager.getConnection(url, "sa", "");
                var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE agent_execution (id BIGINT PRIMARY KEY, estimated_cost_micros BIGINT)");
            statement.execute("INSERT INTO agent_execution VALUES (1, 1234)");
            ScriptUtils.executeSqlScript(
                    connection, new ClassPathResource("db/migration/V20261007051452__execution_event_observation.sql"));

            try (var rows =
                    statement.executeQuery("SELECT event_observation, estimated_cost_micros FROM agent_execution")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("event_observation")).isEqualTo("UNKNOWN");
                assertThat(rows.getLong("estimated_cost_micros")).isEqualTo(1234L);
            }
        }
    }
}
