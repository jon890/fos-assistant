package com.bifos.assistant;

import com.bifos.assistant.testsupport.MysqlTestDatabase;
import org.junit.jupiter.api.Tag;

/** 실제 MySQL에서도 COMMENT 이외 DDL과 데이터를 바꾸지 않는지 확인한다. */
@Tag("mysql")
class SchemaCommentsMysqlTest extends SchemaCommentsMigrationTest {
    @Override
    String[] createDatabase() {
        var database = MysqlTestDatabase.create();
        return new String[] {database.url(), database.username(), database.password()};
    }
}
