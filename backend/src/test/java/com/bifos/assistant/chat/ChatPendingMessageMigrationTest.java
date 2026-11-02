package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.chat.conversation.application.PendingMessageStore;
import com.bifos.assistant.chat.conversation.domain.PendingMessage;
import com.bifos.assistant.chat.conversation.domain.PendingMessages;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 대기 메시지를 {@code chat_pending_message} 에서 {@code conversation.pending_messages} 로 옮기는 마이그레이션을 본다
 * (ADR-20261102 / table-reduce-r2).
 *
 * <p>테스트 DB 는 엔티티로 스키마를 만들므로 운영과 같은 Flyway 스키마는 여기서 따로 만든다. 옮긴 JSON 은 Control Plane 이
 * 쓰는 {@link PendingMessageStore} 의 해석으로 읽어 두 쪽의 모양이 같은지도 함께 본다.
 */
class ChatPendingMessageMigrationTest {

    /** 칸을 더한 DDL 이다. 옮기는 마이그레이션 바로 앞 버전이다. */
    private static final String BEFORE = "20261102074314";


    /** 같은 검사를 실제 MySQL 에서 돌리는 하위 클래스가 바꿔 끼운다. */
    Database createDatabase() {
        return new Database(
            "jdbc:h2:mem:chat-pending-message-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    }


    @Test
    @DisplayName("대화마다 옛 번호 순서로 JSON 하나로 옮기고 한 행이라도 멈췄으면 줄이 멈추며 옮긴 행은 옛 표에서 지운다")
    void movesRowsIntoOneQueuePerConversation() throws SQLException {
        Database database = createDatabase();
        migrate(database, BEFORE);
        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            insertConversations(statement, 1, 2, 3);
            statement.executeUpdate("""
                    INSERT INTO chat_pending_message (id, conversation_id, user_id, content, held, created_at) VALUES
                        (12, 1, 7, '둘째', FALSE, '2026-09-30 00:00:02.000002'),
                        (11, 1, 7, '첫째', FALSE, '2026-09-30 00:00:01.000001'),
                        (13, 2, 7, '멈춘 글', TRUE, '2026-09-30 00:00:03'),
                        (14, 2, 7, '뒤 글', FALSE, '2026-09-30 00:00:04')
                    """);
        }

        migrate(database, null);

        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            assertThat(queueOf(statement, 1)).isEqualTo(new PendingMessages(false, List.of(
                new PendingMessage(11L, "첫째", Instant.parse("2026-09-30T00:00:01.000001Z")),
                new PendingMessage(12L, "둘째", Instant.parse("2026-09-30T00:00:02.000002Z")))));
            PendingMessages held = queueOf(statement, 2);
            assertThat(held.held()).isTrue();
            assertThat(held.ids()).containsExactly(13L, 14L);
            assertThat(strings(statement, "SELECT pending_messages FROM conversation WHERE id = 3"))
                .as("대기 행이 없던 대화")
                .containsExactly((String)null);
            assertThat(strings(statement, "SELECT COUNT(*) FROM chat_pending_message"))
                .as("옮긴 행은 직전 이미지가 다시 보내지 않게 지운다")
                .containsExactly("0");
        }
    }


    @Test
    @DisplayName("대화 줄이 없는 행은 옮기지 않고 옛 표에 남긴다")
    void keepsRowsWithoutConversation() throws SQLException {
        Database database = createDatabase();
        migrate(database, BEFORE);
        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            insertConversations(statement, 1);
            statement.executeUpdate("""
                    INSERT INTO chat_pending_message (id, conversation_id, user_id, content, held, created_at) VALUES
                        (21, 1, 7, '보낼 글', FALSE, '2026-09-30 00:00:01'),
                        (22, 99, 7, '갈 곳 없는 글', FALSE, '2026-09-30 00:00:02')
                    """);
        }

        migrate(database, null);

        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            assertThat(queueOf(statement, 1).ids()).containsExactly(21L);
            assertThat(strings(statement, "SELECT id FROM chat_pending_message")).containsExactly("22");
        }
    }


    @Test
    @DisplayName("대화 줄에 이미 대기 줄이 있으면 덮어쓰지 않고 멈춘다")
    void refusesToOverwriteExistingQueue() throws SQLException {
        Database database = createDatabase();
        migrate(database, BEFORE);
        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            insertConversations(statement, 1);
            statement.executeUpdate("UPDATE conversation SET pending_messages = '{}' WHERE id = 1");
            statement.executeUpdate("INSERT INTO chat_pending_message (id, conversation_id, user_id, content, held, created_at)"
                + " VALUES (31, 1, 7, '글', FALSE, '2026-09-30 00:00:01')");
        }

        assertThatThrownBy(() -> migrate(database, null))
            .isInstanceOf(RuntimeException.class)
            .hasRootCauseInstanceOf(IllegalStateException.class);

        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            assertThat(strings(statement, "SELECT pending_messages FROM conversation WHERE id = 1")).containsExactly("{}");
            assertThat(strings(statement, "SELECT COUNT(*) FROM chat_pending_message")).containsExactly("1");
        }
    }


    private static void insertConversations(Statement statement, int... ids) throws SQLException {
        for (int id : ids) {
            statement.executeUpdate("INSERT INTO conversation (id, user_id, title, created_at, updated_at, public_id, auto_turn_count)"
                + " VALUES (" + id + ", 7, '대화', '2026-09-30 00:00:00', '2026-09-30 00:00:00', X'"
                + "%032x".formatted(id) + "', 0)");
        }
    }


    private static PendingMessages queueOf(Statement statement, long conversationId) throws SQLException {
        try (ResultSet rows = statement.executeQuery("SELECT pending_messages FROM conversation WHERE id = " + conversationId)) {
            assertThat(rows.next()).as("%d번 대화", conversationId).isTrue();
            return PendingMessageStore.decode(rows.getString(1));
        }
    }


    private static void migrate(Database database, String target) {
        var configuration = Flyway.configure()
            .dataSource(database.url(), database.username(), database.password())
            .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }


    private static List<String> strings(Statement statement, String query) throws SQLException {
        List<String> values = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery(query)) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }


    private static Connection connect(Database database) throws SQLException {
        return DriverManager.getConnection(database.url(), database.username(), database.password());
    }


    record Database(String url, String username, String password) {
    }
}
