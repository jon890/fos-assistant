package com.bifos.assistant.memory;

import com.bifos.assistant.testsupport.MysqlTestDatabase;
import org.junit.jupiter.api.Tag;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Flyway 스키마의 실제 MySQL에서도 엔티티와 저장소 탐색 결과를 비교한다. */
@Tag("mysql")
class MemoryPersistencePlacementMysqlTest extends MemoryPersistencePlacementTest {
    @DynamicPropertySource
    static void useMysql(DynamicPropertyRegistry registry) {
        configureMysql(registry);
    }


    static void configureMysql(DynamicPropertyRegistry registry) {
        MysqlTestDatabase database = MysqlTestDatabase.create();
        registry.add("spring.datasource.url", database::url);
        registry.add("spring.datasource.username", database::username);
        registry.add("spring.datasource.password", database::password);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }
}

/** 수정과 승인이 동시에 들어오는 잠금 회귀를 실제 MySQL에서 재사용한다. */
@Tag("mysql")
class MemoryServiceMysqlTest extends MemoryServiceTest {
    @DynamicPropertySource
    static void useMysql(DynamicPropertyRegistry registry) {
        MemoryPersistencePlacementMysqlTest.configureMysql(registry);
    }
}

/** 사용자 key 및 암호화 결합 값 회귀를 실제 MySQL에서 재사용한다. */
@Tag("mysql")
class MemoryEncryptionMysqlTest extends MemoryEncryptionTest {
    @DynamicPropertySource
    static void useMysql(DynamicPropertyRegistry registry) {
        MemoryPersistencePlacementMysqlTest.configureMysql(registry);
    }
}
