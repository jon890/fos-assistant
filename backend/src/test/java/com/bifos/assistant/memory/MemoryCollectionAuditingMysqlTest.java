package com.bifos.assistant.memory;

import com.bifos.assistant.testsupport.MysqlTestDatabase;
import org.junit.jupiter.api.Tag;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 동일한 callback·merge·rollback 계약을 Flyway 스키마의 실제 MySQL로 확인한다. */
@Tag("mysql")
class MemoryCollectionAuditingMysqlTest extends MemoryCollectionAuditingTest {
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
