package com.bifos.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * V54 가 서비스 토큰 표를 만들고 해시의 유일성과 사용자 외래 키를 지키는지 본다(ADR-056).
 *
 * <p>다른 검사들은 엔티티로 스키마를 만들어 이 제약을 지나지 않는다.
 */
class ServiceTokenMigrationTest {

    private String url;

    @BeforeEach
    void migrateToV54() throws SQLException {
        url = "jdbc:h2:mem:migration-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        migrate("53");
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO app_user (email, display_name, group_id, role, created_at)
                    VALUES ('dad@example.com', 'Dad', 1, 'ADMIN', CURRENT_TIMESTAMP(6))
                    """);
        }
        migrate("54");
    }

    @Test
    @DisplayName("토큰 줄과 그 토큰이 받는 collection 줄을 넣을 수 있다")
    void insertsTokenAndCollection() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(token(userId(statement), "hash-1"));
            long tokenId = tokenId(statement, "hash-1");
            statement.executeUpdate("INSERT INTO service_token_collection (token_id, collection, allow_sensitive)"
                    + " VALUES (" + tokenId + ", 'identity', TRUE)");

            var rows = statement.executeQuery("SELECT COUNT(*) FROM service_token_collection");
            rows.next();
            assertThat(rows.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("같은 해시를 한 번 더 넣으면 실패한다")
    void rejectsDuplicateHash() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            long userId = userId(statement);
            statement.executeUpdate(token(userId, "hash-1"));

            assertThatThrownBy(() -> statement.executeUpdate(token(userId, "hash-1")))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    @DisplayName("없는 사용자의 토큰은 넣지 못한다")
    void rejectsUnknownUser() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate(token(9_999L, "hash-2")))
                    .isInstanceOf(SQLException.class);
        }
    }

    private static String token(long userId, String hash) {
        return """
                INSERT INTO service_token (user_id, token_hash, label, created_at, expires_at)
                VALUES (%d, '%s', 'career-os', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """.formatted(userId, hash);
    }

    private static long userId(Statement statement) throws SQLException {
        var rows = statement.executeQuery("SELECT id FROM app_user WHERE email = 'dad@example.com'");
        rows.next();
        return rows.getLong(1);
    }

    private static long tokenId(Statement statement, String hash) throws SQLException {
        var rows = statement.executeQuery("SELECT id FROM service_token WHERE token_hash = '" + hash + "'");
        rows.next();
        return rows.getLong(1);
    }

    private void migrate(String target) {
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .target(target)
                .load()
                .migrate();
    }
}
