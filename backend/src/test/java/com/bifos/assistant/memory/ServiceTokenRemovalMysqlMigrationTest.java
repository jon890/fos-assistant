package com.bifos.assistant.memory;

import com.bifos.assistant.testsupport.MysqlTestDatabase;
import org.junit.jupiter.api.Tag;

/** 서비스 토큰 표를 실제 MySQL에서도 삭제하고 남은 사용자 기억을 보존한다. */
@Tag("mysql")
class ServiceTokenRemovalMysqlMigrationTest extends ServiceTokenRemovalMigrationTest {
    @Override
    Database createDatabase() {
        MysqlTestDatabase database = MysqlTestDatabase.create();
        return new Database(database.url(), database.username(), database.password());
    }
}
