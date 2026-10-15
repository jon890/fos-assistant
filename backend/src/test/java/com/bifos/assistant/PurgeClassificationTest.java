package com.bifos.assistant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.testsupport.PurgeTable;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 일반 테스트에서도 새 테이블의 분류 누락을 잡는다. 삭제 결과는 MySQL 검사가 맡는다. */
class PurgeClassificationTest {
    @Test
    @DisplayName("Flyway가 만든 모든 테이블에 소유 범위와 삭제 기대값이 있다")
    void classifiesEveryMigratedTable() throws Exception {
        String url = "jdbc:h2:mem:purge_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        var source = new DriverManagerDataSource(url, "sa", "");
        try {
            Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
            var jdbc = new JdbcTemplate(source);
            PurgeTable.assertSchema(jdbc);
            jdbc.execute("CREATE TABLE purge_unclassified_probe (id BIGINT PRIMARY KEY, user_id BIGINT)");
            assertThatThrownBy(() -> PurgeTable.assertSchema(jdbc))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("purge_unclassified_probe", "backend/src/test/resources/purge/tables.tsv", "fixture.sql");
        } finally {
            new JdbcTemplate(source).execute("SHUTDOWN");
        }
    }
}
