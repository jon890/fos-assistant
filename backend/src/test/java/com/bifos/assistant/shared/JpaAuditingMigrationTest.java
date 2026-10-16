package com.bifos.assistant.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 실제 Flyway 업그레이드에서 알 수 없는 과거 시각을 채우지 않는지 확인한다. */
class JpaAuditingMigrationTest {

    @Test
    @DisplayName("기존 세 본체의 생성 시각과 설정을 보존하고 새 변경 시각은 NULL로 둔다")
    void preservesUnknownHistoricalModificationTimes() throws Exception {
        String url = "jdbc:h2:mem:auditing-migration-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration")
            .target("20261016094357").load().migrate();
        try (var connection = DriverManager.getConnection(url, "sa", ""); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO app_user (email, display_name, group_id, role, created_at)
                    VALUES ('audit@example.test', '검사', 1, 'ADMIN', TIMESTAMP '2020-01-01 00:00:00')
                    """);
            statement.executeUpdate("""
                    INSERT INTO allowed_person (email, display_name, hermes_profile, enabled, created_at)
                    VALUES ('audit@example.test', '검사', 'audit', TRUE, TIMESTAMP '2020-01-01 00:00:00')
                    """);
            statement.executeUpdate("""
                    INSERT INTO agent (code, name, hermes_profile, api_base_url, cost_mode, credential_scope, visibility, enabled, created_at)
                    VALUES ('audit', '검사', 'audit', 'http://runtime.test', 'API', 'DEDICATED', 'GROUP', TRUE, TIMESTAMP '2020-01-01 00:00:00')
                    """);
        }
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load().migrate();
        try (var connection = DriverManager.getConnection(url, "sa", ""); var statement = connection.createStatement()) {
            for (String table : List.of("agent", "app_user", "allowed_person")) {
                try (var row = statement.executeQuery("select created_at, updated_at from " + table)) {
                    assertThat(row.next()).as(table).isTrue();
                    assertThat(row.getTimestamp(1).toLocalDateTime()).isEqualTo(LocalDateTime.of(2020, 1, 1, 0, 0));
                    assertThat(row.getObject(2)).isNull();
                }
            }
        }
    }


    @Test
    @DisplayName("빈 DB에서 전체 Flyway를 적용해도 새 세 감사 칸은 NULL을 허용하고 자동 기본값이 없다")
    void createsNullableAuditColumnsOnFreshSchema() throws Exception {
        String url = "jdbc:h2:mem:auditing-fresh-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load().migrate();
        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            for (String table : List.of("AGENT", "APP_USER", "ALLOWED_PERSON")) {
                try (var columns = connection.getMetaData().getColumns(null, null, table, "UPDATED_AT")) {
                    assertThat(columns.next()).as(table).isTrue();
                    assertThat(columns.getString("IS_NULLABLE")).isEqualTo("YES");
                    assertThat(columns.getString("COLUMN_DEF")).isNull();
                }
            }
        }
    }
}
