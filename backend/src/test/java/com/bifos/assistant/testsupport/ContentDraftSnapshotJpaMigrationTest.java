package com.bifos.assistant.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.AssistantApplication;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;

/** 엔티티 생성 스키마 대신 실제 Flyway 스키마로 H2의 JPA 기동을 확인한다. */
@BackendIntegrationTest
class ContentDraftSnapshotJpaMigrationTest {
    @Test
    @DisplayName("H2의 실제 Flyway 스키마로 snapshot과 job 엔티티를 검증한다")
    void validatesSnapshotAndJobAgainstMigratedSchema() {
        // 공유 검사 캐시에 세 번째 컨텍스트를 넣으면 기존 create-drop 컨텍스트가 닫혀 다른 검사의 DB를 지운다.
        // 실제 애플리케이션은 별도 DB로 띄우고 이 검사 안에서 닫아 캐시의 수명과 분리한다.
        var url = "jdbc:h2:mem:content-preview-migration-" + UUID.randomUUID() + ";MODE=MySQL";
        try (var context = new SpringApplicationBuilder(AssistantApplication.class, IntegrationTestDoubles.class)
            .profiles("test")
            .run("--server.port=0", "--spring.datasource.url=" + url,
                "--spring.flyway.enabled=true", "--spring.jpa.hibernate.ddl-auto=validate")) {
            var flyway = context.getBean(Flyway.class);
            var jdbc = context.getBean(JdbcTemplate.class);
            assertThat(flyway.info().pending()).isEmpty();
            assertThat(flyway.info().applied()).isNotEmpty();
            assertThat(jdbc.queryForObject("select count(*) from content_draft_snapshot", Long.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from content_draft_job", Long.class)).isZero();
        }
    }
}
