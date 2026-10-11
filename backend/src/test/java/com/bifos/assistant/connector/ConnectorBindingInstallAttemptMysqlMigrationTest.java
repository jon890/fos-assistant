package com.bifos.assistant.connector;

import com.bifos.assistant.testsupport.MysqlTestDatabase;
import org.junit.jupiter.api.Tag;

/** 설치 시도 DDL과 UUID 저장을 Flyway가 만든 실제 MySQL 스키마에서도 실행한다. */
@Tag("mysql")
class ConnectorBindingInstallAttemptMysqlMigrationTest extends ConnectorBindingInstallAttemptMigrationTest {
    @Override
    Database createDatabase() {
        MysqlTestDatabase database = MysqlTestDatabase.create();
        return new Database(database.url(), database.username(), database.password());
    }
}
