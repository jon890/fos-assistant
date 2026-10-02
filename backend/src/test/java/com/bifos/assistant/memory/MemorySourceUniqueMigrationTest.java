package com.bifos.assistant.memory;

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
 * V55 가 이미 있는 Memory 를 잃지 않고 같은 출처의 중복을 막는지 본다(ADR-058).
 *
 * <p>다른 검사들은 엔티티로 스키마를 만들어 이 마이그레이션을 지나지 않는다.
 */
class MemorySourceUniqueMigrationTest {

    @Test
    @DisplayName("V55 는 출처가 없는 줄을 그대로 두고 같은 주인의 같은 출처만 막는다")
    void allowsRowsWithoutSourceAndRejectsSameSourceOfSameOwner() throws SQLException {
        Database database = createDatabase();
        migrate(database, "54");
        try (Connection connection =
                        DriverManager.getConnection(database.url(), database.username(), database.password());
                Statement statement = connection.createStatement()) {
            insert(statement, 1, "첫째", null);
            insert(statement, 1, "둘째", null);

            migrate(database, "55");

            try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM memory")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(2);
            }

            insert(statement, 1, "셋째", "private/wiki/sample/note-a.md");
            assertThatThrownBy(() -> insert(statement, 1, "넷째", "private/wiki/sample/note-a.md"))
                    .isInstanceOf(SQLException.class);
            insert(statement, 2, "다섯째", "private/wiki/sample/note-a.md");
        }
    }

    private static void insert(Statement statement, long owner, String title, String sourceRef) throws SQLException {
        String source = sourceRef == null ? "NULL, NULL" : "'brain', '" + sourceRef + "'";
        statement.executeUpdate("""
                INSERT INTO memory (
                    scope, owner_user_id, group_id, title, content, always_inject, status,
                    source_type, source_ref, created_at, updated_at
                ) VALUES ('USER', %d, NULL, '%s', '본문', FALSE, 'ACCEPTED', %s,
                    CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """.formatted(owner, title, source));
    }

    /** 같은 검사를 실제 MySQL 에서 돌리는 하위 클래스가 바꿔 끼운다. */
    Database createDatabase() {
        return new Database("jdbc:h2:mem:migration-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    }

    private static void migrate(Database database, String target) {
        Flyway.configure()
                .dataSource(database.url(), database.username(), database.password())
                .locations("classpath:db/migration")
                .target(target)
                .load()
                .migrate();
    }

    record Database(String url, String username, String password) {}
}
