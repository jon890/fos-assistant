package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
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
 * 대기 메시지 표가 마이그레이션으로 생기는지 본다.
 *
 * <p>테스트 DB 는 엔티티로 스키마를 만들므로, 운영과 같은 Flyway 스키마는 여기서 따로 확인한다.
 */
class ChatPendingMessageMigrationTest {
    private String url;

    @BeforeEach
    void setUp() {
        url = "jdbc:h2:mem:chat-pending-message-migration-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    @DisplayName("대기 메시지 표에는 비어 있을 수 없는 여섯 칸이 있고 held 의 기본값은 거짓이다")
    void createsSixNotNullColumnsWithHeldDefaultingToFalse() throws SQLException {
        Map<String, String> nullableByColumn = new LinkedHashMap<>();
        String heldDefault = null;
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                ResultSet columns = connection.getMetaData().getColumns(null, null, "CHAT_PENDING_MESSAGE", null)) {
            while (columns.next()) {
                String name = columns.getString("COLUMN_NAME");
                nullableByColumn.put(name, columns.getString("IS_NULLABLE"));
                if ("HELD".equals(name)) {
                    heldDefault = columns.getString("COLUMN_DEF");
                }
            }
        }

        assertThat(nullableByColumn.keySet())
                .as("chat_pending_message 의 칸")
                .containsExactlyInAnyOrder("ID", "CONVERSATION_ID", "USER_ID", "CONTENT", "HELD", "CREATED_AT");
        assertThat(nullableByColumn.values())
                .as("칸마다 NULL 허용 여부 %s", nullableByColumn)
                .containsOnly("NO");
        assertThat(heldDefault).as("held 의 기본값").isEqualToIgnoringCase("FALSE");
    }

    @Test
    @DisplayName("held 를 주지 않고 넣은 대기 메시지는 멈추지 않은 것으로 저장된다")
    void storesRowWithoutHeldAsNotHeld() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO chat_pending_message (conversation_id, user_id, content, created_at)
                    VALUES (1, 1, 'queued', TIMESTAMP '2026-01-01 00:00:00')
                    """);

            try (ResultSet row = statement.executeQuery("SELECT held FROM chat_pending_message")) {
                assertThat(row.next()).as("넣은 대기 메시지").isTrue();
                assertThat(row.getBoolean(1)).as("held").isFalse();
            }
        }
    }

    @Test
    @DisplayName("대화별 색인의 칸 순서는 conversation id, id 다")
    void indexesConversationIdThenId() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            DatabaseMetaData meta = connection.getMetaData();

            assertThat(indexColumns(meta, "CHAT_PENDING_MESSAGE", "IDX_CHAT_PENDING_MESSAGE_CONVERSATION"))
                    .containsExactly("CONVERSATION_ID", "ID");
        }
    }

    @Test
    @DisplayName("content 가 NULL 인 대기 메시지는 거절된다")
    void rejectsNullContent() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate("""
                            INSERT INTO chat_pending_message (conversation_id, user_id, content, created_at)
                            VALUES (1, 1, NULL, TIMESTAMP '2026-01-01 00:00:00')
                            """)).isInstanceOf(SQLException.class);

            try (ResultSet count = statement.executeQuery("SELECT COUNT(*) FROM chat_pending_message")) {
                assertThat(count.next()).isTrue();
                assertThat(count.getInt(1)).as("거절된 뒤 남은 행 수").isZero();
            }
        }
    }

    /** 그 색인의 칸을 색인 안 순서대로 돌려준다. */
    private static List<String> indexColumns(DatabaseMetaData meta, String table, String index) throws SQLException {
        List<String> result = new ArrayList<>();
        try (ResultSet rows = meta.getIndexInfo(null, null, table, false, false)) {
            while (rows.next()) {
                if (index.equalsIgnoreCase(rows.getString("INDEX_NAME"))) {
                    result.add(rows.getShort("ORDINAL_POSITION") - 1, rows.getString("COLUMN_NAME"));
                }
            }
        }
        return result;
    }
}
