package com.bifos.assistant.agent;

import com.bifos.assistant.testsupport.MysqlTestDatabase;
import org.junit.jupiter.api.Tag;

/** 대기 요청의 유일 제약과 NULL 처리도 실제 MySQL에서 확인한다. */
@Tag("mysql")
class ToolsetRequestMysqlMigrationTest extends ToolsetRequestMigrationTest {
    @Override
    Database createDatabase() {
        MysqlTestDatabase mysql = MysqlTestDatabase.create();
        return new Database(mysql.url(), mysql.username(), mysql.password());
    }
}
