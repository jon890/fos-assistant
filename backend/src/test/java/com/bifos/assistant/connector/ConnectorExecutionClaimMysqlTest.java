package com.bifos.assistant.connector;

import com.bifos.assistant.testsupport.MysqlTestDatabase;
import org.junit.jupiter.api.Tag;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** H2와 같은 발급·소비·철회 검사를 실제 Flyway MySQL 스키마에서 실행한다. */
@Tag("mysql")
class ConnectorExecutionClaimMysqlTest extends ConnectorExecutionClaimTest {
    private static final MysqlTestDatabase DATABASE = MysqlTestDatabase.create();

    @DynamicPropertySource
    static void configureMysql(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::url);
        registry.add("spring.datasource.username", DATABASE::username);
        registry.add("spring.datasource.password", DATABASE::password);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
    }
}
