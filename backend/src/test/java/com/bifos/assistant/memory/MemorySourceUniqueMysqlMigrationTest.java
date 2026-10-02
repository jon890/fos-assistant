package com.bifos.assistant.memory;

import com.bifos.assistant.testsupport.MysqlTestDatabase;
import org.junit.jupiter.api.Tag;

/**
 * {@link MemorySourceUniqueMigrationTest} 의 검사를 실제 MySQL 에서 그대로 돌린다.
 *
 * <p>유일 제약의 세 칸은 모두 `memory` 표에 있어 정렬 규칙이 하나다. 줄이 있는 상태에서 색인이 만들어지는지를 MySQL 에서 본다.
 */
@Tag("mysql")
class MemorySourceUniqueMysqlMigrationTest extends MemorySourceUniqueMigrationTest {

    @Override
    Database createDatabase() {
        MysqlTestDatabase database = MysqlTestDatabase.create();
        return new Database(database.url(), database.username(), database.password());
    }
}
