package com.bifos.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SchemaCommentsMigrationTest {
    private static final String COMMENTS_VERSION = "20261016075220";


    String[] createDatabase() {
        return new String[] {"jdbc:h2:mem:comments-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""};
    }


    @Test
    @DisplayName("COMMENT를 달아도 기존 컬럼 정의와 데이터와 생성 컬럼 유일 제약을 보존한다")
    void preservesDefinitionsAndRowsWhileAddingComments() throws Exception {
        String[] database = createDatabase();
        migrateBeforeComments(database);
        try (var connection = DriverManager.getConnection(database[0], database[1], database[2])) {
            boolean mysql = "MySQL".equals(connection.getMetaData().getDatabaseProductName());
            execute(connection, "INSERT INTO app_user(id,email,display_name,group_id,role,created_at) VALUES(991,'schema@example.invalid','검사 사용자',1,'MEMBER',CURRENT_TIMESTAMP)");
            execute(connection, taskInsert(991));
            // 기존 COMMENT가 있는 컬럼은 낮은 시각 버전의 뒤늦은 적용으로 덮어쓰지 않는다.
            execute(connection, mysql
                ? "ALTER TABLE agent MODIFY COLUMN code VARCHAR(64) NOT NULL COMMENT '이미 있는 설명'"
                : "COMMENT ON COLUMN agent.code IS '이미 있는 설명'");
            execute(connection, mysql
                ? "ALTER TABLE agent COMMENT='이미 있는 테이블 설명'"
                : "COMMENT ON TABLE agent IS '이미 있는 테이블 설명'");
            Map<String, List<String>> before = definitions(connection, mysql);
            Map<String, String> ddlBefore = mysql ? mysqlDefinitions(connection) : Map.of();

            Flyway.configure().dataSource(database[0], database[1], database[2]).locations("classpath:db/migration")
                .outOfOrder(true).target(COMMENTS_VERSION).load().migrate();

            assertThat(definitions(connection, mysql)).isEqualTo(before);
            assertAllColumnComments(connection, mysql);
            if (mysql) {
                assertThat(mysqlDefinitions(connection)).isEqualTo(ddlBefore);
            }
            assertThat(comment(connection, "agent", "code", mysql)).isEqualTo("이미 있는 설명");
            try (var rows = connection.getMetaData().getTables(connection.getCatalog(), mysql ? null : "PUBLIC", mysql ? "agent" : "AGENT", null)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("REMARKS")).isEqualTo("이미 있는 테이블 설명");
            }
            assertThat(comment(connection, "task", "check_owner_user_id", mysql)).contains("CHECK");
            try (var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT owner_user_id,check_owner_user_id,check_agent_id,instruction FROM task WHERE id=991")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isEqualTo(991);
                assertThat(rows.getLong(2)).isEqualTo(991);
                assertThat(rows.getLong(3)).isEqualTo(991);
                assertThat(rows.getString(4)).isNull();
            }
            assertThatThrownBy(() -> execute(connection, taskInsert(992))).isInstanceOf(SQLException.class);
            assertThat(Flyway.configure().dataSource(database[0], database[1], database[2]).locations("classpath:db/migration")
                .outOfOrder(true).target(COMMENTS_VERSION).load().migrate().migrationsExecuted).isZero();
        }
    }


    @Test
    @DisplayName("낮은 시각 버전이 늦게 적용돼도 이미 지운 테이블과 컬럼은 건너뛴다")
    void skipsTablesAndColumnsAlreadyRemovedByLaterMigrations() throws Exception {
        String[] database = createDatabase();
        migrateBeforeComments(database);
        try (var connection = DriverManager.getConnection(database[0], database[1], database[2])) {
            execute(connection, "DROP TABLE model_hidden");
            execute(connection, "ALTER TABLE agent DROP COLUMN default_reasoning_effort");
            Flyway.configure().dataSource(database[0], database[1], database[2]).locations("classpath:db/migration")
                .outOfOrder(true).target(COMMENTS_VERSION).load().migrate();
            boolean mysql = "MySQL".equals(connection.getMetaData().getDatabaseProductName());
            assertAllColumnComments(connection, mysql);
        }
    }


    private static void assertAllColumnComments(Connection connection, boolean mysql) throws SQLException {
        int checked = 0;
        try (var rows = connection.getMetaData().getColumns(connection.getCatalog(), mysql ? null : "PUBLIC", null, null)) {
            while (rows.next()) {
                if (!"flyway_schema_history".equalsIgnoreCase(rows.getString("TABLE_NAME"))) {
                    assertThat(rows.getString("REMARKS")).as("COMMENT: %s.%s", rows.getString("TABLE_NAME"), rows.getString("COLUMN_NAME"))
                        .isNotBlank();
                    checked++;
                }
            }
        }
        assertThat(checked).isPositive();
    }


    private static void migrateBeforeComments(String[] database) {
        var configuration = Flyway.configure().dataSource(database[0], database[1], database[2]).locations("classpath:db/migration")
            .outOfOrder(true);
        var previous = Arrays.stream(configuration.load().info().all())
            .map(migration -> migration.getVersion())
            .filter(version -> version != null && version.compareTo(MigrationVersion.fromVersion(COMMENTS_VERSION)) < 0)
            .max(MigrationVersion::compareTo).orElseThrow();
        configuration.target(previous).load().migrate();
    }


    private static String taskInsert(long id) {
        return "INSERT INTO task(id,public_id,owner_user_id,agent_id,title,instruction,state,conversation_mode,notify,created_at,updated_at,kind) VALUES("
            + id + ",X'" + String.format("%032x", id)
            + "',991,991,'검사 예약',NULL,'ACTIVE','SINGLE','ALWAYS',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'CHECK')";
    }


    private static Map<String, List<String>> definitions(Connection connection, boolean mysql) throws SQLException {
        var result = new LinkedHashMap<String, List<String>>();
        try (var rows = connection.getMetaData().getColumns(connection.getCatalog(), mysql ? null : "PUBLIC", null, null)) {
            while (rows.next()) {
                if ("flyway_schema_history".equalsIgnoreCase(rows.getString("TABLE_NAME"))) {
                    continue;
                }
                List<String> attributes = new ArrayList<>();
                for (String field : List.of("TYPE_NAME", "COLUMN_SIZE", "DECIMAL_DIGITS", "NULLABLE", "COLUMN_DEF", "ORDINAL_POSITION",
                    "IS_AUTOINCREMENT", "IS_GENERATEDCOLUMN")) {
                    attributes.add(rows.getString(field));
                }
                result.put(rows.getString("TABLE_NAME") + "." + rows.getString("COLUMN_NAME"), attributes);
            }
        }
        assertThat(result).isNotEmpty();
        return result;
    }


    private static Map<String, String> mysqlDefinitions(Connection connection) throws SQLException {
        var result = new LinkedHashMap<String, String>();
        List<String> tables = new ArrayList<>();
        try (var statement = connection.createStatement();
            var rows = statement.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name<>'flyway_schema_history' ORDER BY table_name")) {
            while (rows.next()) {
                tables.add(rows.getString(1));
            }
        }
        for (String table : tables) {
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SHOW CREATE TABLE `" + table + "`")) {
                rows.next();
                // COMMENT를 제외한 정의 전체를 대조해 정렬 규칙과 생성식, FK, 인덱스까지 확인한다.
                result.put(table, rows.getString(2).replaceAll(" COMMENT(?:=| )'(?:''|\\\\.|[^'\\\\])*'", ""));
            }
        }
        return result;
    }


    private static String comment(Connection connection, String table, String column, boolean mysql) throws SQLException {
        try (var statement = connection.prepareStatement(mysql
            ? "SELECT column_comment FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND column_name=?"
            : "SELECT remarks FROM information_schema.columns WHERE table_schema='PUBLIC' AND LOWER(table_name)=? AND LOWER(column_name)=?")) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
        }
    }


    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }
}
