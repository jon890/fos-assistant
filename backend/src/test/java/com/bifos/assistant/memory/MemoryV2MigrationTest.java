package com.bifos.assistant.memory;

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * V47 이 이미 있는 Memory 와 에이전트를 잃지 않고 넓힌 칸으로 옮기는지 본다(ADR-051, ADR-052).
 *
 * <p>다른 검사들은 엔티티로 스키마를 만들어 이 옮김을 지나지 않는다. 기존 줄이 있는 데이터베이스에서 마이그레이션이
 * 멈추거나 값을 잘못 옮기면 배포한 뒤 주입 결과가 달라진다.
 */
class MemoryV2MigrationTest {

    private String url;

    @BeforeEach
    void setUpV46DataAndMigrate() throws SQLException {
        url = "jdbc:h2:mem:migration-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";

        // V47 바로 앞까지 올린 뒤 그 시점의 사용자, 에이전트, Memory 를 넣는다.
        migrate("46");
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO app_user (email, display_name, group_id, role, created_at) VALUES
                        ('dad@example.com', 'Dad', 1, 'ADMIN', CURRENT_TIMESTAMP(6)),
                        ('kid@example.com', 'Kid', 1, 'MEMBER', CURRENT_TIMESTAMP(6)),
                        ('other@example.com', 'Other', 2, 'ADMIN', CURRENT_TIMESTAMP(6))
                    """);
            statement.executeUpdate("""
                    INSERT INTO agent (
                        code, name, hermes_profile, api_base_url, cost_mode, credential_scope, visibility,
                        owner_user_id, enabled, created_at, profile_managed, connector_managed
                    ) VALUES
                        ('home', 'Home', 'home', 'http://runtime.test/p/home', 'SUBSCRIPTION',
                            'SHARED_HOUSEHOLD', 'GROUP', NULL, TRUE, CURRENT_TIMESTAMP(6), FALSE, FALSE),
                        ('dad', 'Dad', 'dad', 'http://runtime.test/p/dad', 'SUBSCRIPTION',
                            'SHARED_HOUSEHOLD', 'PRIVATE', 1, TRUE, CURRENT_TIMESTAMP(6), FALSE, FALSE),
                        ('ledger', 'Ledger', 'ledger', 'http://runtime.test/p/ledger', 'SUBSCRIPTION',
                            'SHARED_HOUSEHOLD', 'PRIVATE', 1, TRUE, CURRENT_TIMESTAMP(6), TRUE, TRUE)
                    """);
            statement.executeUpdate("""
                    INSERT INTO memory (
                        scope, owner_user_id, group_id, title, content, always_inject, status,
                        created_at, updated_at
                    ) VALUES
                        ('GROUP', NULL, 1, '그룹 항상', '그룹 내용', TRUE, 'ACCEPTED',
                            CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
                        ('USER', 1, NULL, '개인 색인', '개인 내용', FALSE, 'ACCEPTED',
                            CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
                        ('USER', 1, NULL, '개인 제안', '제안 내용', FALSE, 'PROPOSED',
                            CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                    """);
        }
        migrate("47");
    }

    @Test
    @DisplayName("V47 은 기존 Memory 를 모두 남기고 always inject 를 꺼내는 방식으로 옮긴다")
    void v47KeepsEveryMemoryAndMovesAlwaysInjectToRetrieval() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            assertThat(pairs(statement, "SELECT title, retrieval FROM memory"))
                    .containsExactlyInAnyOrderEntriesOf(
                            Map.of("그룹 항상", "ALWAYS", "개인 색인", "SEARCH", "개인 제안", "SEARCH"));
            assertThat(pairs(statement, "SELECT title, content FROM memory"))
                    .containsExactlyInAnyOrderEntriesOf(
                            Map.of("그룹 항상", "그룹 내용", "개인 색인", "개인 내용", "개인 제안", "제안 내용"));
            assertThat(values(statement, """
                    SELECT DISTINCT CONCAT(collection, '/', entry_type, '/', sensitivity, '/', CAST(revision AS VARCHAR(10)))
                    FROM memory
                    """)).containsExactly("core/MEMORY/NORMAL/1");
            assertThat(values(
                            statement,
                            "SELECT title FROM memory WHERE document_key IS NOT NULL OR source_type IS NOT NULL"))
                    .isEmpty();
            // 옛 칸은 한 배포 동안 그대로 남는다.
            assertThat(pairs(statement, "SELECT title, CAST(always_inject AS VARCHAR(5)) FROM memory"))
                    .containsEntry("그룹 항상", "TRUE")
                    .containsEntry("개인 색인", "FALSE");
        }
    }

    @Test
    @DisplayName("V47 은 커넥터 에이전트가 아닌 모든 에이전트에 core 를 주고 커넥터 에이전트에는 주지 않는다")
    void v47GrantsCoreToEveryAgentExceptConnectorAgents() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            assertThat(pairs(statement, """
                    SELECT a.code, CONCAT(g.collection, '/', CAST(g.allow_sensitive AS VARCHAR(5)))
                    FROM agent_memory_collection g JOIN agent a ON a.id = g.agent_id
                    """)).containsExactlyInAnyOrderEntriesOf(Map.of("home", "core/FALSE", "dad", "core/FALSE"));
        }
    }

    @Test
    @DisplayName("V47 은 사용자가 있는 그룹마다 기본 collection 일곱 개를 순서대로 넣는다")
    void v47SeedsSevenDefaultCollectionsForEveryGroup() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            for (int groupId : List.of(1, 2)) {
                assertThat(values(
                                statement,
                                "SELECT collection_key FROM memory_collection WHERE group_id = " + groupId
                                        + " ORDER BY sort_order"))
                        .containsExactly("core", "career", "learning", "health", "finance", "home", "identity");
            }
            assertThat(values(statement, "SELECT DISTINCT CAST(group_id AS VARCHAR(10)) FROM memory_collection"))
                    .containsExactlyInAnyOrder("1", "2");
        }
    }

    @Test
    @DisplayName("V47 뒤에도 옛 판의 코드가 쓰던 칸만으로 Memory 를 넣을 수 있다")
    void afterV47RowsWrittenWithOnlyTheOldColumnsStillInsert() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO memory (
                        scope, owner_user_id, group_id, title, content, always_inject, status,
                        created_at, updated_at
                    ) VALUES ('USER', 2, NULL, '되돌린 뒤', '옛 코드가 쓴 내용', FALSE, 'ACCEPTED',
                        CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                    """);
            assertThat(values(statement, """
                    SELECT CONCAT(collection, '/', entry_type, '/', retrieval, '/', sensitivity, '/', CAST(revision AS VARCHAR(10)))
                    FROM memory WHERE title = '되돌린 뒤'
                    """)).containsExactly("core/MEMORY/SEARCH/NORMAL/1");
        }
    }

    @Test
    @DisplayName("V47 뒤에는 같은 주인과 collection 에 같은 document key 를 둘 수 없고 key 가 없는 항목은 겹쳐도 된다")
    void afterV47DocumentKeyIsUniquePerOwnerAndCollection() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(document(1, "career", "position-preferences"));
            // 다른 사용자와 다른 collection 은 같은 key 를 쓴다.
            statement.executeUpdate(document(2, "career", "position-preferences"));
            statement.executeUpdate(document(1, "health", "position-preferences"));

            assertThatThrownBy(() -> statement.executeUpdate(document(1, "career", "position-preferences")))
                    .isInstanceOf(SQLException.class);
        }
    }

    private static String document(int ownerUserId, String collection, String documentKey) {
        return """
                INSERT INTO memory (
                    scope, owner_user_id, group_id, title, content, always_inject, status, collection, entry_type,
                    document_key, created_at, updated_at
                ) VALUES ('USER', %d, NULL, '문서', '문서 내용', FALSE, 'ACCEPTED', '%s', 'DOCUMENT', '%s',
                    CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """.formatted(ownerUserId, collection, documentKey);
    }

    private void migrate(String target) {
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .target(target)
                .load()
                .migrate();
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

    private static List<String> values(Statement statement, String sql) throws SQLException {
        List<String> result = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                result.add(rows.getString(1));
            }
        }
        return result;
    }
}
