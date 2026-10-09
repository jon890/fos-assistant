package com.bifos.assistant.agent;

import com.bifos.assistant.testsupport.MysqlTestDatabase;
import org.junit.jupiter.api.Tag;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** MySQL의 REPEATABLE READ에서 결정 경합과 조건부 갱신을 실제 트랜잭션으로 검증한다. */
@Tag("mysql")
class ToolsetRequestFlowMysqlTest extends ToolsetRequestFlowTest {
    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        MysqlTestDatabase database = MysqlTestDatabase.create();
        registry.add("spring.datasource.url", database::url);
        registry.add("spring.datasource.username", database::username);
        registry.add("spring.datasource.password", database::password);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }
}
