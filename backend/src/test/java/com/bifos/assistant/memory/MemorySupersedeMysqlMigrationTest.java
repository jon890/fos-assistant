package com.bifos.assistant.memory;

import com.bifos.assistant.testsupport.MysqlTestDatabase;
import org.junit.jupiter.api.Tag;

/** {@link MemorySupersedeMigrationTest} 의 검사를 실제 MySQL 에서 그대로 돌린다. */
@Tag("mysql")
class MemorySupersedeMysqlMigrationTest extends MemorySupersedeMigrationTest {

    @Override
    Database createDatabase() {
        MysqlTestDatabase database = MysqlTestDatabase.create();
        return new Database(database.url(), database.username(), database.password());
    }
}
