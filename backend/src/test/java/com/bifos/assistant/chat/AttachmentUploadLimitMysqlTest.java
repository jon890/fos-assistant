package com.bifos.assistant.chat;

import com.bifos.assistant.testsupport.MysqlTestDatabase;
import org.junit.jupiter.api.Tag;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** {@link AttachmentUploadLimitTest} 의 실제 HTTP 동시 upload 검사를 MySQL에서 함께 돌린다. */
@Tag("mysql")
class AttachmentUploadLimitMysqlTest extends AttachmentUploadLimitTest {

    private static MysqlTestDatabase database;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        database = MysqlTestDatabase.create();
        registry.add("spring.datasource.url", database::url);
        registry.add("spring.datasource.username", database::username);
        registry.add("spring.datasource.password", database::password);
    }
}
