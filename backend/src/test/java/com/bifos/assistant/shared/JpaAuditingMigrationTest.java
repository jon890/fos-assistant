package com.bifos.assistant.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 빈 DB 에 Flyway 를 끝까지 적용한 스키마의 감사 칸을 확인한다. 기존 행 업그레이드 검사는 운영 적용 14일 뒤 지웠다. */
class JpaAuditingMigrationTest {

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
