package com.bifos.assistant.attention;

import com.bifos.assistant.testsupport.MysqlTestDatabase;
import org.junit.jupiter.api.Tag;

/** {@link AttentionLegacyMigrationTest} 의 이관 검사를 실제 MySQL 에서 돌린다. */
@Tag("mysql")
class AttentionLegacyMysqlMigrationTest extends AttentionLegacyMigrationTest {

    @Override
    Database createDatabase() {
        MysqlTestDatabase database = MysqlTestDatabase.create();
        return new Database(database.url(), database.username(), database.password());
    }
}
