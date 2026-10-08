package com.bifos.assistant.people;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 사용자 활동 칸과 메시지 집계 색인이 Flyway 스키마에 생기는지 확인한다. */
class UserActivityMigrationTest {

    private String url;

    @BeforeEach
    void setUp() {
        url = "jdbc:h2:mem:user-activity-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .target("20261008104500")
                .load()
                .migrate();
    }

    @Test
    @DisplayName("기존 허용 목록의 마지막 로그인 시각은 비어 있고 메시지 집계 색인이 있다")
    void addsNullableLoginTimeAndMessageAggregateIndex() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO allowed_person (email, display_name, hermes_profile, enabled, created_at)
                        VALUES ('migration@example.com', '사용자', 'migration-user', TRUE, CURRENT_TIMESTAMP(6))
                        """);
            }
        }
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                ResultSet row = connection
                        .createStatement()
                        .executeQuery(
                                "SELECT last_login_at FROM allowed_person WHERE email = 'migration@example.com'")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getObject(1)).isNull();
            assertThat(indexColumns(connection.getMetaData(), "CHAT_MESSAGE", "IX_CHAT_MESSAGE_SENDER_ROLE_CREATED_AT"))
                    .containsExactly("SENDER_USER_ID", "ROLE", "CREATED_AT");
        }
    }

    private static List<String> indexColumns(DatabaseMetaData metadata, String table, String index)
            throws SQLException {
        List<String> columns = new ArrayList<>();
        try (ResultSet rows = metadata.getIndexInfo(null, null, table, false, false)) {
            while (rows.next()) {
                if (index.equalsIgnoreCase(rows.getString("INDEX_NAME"))) {
                    columns.add(rows.getShort("ORDINAL_POSITION") - 1, rows.getString("COLUMN_NAME"));
                }
            }
        }
        return columns;
    }
}
