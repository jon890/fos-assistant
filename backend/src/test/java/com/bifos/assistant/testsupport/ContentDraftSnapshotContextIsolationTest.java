package com.bifos.assistant.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContextManager;

/** 실제 기동 검사가 기존 통합 검사 컨텍스트와 스키마를 닫지 않는지 확인한다. */
class ContentDraftSnapshotContextIsolationTest {
    @BackendIntegrationTest
    static class SharedContext {}

    static class ClaimContext extends ConnectorExecutionClaimTestSupport {}

    @Test
    @DisplayName("Flyway 기동과 종료 뒤에도 공유 검사와 claim 검사의 스키마가 살아 있다")
    void preservesCachedSchemasAcrossMigratedStartup() throws Exception {
        var shared = new TestContextManager(SharedContext.class).getTestContext().getApplicationContext();
        var claim = new TestContextManager(ClaimContext.class).getTestContext().getApplicationContext();
        var sharedJdbc = shared.getBean(JdbcTemplate.class);
        var claimJdbc = claim.getBean(JdbcTemplate.class);
        long before = claimJdbc.queryForObject("select count(*) from app_user", Long.class);

        var migration = new ContentDraftSnapshotJpaMigrationTest();
        new TestContextManager(ContentDraftSnapshotJpaMigrationTest.class).prepareTestInstance(migration);
        migration.validatesSnapshotAndJobAgainstMigratedSchema();

        assertThat(claimJdbc.queryForObject("select count(*) from app_user", Long.class)).isEqualTo(before);
        assertThat(sharedJdbc.queryForObject("select count(*) from app_user", Long.class)).isEqualTo(before);
    }
}
