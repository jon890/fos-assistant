package com.bifos.assistant.chat;

import com.bifos.assistant.testsupport.MysqlTestDatabase;
import org.junit.jupiter.api.Tag;

/** {@link ChatAttachmentPositionMigrationTest} 의 backfill 검사를 실제 MySQL 에서 돌린다. */
@Tag("mysql")
class ChatAttachmentPositionMysqlMigrationTest extends ChatAttachmentPositionMigrationTest {

    @Override
    Database createDatabase() {
        MysqlTestDatabase database = MysqlTestDatabase.create();
        return new Database(database.url(), database.username(), database.password());
    }
}
