package com.bifos.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.testsupport.MysqlTestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** rule-85: Flyway 적용 뒤 실제 식별자를 INFORMATION_SCHEMA에서 읽는다. */
@Tag("mysql")
class SchemaIdentifiersMysqlTest {
    @Test
    @DisplayName("Flyway를 끝까지 적용한 모든 테이블과 컬럼 이름은 lower_snake_case다")
    void migratedSchemaHasNoViolations() throws Exception {
        var database = MysqlTestDatabase.create();
        Flyway.configure().dataSource(database.url(), database.username(), database.password())
            .locations("classpath:db/migration").outOfOrder(true).load().migrate();
        try (var connection = DriverManager.getConnection(database.url(), database.username(), database.password())) {
            assertThat(violations(connection)).isEmpty();
        }
    }


    @Test
    @DisplayName("실제 DDL의 정상 이름을 허용하고 테이블과 컬럼 이름 위반을 구별한다")
    void checksValidAndInvalidFixtures() throws Exception {
        var database = MysqlTestDatabase.create();
        try (var connection = DriverManager.getConnection(database.url(), database.username(), database.password());
            var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE valid_table (id BIGINT, value_2 VARCHAR(10))");
            assertThat(violations(connection)).isEmpty();
            statement.executeUpdate("CREATE TABLE `InvalidTable` (id BIGINT)");
            statement.executeUpdate("CREATE TABLE invalid_column (`camelCase` BIGINT, `Upper` BIGINT, `double__underscore` BIGINT, `trailing_` BIGINT)");
            assertThat(violations(connection)).containsExactlyInAnyOrder(
                "TABLE: InvalidTable", "COLUMN: invalid_column.camelCase", "COLUMN: invalid_column.Upper",
                "COLUMN: invalid_column.double__underscore", "COLUMN: invalid_column.trailing_");
        }
    }


    private static List<String> violations(Connection connection) throws Exception {
        List<String> violations = new ArrayList<>();
        int tables = 0;
        int columns = 0;
        try (var statement = connection.createStatement();
            var rows = statement.executeQuery("SELECT table_name, column_name FROM information_schema.columns WHERE table_schema=DATABASE() ORDER BY table_name, ordinal_position")) {
            String previous = null;
            while (rows.next()) {
                String table = rows.getString(1);
                String column = rows.getString(2);
                if (!table.equals(previous)) {
                    tables++;
                    check(violations, table, "TABLE: " + table);
                    previous = table;
                }
                columns++;
                check(violations, column, "COLUMN: " + table + "." + column);
            }
        }
        assertThat(tables).isPositive();
        assertThat(columns).isPositive();
        return violations;
    }


    private static void check(List<String> violations, String name, String label) {
        if (!name.matches("[a-z][a-z0-9]*(?:_[a-z0-9]+)*")) {
            violations.add(label);
        }
    }
}
