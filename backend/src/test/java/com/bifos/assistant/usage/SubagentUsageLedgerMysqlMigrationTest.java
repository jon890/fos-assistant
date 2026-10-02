package com.bifos.assistant.usage;

import com.bifos.assistant.testsupport.MysqlTestDatabase;
import org.junit.jupiter.api.Tag;

/**
 * {@link SubagentUsageLedgerMigrationTest} 의 검사를 실제 MySQL 에서 그대로 돌린다.
 *
 * <p>H2 는 정렬 규칙이 다른 칸의 비교를 막지 않는다. 지난 줄을 다시 넣는 SQL 이 운영 MySQL 에서만 오류 1267 로 실패한 적이 있다.
 */
@Tag("mysql")
class SubagentUsageLedgerMysqlMigrationTest extends SubagentUsageLedgerMigrationTest {

    @Override
    Database createDatabase() {
        MysqlTestDatabase database = MysqlTestDatabase.create();
        return new Database(database.url(), database.username(), database.password());
    }
}
