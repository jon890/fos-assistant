package com.bifos.assistant.chat;

import com.bifos.assistant.testsupport.MysqlTestDatabase;
import org.junit.jupiter.api.Tag;

@Tag("mysql")
class ContentDraftMysqlMigrationTest extends ContentDraftMigrationTest {
    @Override
    String[] createDatabase() {
        var database = MysqlTestDatabase.create();
        return new String[] {database.url(), database.username(), database.password()};
    }
}
