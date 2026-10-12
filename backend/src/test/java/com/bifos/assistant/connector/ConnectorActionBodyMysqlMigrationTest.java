package com.bifos.assistant.connector;

import com.bifos.assistant.testsupport.MysqlTestDatabase;
import org.junit.jupiter.api.Tag;

/** 본문 key DDL을 기존 데이터가 있는 실제 MySQL에서도 검증한다. */
@Tag("mysql")
class ConnectorActionBodyMysqlMigrationTest extends ConnectorActionBodyMigrationTest {
    @Override
    Database createDatabase() {
        var database = MysqlTestDatabase.create();
        return new Database(database.url(), database.username(), database.password());
    }
}
