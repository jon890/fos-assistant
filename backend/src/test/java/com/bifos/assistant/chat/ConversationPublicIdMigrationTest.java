package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * V23 이 이미 있는 대화마다 서로 다른 v4 공개 식별자를 채우는지 본다.
 *
 * <p>다른 검사들은 엔티티로 스키마를 만들어 이 채움을 한 번도 지나지 않는다. 채우지 못하면 배포할 때
 * NOT NULL 로 바꾸는 단계에서 실패한다.
 */
class ConversationPublicIdMigrationTest {

    @Test
    @DisplayName("V23 이 이미 있는 대화마다 서로 다른 v4 식별자를 채운다")
    void v23FillsDistinctV4IdsForExistingConversations() throws SQLException {
        String url = "jdbc:h2:mem:migration-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";

        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .target("22")
                .load()
                .migrate();
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO conversation (user_id, title, created_at, updated_at) VALUES
                        (1, '첫 대화', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
                        (1, '둘째 대화', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                    """);
        }

        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .load()
                .migrate();

        List<UUID> filled = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT public_id FROM conversation ORDER BY id")) {
            while (rows.next()) {
                byte[] bytes = rows.getBytes(1);
                assertThat(bytes).hasSize(16);
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                filled.add(new UUID(buffer.getLong(), buffer.getLong()));
            }
        }
        assertThat(filled).hasSize(2).doesNotHaveDuplicates();
        assertThat(filled).allSatisfy(id -> {
            assertThat(id.version()).isEqualTo(4);
            assertThat(id.variant()).isEqualTo(2);
        });
    }
}
