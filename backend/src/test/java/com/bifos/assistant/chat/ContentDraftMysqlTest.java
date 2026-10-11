package com.bifos.assistant.chat;

import com.mysql.cj.jdbc.Driver;
import com.bifos.assistant.testsupport.MysqlTestDatabase;
import org.junit.jupiter.api.Tag;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** H2와 같은 실제 서비스 회귀를 Flyway MySQL 스키마에서도 실행한다. */
@Tag("mysql")
class ContentDraftMysqlTest extends ContentDraftServiceTest {
    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        var database = MysqlTestDatabase.create();
        registry.add("spring.datasource.url", database::url);
        registry.add("spring.datasource.username", database::username);
        registry.add("spring.datasource.password", database::password);
        registry.add("spring.datasource.driver-class-name", () -> "Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }
}
