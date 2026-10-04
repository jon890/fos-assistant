package com.bifos.assistant.followup;

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
 * {@code follow_up} 표의 유일 제약이 열린 같은 제목만 막고 끝난 줄은 받는지 본다(ADR-073).
 *
 * <p>다른 검사들은 엔티티로 스키마를 만들어 이 마이그레이션을 지나지 않는다.
 */
class FollowUpMigrationTest {

    private static final String TITLE_KEY = "a".repeat(64);

    @Test
    @DisplayName("열린 같은 제목 둘은 막고 끝난 같은 제목 둘은 받는다")
    void rejectsTwoOpenRowsAndAcceptsTwoClosedRowsOfSameTitle() throws SQLException {
        Database database = createDatabase();
        migrate(database);
        try (Connection connection =
                        DriverManager.getConnection(database.url(), database.username(), database.password());
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO app_user (email, display_name, group_id, role, created_at)
                    VALUES ('follow-up-migration@example.com', 'Dad', 1, 'ADMIN', CURRENT_TIMESTAMP(6))
                    """);
            long userId = userId(statement);

            insert(statement, userId, 1, "OPEN", "1");
            assertThatThrownBy(() -> insert(statement, userId, 2, "PROPOSED", "1"))
                    .isInstanceOf(SQLException.class);

            insert(statement, userId, 3, "DONE", "NULL");
            insert(statement, userId, 4, "DROPPED", "NULL");

            try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM follow_up WHERE user_id = " + userId)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(3);
            }
        }
    }

    private static long userId(Statement statement) throws SQLException {
        try (ResultSet rows =
                statement.executeQuery("SELECT id FROM app_user WHERE email = 'follow-up-migration@example.com'")) {
            assertThat(rows.next()).isTrue();
            return rows.getLong(1);
        }
    }

    /** 같은 사용자와 같은 {@code title_key} 로 한 줄을 넣는다. 공개 식별자는 {@code seq} 로 서로 다르게 한다. */
    private static void insert(Statement statement, long userId, int seq, String status, String openMarker)
            throws SQLException {
        String publicId = "%032x".formatted(seq);
        statement.executeUpdate("""
                INSERT INTO follow_up (
                    public_id, user_id, title, title_key, waiting, status, open_marker, created_at, updated_at
                ) VALUES (X'%s', %d, '할 일 검사 7391', '%s', FALSE, '%s', %s,
                    CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """.formatted(publicId, userId, TITLE_KEY, status, openMarker));
    }

    /** 같은 검사를 실제 MySQL 에서 돌리는 하위 클래스가 바꿔 끼울 수 있다. */
    Database createDatabase() {
        return new Database("jdbc:h2:mem:migration-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    }

    private static void migrate(Database database) {
        Flyway.configure()
                .dataSource(database.url(), database.username(), database.password())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    record Database(String url, String username, String password) {}
}
