package com.bifos.assistant.chat;

import com.bifos.assistant.testsupport.MysqlTestDatabase;
import org.junit.jupiter.api.Tag;

/** {@link ChatPendingMessageMigrationTest} 의 검사를 실제 MySQL 에서 그대로 돌린다. */
@Tag("mysql")
class ChatPendingMessageMysqlMigrationTest extends ChatPendingMessageMigrationTest {

    @Override
    Database createDatabase() {
        MysqlTestDatabase database = MysqlTestDatabase.create();
        return new Database(database.url(), database.username(), database.password());
    }
}
