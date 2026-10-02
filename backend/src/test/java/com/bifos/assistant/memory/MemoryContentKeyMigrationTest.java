package com.bifos.assistant.memory;

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
 * V52 가 이미 있는 Memory 와 판을 잃지 않고 {@code content_key_id} 칸을 더하는지 본다(ADR-054).
 *
 * <p>다른 검사들은 엔티티로 스키마를 만들어 이 마이그레이션을 지나지 않는다.
 */
class MemoryContentKeyMigrationTest {

    @Test
    @DisplayName("V52 는 기존 본문을 그대로 두고 content_key_id 를 비워 둔다")
    void keepsContentAndLeavesKeyIdEmpty() throws SQLException {
        String url = "jdbc:h2:mem:migration-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        migrate(url, "51");
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO memory (
                        scope, owner_user_id, group_id, title, content, always_inject, status,
                        created_at, updated_at
                    ) VALUES ('USER', 1, NULL, '개인 항목', '옮기기-전-본문', FALSE, 'ACCEPTED',
                        CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                    """);
            statement.executeUpdate("""
                    INSERT INTO memory_revision (
                        memory_id, revision, change_type, scope, owner_user_id, collection, entry_type, status,
                        title, content, retrieval, sensitivity, changed_at
                    ) VALUES (1, 1, 'UPDATED', 'USER', 1, 'core', 'MEMORY', 'ACCEPTED',
                        '개인 항목', '옮기기-전-본문', 'SEARCH', 'NORMAL', CURRENT_TIMESTAMP(6))
                    """);

            migrate(url, "52");

            for (String table : new String[] {"memory", "memory_revision"}) {
                try (ResultSet rows = statement.executeQuery("SELECT content, content_key_id FROM " + table)) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("옮기기-전-본문");
                    assertThat(rows.getString(2)).isNull();
                }
            }
        }
    }

    private static void migrate(String url, String target) {
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .target(target)
                .load()
                .migrate();
    }
}
