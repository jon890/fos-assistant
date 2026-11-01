package com.bifos.assistant.chat.modelchoice;

import com.bifos.assistant.testsupport.MysqlTestDatabase;
import org.junit.jupiter.api.Tag;

/**
 * {@link AgentModelDefaultDropMigrationTest} 의 검사를 실제 MySQL 에서 그대로 돌린다.
 *
 * <p>컬럼을 하나씩 지우는 문장과 대화의 문자열 칸 비교가 H2 와 MySQL 에서 다르게 받아들여질 수 있다.
 */
@Tag("mysql")
class AgentModelDefaultDropMysqlMigrationTest extends AgentModelDefaultDropMigrationTest {

    @Override
    Database createDatabase() {
        MysqlTestDatabase database = MysqlTestDatabase.create();
        return new Database(database.url(), database.username(), database.password());
    }
}
