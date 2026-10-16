package com.bifos.assistant.connector;

import com.bifos.assistant.testsupport.MysqlTestDatabase;
import org.junit.jupiter.api.Tag;

/** {@link FinancialLimitsIntoOwnersMigrationTest} 의 검사를 실제 MySQL 에서 그대로 돌린다. 줄을 옮기는 SQL 이 운영 MySQL 에서도 도는지 본다. */
@Tag("mysql")
class FinancialLimitsIntoOwnersMysqlMigrationTest extends FinancialLimitsIntoOwnersMigrationTest {

    @Override
    Database createDatabase() {
        MysqlTestDatabase database = MysqlTestDatabase.create();
        return new Database(database.url(), database.username(), database.password());
    }
}
