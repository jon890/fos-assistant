package com.bifos.assistant.chat;

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
 * V24 가 결과물 표를 만들고, 같은 답에 같은 경로를 두 번 묶지 못하게 하는지 본다.
 *
 * <p>다른 검사들은 엔티티로 스키마를 만들어 이 마이그레이션을 지나지 않는다.
 */
class ChatArtifactMigrationTest {

    @Test
    @DisplayName("V24 가 만든 표는 같은 답의 같은 경로를 두 번 받지 않고 다른 답에는 받는다")
    void tableFromV24RejectsSamePathTwiceForSameReplyButAllowsOtherReply() throws SQLException {
        String url = "jdbc:h2:mem:migration-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(insert(10L));
            statement.executeUpdate(insert(11L));

            assertThatThrownBy(() -> statement.executeUpdate(insert(10L)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("UK_CHAT_ARTIFACT_MESSAGE_PATH");
            try (ResultSet rows = statement.executeQuery(
                    "SELECT COUNT(*) FROM chat_artifact WHERE conversation_id = 1 AND deleted_at IS NULL")) {
                rows.next();
                assertThat(rows.getLong(1)).isEqualTo(2);
            }
        }
    }

    private static String insert(Long messageId) {
        return "INSERT INTO chat_artifact (conversation_id, message_id, path, byte_size, created_at) " + "VALUES (1, "
                + messageId + ", '초안/index.html', 42, CURRENT_TIMESTAMP(6))";
    }
}
