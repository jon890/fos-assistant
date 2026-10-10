package com.bifos.assistant.chat;

import com.bifos.assistant.testsupport.MysqlTestDatabase;
import org.junit.jupiter.api.Tag;

/** 실제 MySQL의 UNIQUE와 복합 FK, cascade를 같은 데이터로 검증한다. */
@Tag("mysql")
class MediaObservationMysqlMigrationTest extends MediaObservationMigrationTest {
    @Override
    String[] createDatabase() {
        var database = MysqlTestDatabase.create();
        return new String[] {database.url(), database.username(), database.password()};
    }
}
