package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.util.Arrays;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AttachmentDeletionRequestMigrationTest {
    @Test
    @DisplayName("새 마이그레이션은 기존 첨부를 미요청으로 유지하고 요청과 완료 시각을 구분한다")
    void addsRequestWithoutMarkingCompleted() throws Exception {
        String url = "jdbc:h2:mem:attachment-deletion-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        Flyway info = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .load();
        String previous = Arrays.stream(info.info().all())
                .map(MigrationInfo::getVersion)
                .filter(version ->
                        version != null && version.compareTo(MigrationVersion.fromVersion("20261010004638")) < 0)
                .max(MigrationVersion::compareTo)
                .orElseThrow()
                .getVersion();
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .target(previous)
                .load()
                .migrate();
        try (var connection = DriverManager.getConnection(url, "sa", "");
                var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    insert into chat_attachment(id,conversation_id,uploaded_by_user_id,original_name,stored_name,
                      content_type,byte_size,position,expires_at,created_at)
                    values(1,1,1,'a.gif','1.gif','image/gif',9,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """);
        }
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        try (var connection = DriverManager.getConnection(url, "sa", "");
                var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("select deletion_requested_at,deleted_at from chat_attachment")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getTimestamp(1)).isNull();
                assertThat(rows.getTimestamp(2)).isNull();
            }
            statement.executeUpdate("update chat_attachment set deletion_requested_at=CURRENT_TIMESTAMP where id=1");
            try (var rows = statement.executeQuery("select deletion_requested_at,deleted_at from chat_attachment")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getTimestamp(1)).isNotNull();
                assertThat(rows.getTimestamp(2)).isNull();
            }
        }
    }
}
