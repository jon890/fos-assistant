package com.bifos.assistant.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * V25 가 사용자들이 모인 단위의 컬럼과 저장 값을 family 에서 group 으로 옮기는지 본다.
 *
 * <p>다른 검사들은 엔티티로 스키마를 만들어 이 옮김을 한 번도 지나지 않는다. 옮기지 못하면 배포할 때
 * 스키마 검증이 실패하거나, 이미 저장된 {@code FAMILY} 값을 enum 으로 읽지 못해 목록이 깨진다.
 */
class GroupRenameMigrationTest {

    private String url;

    @BeforeEach
    void V24_시점의_데이터를_만든다() throws SQLException {
        url = "jdbc:h2:mem:migration-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";

        // V25 바로 앞까지 올린 뒤 그 시점의 사용자, 에이전트, Memory 를 넣는다.
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .target("24")
                .load()
                .migrate();
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    """
                    INSERT INTO app_user (email, display_name, family_id, role, created_at)
                    VALUES ('dad@example.com', 'Dad', 1, 'ADMIN', CURRENT_TIMESTAMP(6))
                    """);
            statement.executeUpdate(
                    """
                    INSERT INTO agent (
                        code, name, hermes_profile, api_base_url, provider, model, model_synced_at,
                        cost_mode, credential_scope, visibility, owner_user_id, enabled, created_at
                    ) VALUES
                        ('home', 'Home', 'home', 'http://runtime.test/p/home', 'openai-codex', 'example-model',
                            NULL, 'SUBSCRIPTION', 'SHARED_HOUSEHOLD', 'FAMILY', NULL, TRUE, CURRENT_TIMESTAMP(6)),
                        ('dad', 'Dad', 'dad', 'http://runtime.test/p/dad', 'openai-codex', 'example-model',
                            NULL, 'SUBSCRIPTION', 'SHARED_HOUSEHOLD', 'PRIVATE', 1, TRUE, CURRENT_TIMESTAMP(6))
                    """);
            statement.executeUpdate(
                    """
                    INSERT INTO memory (
                        scope, owner_user_id, family_id, title, content, always_inject, status,
                        created_at, updated_at
                    ) VALUES
                        ('FAMILY', NULL, 1, '그룹 제목', '그룹 내용', TRUE, 'ACCEPTED',
                            CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
                        ('USER', 1, NULL, '개인 제목', '개인 내용', FALSE, 'ACCEPTED',
                            CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                    """);
        }

        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    void V25_가_group_id_값을_보존하고_FAMILY_만_GROUP_으로_바꾼다() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery(
                    "SELECT group_id FROM app_user WHERE email = 'dad@example.com'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong("group_id")).isEqualTo(1L);
            }

            assertThat(pairs(statement, "SELECT code, visibility FROM agent WHERE code IN ('home', 'dad')"))
                    .containsExactlyInAnyOrderEntriesOf(Map.of("home", "GROUP", "dad", "PRIVATE"));

            Map<String, String> memoryScopes = pairs(statement, "SELECT title, scope FROM memory");
            assertThat(memoryScopes)
                    .containsExactlyInAnyOrderEntriesOf(Map.of("그룹 제목", "GROUP", "개인 제목", "USER"));

            Map<String, String> memoryGroups = pairs(statement,
                    "SELECT title, CAST(group_id AS VARCHAR(20)) FROM memory");
            assertThat(memoryGroups).containsEntry("그룹 제목", "1").containsEntry("개인 제목", null);
        }
    }

    @Test
    void V25_뒤에는_색인도_group_이름으로_바뀌고_옛_이름은_남지_않는다() throws SQLException {
        List<String> indexes = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        """
                        SELECT UPPER(INDEX_NAME) FROM INFORMATION_SCHEMA.INDEXES
                        WHERE UPPER(TABLE_NAME) IN ('APP_USER', 'MEMORY')
                        """)) {
            while (rows.next()) {
                indexes.add(rows.getString(1));
            }
        }
        assertThat(indexes)
                .contains("IX_APP_USER_GROUP", "IDX_MEMORY_GROUP")
                .doesNotContain("IX_APP_USER_FAMILY", "IDX_MEMORY_FAMILY");
    }

    @Test
    void V25_뒤에는_family_id_컬럼을_읽을_수_없다() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeQuery("SELECT family_id FROM app_user"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeQuery("SELECT family_id FROM memory"))
                    .isInstanceOf(SQLException.class);
        }
    }

    private static Map<String, String> pairs(Statement statement, String sql) throws SQLException {
        Map<String, String> result = new LinkedHashMap<>();
        try (ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                result.put(rows.getString(1), rows.getString(2));
            }
        }
        return result;
    }
}
