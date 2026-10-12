package com.bifos.assistant.chat;

import com.bifos.assistant.testsupport.MysqlTestDatabase;
import org.junit.jupiter.api.Tag;

/** H2와 같은 제약 검사를 fresh MySQL의 DDL에서도 확인한다. */
@Tag("mysql")
class ContentDraftSnapshotDdlMysqlTest extends ContentDraftSnapshotMigrationTest {
    @Override
    String[] createDatabase() {
        var database = MysqlTestDatabase.create();
        return new String[] {database.url(), database.username(), database.password()};
    }
}
