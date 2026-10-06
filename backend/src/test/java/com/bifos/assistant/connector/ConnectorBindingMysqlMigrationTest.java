package com.bifos.assistant.connector;

import com.bifos.assistant.testsupport.MysqlTestDatabase;
import org.junit.jupiter.api.Tag;

/**
 * {@link ConnectorBindingMigrationTest} 의 검사를 실제 MySQL 에서 그대로 돌린다.
 *
 * <p>줄을 넣는 마이그레이션은 H2 가 통과해도 운영 MySQL 에서 실패할 수 있다. FK 가 걸린 칸을 비워도 되게 바꾸는 문장도 함께 본다.
 */
@Tag("mysql")
class ConnectorBindingMysqlMigrationTest extends ConnectorBindingMigrationTest {

    @Override
    Database createDatabase() {
        MysqlTestDatabase database = MysqlTestDatabase.create();
        return new Database(database.url(), database.username(), database.password());
    }
}
