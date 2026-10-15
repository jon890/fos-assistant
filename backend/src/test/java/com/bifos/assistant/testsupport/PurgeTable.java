package com.bifos.assistant.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/** 모든 테이블의 분류와 검사 행의 키, 삭제 경로별 기대값을 읽는다. */
public record PurgeTable(String table, Scope scope, String fixtureKey, Outcome user,
    Outcome conversation, Outcome agent, String reason) {


    public static final String RESOURCE = "purge/tables.tsv";

    public enum Scope {
        USER,
        CONVERSATION,
        AGENT,
        GLOBAL
    }

    public enum Outcome {
        DELETE,
        KEEP,
        SCRUB,
        DETACH,
        GONE,
        GAP
    }


    public static List<PurgeTable> readAll() throws IOException {
        String content = new ClassPathResource(RESOURCE).getContentAsString(StandardCharsets.UTF_8);
        Map<String, PurgeTable> tables = new LinkedHashMap<>();
        for (String line : content.lines().filter(value -> !value.isBlank() && !value.startsWith("#")).toList()) {
            String[] fields = line.split("\\|", -1);
            assertThat(fields).as("%s의 분류는 7칸이어야 한다: %s", RESOURCE, line).hasSize(7);
            PurgeTable entry = new PurgeTable(fields[0], Scope.valueOf(fields[1]), fields[2],
                Outcome.valueOf(fields[3]), Outcome.valueOf(fields[4]), Outcome.valueOf(fields[5]), fields[6]);
            assertThat(entry.table()).matches("[a-z_]+");
            assertThat(entry.fixtureKey()).matches("[a-z_]+");
            assertThat(entry.reason()).as("%s의 소유 관계와 예외 근거", entry.table()).isNotBlank();
            assertThat(tables.put(entry.table(), entry)).as("%s의 중복 분류", entry.table()).isNull();
            assertThat(entry.user()).as("사용자 purge 부재는 개인 데이터의 알려진 공백이다")
                .isEqualTo(entry.scope() == Scope.GLOBAL ? Outcome.KEEP : Outcome.GAP);
        }
        assertThat(tables).as("빈 테이블 분류는 허용하지 않는다").isNotEmpty();
        return List.copyOf(tables.values());
    }


    /** H2와 실제 MySQL 모두 Flyway 적용 결과의 테이블 목록을 대조한다. */
    public static void assertSchema(JdbcTemplate jdbc) throws IOException {
        String schema = jdbc.execute((ConnectionCallback<String>)connection -> connection.getMetaData().getDatabaseProductName().equals("MySQL")
            ? connection.getCatalog() : connection.getSchema());
        List<String> actual = jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE LOWER(table_schema) = LOWER(?) AND table_type = 'BASE TABLE'
                  AND LOWER(table_name) <> 'flyway_schema_history'
                """, String.class, schema).stream()
            .map(name -> name.toLowerCase(Locale.ROOT)).toList();
        List<String> classified = readAll().stream().map(PurgeTable::table).toList();
        assertThat(actual).as("스키마가 비어 있으면 삭제 검사를 할 수 없다").isNotEmpty();
        assertThat(classified)
            .as("새 테이블은 backend/src/test/resources/%s에 소유 범위와 삭제 기대값·근거를 적고 "
                + "purge/fixture.sql에 행을 더한다. 없어진 테이블의 분류는 지운다", RESOURCE)
            .containsExactlyInAnyOrderElementsOf(actual);
        for (PurgeTable table : readAll()) {
            List<String> columns = jdbc.queryForList("""
                    SELECT column_name FROM information_schema.columns
                    WHERE LOWER(table_schema) = LOWER(?) AND LOWER(table_name) = ?
                    """, String.class, schema, table.table()).stream()
                .map(name -> name.toLowerCase(Locale.ROOT)).toList();
            assertThat(columns).as("%s의 fixture 행을 고르는 칸", table.table()).contains(table.fixtureKey());
            if (table.scope() == Scope.GLOBAL) {
                assertThat(columns).as("사용자·대화·에이전트에 속하는 칸을 추가하면 %s의 GLOBAL 분류를 재검토한다", table.table())
                    .doesNotContain("user_id", "owner_user_id", "conversation_id", "agent_id");
            }
        }
    }
}
