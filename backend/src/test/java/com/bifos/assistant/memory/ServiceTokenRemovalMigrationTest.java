package com.bifos.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 서비스 토큰 표를 지워도 남겨야 할 사용자와 기억은 보존하는지 H2와 MySQL에서 확인한다. */
class ServiceTokenRemovalMigrationTest {
    private static final String BEFORE_VERSION = "20261016053119";
    private static final String DROP_VERSION = "20261016074827";


    @Test
    @DisplayName("취소된 서비스 토큰·권한 표를 제거하고 사용자와 기억을 보존한다")
    void dropsServiceTokenTablesKeepingUsersAndMemory() throws SQLException {
        Database database = createDatabase();
        migrate(database, BEFORE_VERSION);
        seed(database);

        assertThat(tableCount(database, "service_token", "service_token_collection")).isEqualTo(2);
        assertThat(rowCount(database, "service_token")).isEqualTo(2);
        assertThat(rowCount(database, "service_token_collection")).isEqualTo(2);

        migrate(database, DROP_VERSION);

        assertThat(tableCount(database, "service_token", "service_token_collection")).isZero();
        assertThat(rowCount(database, "app_user")).isEqualTo(1);
        assertThat(rowCount(database, "memory")).isEqualTo(1);
        assertThat(stringValue(database, "SELECT email FROM app_user")).isEqualTo("kept@example.com");
        assertKeptMemory(database);
    }


    /** 같은 검사를 실제 MySQL에서 돌리는 하위 클래스가 바꿔 끼운다. */
    Database createDatabase() {
        return new Database(
            "jdbc:h2:mem:service-token-removal-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    }


    private static void seed(Database database) throws SQLException {
        try (Connection connection = connect(database);
            Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO app_user (id, email, display_name, group_id, role, created_at)
                    VALUES (1, 'kept@example.com', '보존 사용자', 1, 'MEMBER', CURRENT_TIMESTAMP(6))
                    """);
            statement.executeUpdate("""
                    INSERT INTO memory (
                        scope, owner_user_id, group_id, collection, entry_type, document_key, title, content,
                        retrieval, always_inject, sensitivity, revision, status, created_at, updated_at
                    ) VALUES (
                        'USER', 1, NULL, 'identity', 'DOCUMENT', 'kept-document', '보존 기억', '보존 본문',
                        'SEARCH', FALSE, 'NORMAL', 1, 'ACCEPTED', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO service_token (id, user_id, token_hash, label, created_at, expires_at, revoked_at)
                    VALUES (101, 1, 'revoked-hash-1', '취소 토큰 하나', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
                           (102, 1, 'revoked-hash-2', '취소 토큰 둘', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                    """);
            statement.executeUpdate("""
                    INSERT INTO service_token_collection (token_id, collection, allow_sensitive)
                    VALUES (101, 'identity', TRUE), (102, 'identity', FALSE)
                    """);
        }
    }


    private static void migrate(Database database, String target) {
        Flyway.configure()
            .dataSource(database.url(), database.username(), database.password())
            .locations("classpath:db/migration")
            .target(target)
            .load()
            .migrate();
    }


    private static int tableCount(Database database, String... names) throws SQLException {
        try (Connection connection = connect(database);
            ResultSet rows = connection.getMetaData().getTables(connection.getCatalog(), null, "%", new String[] {"TABLE"})) {
            int count = 0;
            while (rows.next()) {
                for (String name : names) {
                    if (name.equalsIgnoreCase(rows.getString("TABLE_NAME"))) {
                        count++;
                    }
                }
            }
            return count;
        }
    }


    private static int rowCount(Database database, String table) throws SQLException {
        try (Connection connection = connect(database);
            Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rows.next();
            return rows.getInt(1);
        }
    }


    private static String stringValue(Database database, String sql) throws SQLException {
        try (Connection connection = connect(database);
            Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }


    private static void assertKeptMemory(Database database) throws SQLException {
        try (Connection connection = connect(database);
            Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("SELECT owner_user_id, document_key, content, revision FROM memory")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getLong("owner_user_id")).isEqualTo(1L);
            assertThat(rows.getString("document_key")).isEqualTo("kept-document");
            assertThat(rows.getString("content")).isEqualTo("보존 본문");
            assertThat(rows.getInt("revision")).isEqualTo(1);
        }
    }


    private static Connection connect(Database database) throws SQLException {
        return DriverManager.getConnection(database.url(), database.username(), database.password());
    }


    record Database(String url, String username, String password) {
    }
}
