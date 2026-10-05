package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 지난 첨부의 메시지 안 순서를 번호 오름차순으로 채우는지 확인한다. */
class ChatAttachmentPositionMigrationTest {
    private Database database;

    @BeforeEach
    void setUp() throws SQLException {
        database = createDatabase();
        migrateBeforePosition();
        seedAttachments();
        migrateAll();
    }

    /** 실제 MySQL 검사가 같은 마이그레이션과 줄을 쓴다. */
    Database createDatabase() {
        return new Database(
                "jdbc:h2:mem:chat-attachment-position-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
    }

    @Test
    @DisplayName("메시지마다 첨부 번호 오름차순으로 0부터 position 을 채운다")
    void backfillsPositionFromZeroPerMessageInAttachmentIdOrder() throws SQLException {
        assertThat(positionsOf(101L)).containsExactly(new Position(3L, 0), new Position(9L, 1));
        assertThat(positionsOf(202L)).containsExactly(new Position(2L, 0), new Position(7L, 1));
    }

    @Test
    @DisplayName("아직 메시지에 묶이지 않은 첨부는 기본 position 을 그대로 둔다")
    void leavesUnboundAttachmentAtDefaultPosition() throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery("SELECT position FROM chat_attachment WHERE id = 11")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getInt(1)).isZero();
        }
    }

    private void migrate(String target) {
        Flyway.configure()
                .dataSource(database.url(), database.username(), database.password())
                .locations("classpath:db/migration")
                .target(target)
                .load()
                .migrate();
    }

    private void migrateBeforePosition() {
        Flyway migrationInfo = Flyway.configure()
                .dataSource(database.url(), database.username(), database.password())
                .locations("classpath:db/migration")
                .load();
        String target = Arrays.stream(migrationInfo.info().all())
                .map(MigrationInfo::getVersion)
                .filter(version -> version != null && version.compareTo(MigrationVersion.fromVersion("75")) < 0)
                .max(MigrationVersion::compareTo)
                .orElseThrow()
                .getVersion();
        migrate(target);
    }

    private void migrateAll() {
        Flyway.configure()
                .dataSource(database.url(), database.username(), database.password())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private void seedAttachments() throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO chat_attachment
                        (id, conversation_id, message_id, uploaded_by_user_id, original_name, stored_name,
                            content_type, byte_size, expires_at, deleted_at, created_at)
                    VALUES
                        (9, 1, 101, 1, 'nine.png', '9.png', 'image/png', 1, CURRENT_TIMESTAMP, NULL, CURRENT_TIMESTAMP),
                        (3, 1, 101, 1, 'three.png', '3.png', 'image/png', 1, CURRENT_TIMESTAMP, NULL, CURRENT_TIMESTAMP),
                        (7, 1, 202, 1, 'seven.png', '7.png', 'image/png', 1, CURRENT_TIMESTAMP, NULL, CURRENT_TIMESTAMP),
                        (2, 1, 202, 1, 'two.png', '2.png', 'image/png', 1, CURRENT_TIMESTAMP, NULL, CURRENT_TIMESTAMP),
                        (11, 1, NULL, 1, 'waiting.png', '11.png', 'image/png', 1, CURRENT_TIMESTAMP, NULL, CURRENT_TIMESTAMP)
                    """);
        }
    }

    private List<Position> positionsOf(long messageId) throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT id, position FROM chat_attachment WHERE message_id = "
                        + messageId + " ORDER BY position")) {
            ArrayList<Position> positions = new ArrayList<>();
            while (rows.next()) {
                positions.add(new Position(rows.getLong(1), rows.getInt(2)));
            }
            return positions;
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(database.url(), database.username(), database.password());
    }

    record Database(String url, String username, String password) {}

    record Position(long id, int position) {}
}
