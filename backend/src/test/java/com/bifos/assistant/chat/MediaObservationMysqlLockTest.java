package com.bifos.assistant.chat;

import com.bifos.assistant.testsupport.MysqlTestDatabase;
import org.junit.jupiter.api.Tag;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Flyway 스키마 검증과 사용자 행 잠금 경합을 실제 MySQL에서 실행한다. */
@Tag("mysql")
class MediaObservationMysqlLockTest extends MediaObservationLockTest {
    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        var database = MysqlTestDatabase.create();
        registry.add("spring.datasource.url", database::url);
        registry.add("spring.datasource.username", database::username);
        registry.add("spring.datasource.password", database::password);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }
}
