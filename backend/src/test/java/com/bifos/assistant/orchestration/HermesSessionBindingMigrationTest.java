package com.bifos.assistant.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * V30 이 하위 에이전트 session 등록 표를 만들고 {@code (profile_name, session_id)} 를 유일하게 막는지 본다(ADR-037).
 *
 * <p>다른 검사들은 엔티티로 스키마를 만들어 이 마이그레이션을 지나지 않는다. 마이그레이션은 H2 의 MySQL 모드와
 * MySQL 에서 함께 돌아야 한다.
 */
class HermesSessionBindingMigrationTest {

    private static final String INSERT = "INSERT INTO hermes_session_binding "
            + "(profile_name, session_id, user_id, origin_execution_id, root_session_id, parent_session_id, created_at) "
            + "VALUES ('%s', 'child-1', 7, 100, 'root-1', 'root-1', CURRENT_TIMESTAMP(6))";

    @Test
    @DisplayName("V30 은 profile 마다 session 하나만 받는다")
    void v30AcceptsOnlyOneSessionPerProfile() throws SQLException {
        String url = "jdbc:h2:mem:migration-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").target("29").load().migrate();
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load().migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(INSERT.formatted("profile-a"));
            statement.executeUpdate(INSERT.formatted("profile-b"));

            try (ResultSet rows = statement.executeQuery(
                    "SELECT profile_name, user_id, origin_execution_id FROM hermes_session_binding "
                            + "WHERE session_id = 'child-1' ORDER BY profile_name")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("profile_name")).isEqualTo("profile-a");
                assertThat(rows.getLong("user_id")).isEqualTo(7L);
                assertThat(rows.getLong("origin_execution_id")).isEqualTo(100L);
                assertThat(rows.next()).as("다른 profile 의 같은 session 도 들어간다").isTrue();
                assertThat(rows.getString("profile_name")).isEqualTo("profile-b");
                assertThat(rows.next()).isFalse();
            }

            assertThatThrownBy(() -> statement.executeUpdate(INSERT.formatted("profile-a")))
                    .as("같은 profile 의 같은 session 은 두 번 들어가지 않는다")
                    .isInstanceOf(SQLException.class);
        }
    }
}
