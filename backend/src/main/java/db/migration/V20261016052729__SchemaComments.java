package db.migration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * 현재 컬럼 정의를 보존하면서 테이블과 컬럼에 한국어 설명을 단다.
 * MySQL은 SHOW CREATE TABLE의 정의를 그대로 쓰고, H2는 COMMENT ON을 쓴다.
 */
public class V20261016052729__SchemaComments extends BaseJavaMigration {
    private static final String RESOURCE = "/db/comments/V20261016052729.tsv";


    @Override
    public Integer getChecksum() {
        var checksum = new CRC32();
        checksum.update(readResource());
        return (int)checksum.getValue();
    }


    @Override
    public boolean canExecuteInTransaction() {
        return false;
    }


    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        String product = connection.getMetaData().getDatabaseProductName();
        boolean mysql = "MySQL".equals(product);
        if (!mysql && !"H2".equals(product)) {
            throw new SQLException("COMMENT 마이그레이션이 지원하지 않는 데이터베이스: " + product);
        }
        for (var table : descriptions().entrySet()) {
            String name = table.getKey();
            String metadataName = mysql ? name : name.toUpperCase(Locale.ROOT);
            var columns = new LinkedHashMap<String, String>();
            try (var rows = connection.getMetaData().getColumns(connection.getCatalog(), mysql ? null : "PUBLIC", metadataName, null)) {
                while (rows.next()) {
                    if (metadataName.equalsIgnoreCase(rows.getString("TABLE_NAME"))) {
                        columns.put(rows.getString("COLUMN_NAME").toLowerCase(Locale.ROOT), rows.getString("REMARKS"));
                    }
                }
            }
            // 낮은 시각 버전이 늦게 적용될 때 뒤의 마이그레이션이 이미 지운 표와 칸은 건너뛴다.
            if (columns.isEmpty()) {
                continue;
            }
            if (mysql) {
                commentMysql(connection, name, table.getValue(), columns);
            } else {
                execute(connection, "COMMENT ON TABLE " + quoted(name) + " IS " + literal(table.getValue().get("*")));
                for (var column : table.getValue().entrySet()) {
                    String existing = columns.get(column.getKey());
                    if (columns.containsKey(column.getKey()) && (existing == null || existing.isBlank())) {
                        execute(connection, "COMMENT ON COLUMN " + quoted(name) + "." + quoted(column.getKey()) + " IS " + literal(column.getValue()));
                    }
                }
            }
        }
    }


    private static void commentMysql(Connection connection, String table, Map<String, String> descriptions,
        Map<String, String> existingComments) throws SQLException {
        List<String> clauses = new ArrayList<>();
        clauses.add("COMMENT = " + literal(descriptions.get("*")));
        long expected = existingComments.entrySet().stream()
            .filter(column -> descriptions.containsKey(column.getKey()))
            .filter(column -> column.getValue() == null || column.getValue().isBlank())
            .count();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SHOW CREATE TABLE " + quoted(table))) {
            rows.next();
            for (String line : rows.getString(2).split("\n")) {
                String definition = line.strip();
                if (!definition.startsWith("`")) {
                    continue;
                }
                String column = definition.substring(1, definition.indexOf('`', 1));
                String description = descriptions.get(column);
                String existing = existingComments.get(column);
                // 뒤의 마이그레이션이 이미 붙인 설명은 보존한다.
                if (description == null || (existing != null && !existing.isBlank())) {
                    continue;
                }
                if (definition.endsWith(",")) {
                    definition = definition.substring(0, definition.length() - 1);
                }
                clauses.add("MODIFY COLUMN " + definition + " COMMENT " + literal(description));
            }
        }
        if (clauses.size() != expected + 1) {
            throw new SQLException("SHOW CREATE TABLE에서 COMMENT 대상 컬럼을 모두 찾지 못했다: " + table);
        }
        // 컬럼을 하나씩 ALTER하지 않고 테이블마다 ALTER를 한 번만 실행한다.
        execute(connection, "ALTER TABLE " + quoted(table) + " " + String.join(", ", clauses));
    }


    private static Map<String, Map<String, String>> descriptions() {
        var result = new LinkedHashMap<String, Map<String, String>>();
        for (String line : new String(readResource(), StandardCharsets.UTF_8).lines().toList()) {
            String[] fields = line.split("\t", -1);
            if (fields.length != 3 || !fields[0].matches("[a-z][a-z0-9_]*") || !fields[1].matches("\\*|[a-z][a-z0-9_]*") || fields[2].isBlank()
                || fields[2].contains("\\")) {
                throw new IllegalStateException("스키마 COMMENT 목록 형식이 잘못됐다: " + line);
            }
            if (result.computeIfAbsent(fields[0], ignored -> new LinkedHashMap<>()).putIfAbsent(fields[1], fields[2]) != null) {
                throw new IllegalStateException("스키마 COMMENT가 중복됐다: " + fields[0] + "." + fields[1]);
            }
        }
        if (result.values().stream().anyMatch(columns -> !columns.containsKey("*"))) {
            throw new IllegalStateException("테이블 COMMENT가 빠졌다");
        }
        return result;
    }


    private static byte[] readResource() {
        try (var input = V20261016052729__SchemaComments.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("스키마 COMMENT 목록이 없다: " + RESOURCE);
            }
            return input.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }


    private static String quoted(String identifier) {
        return "`" + identifier + "`";
    }


    private static String literal(String text) {
        return "'" + text.replace("'", "''") + "'";
    }


    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
