package com.bifos.assistant.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 엔티티 생성 스키마 대신 실제 Flyway 스키마로 H2의 JPA 기동을 확인한다. */
@BackendIntegrationTest
class ContentDraftSnapshotJpaMigrationTest {
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Flyway flyway;


    @DynamicPropertySource
    static void useMigratedH2(DynamicPropertyRegistry registry) {
        var url = "jdbc:h2:mem:content-preview-migration-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }


    @Test
    @DisplayName("H2의 실제 Flyway 스키마로 snapshot과 job 엔티티를 검증한다")
    void validatesSnapshotAndJobAgainstMigratedSchema() {
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.info().applied()).isNotEmpty();
        assertThat(jdbc.queryForObject("select count(*) from content_draft_snapshot", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from content_draft_job", Long.class)).isZero();
    }
}
