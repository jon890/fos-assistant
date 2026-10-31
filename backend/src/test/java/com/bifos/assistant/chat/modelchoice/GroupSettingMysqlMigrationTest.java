package com.bifos.assistant.chat.modelchoice;

import com.bifos.assistant.testsupport.MysqlTestDatabase;
import org.junit.jupiter.api.Tag;

/**
 * {@link GroupSettingMigrationTest} 의 검사를 실제 MySQL 에서 그대로 돌린다.
 *
 * <p>표 이름 바꾸기와 기본 키 바꾸기, 칸 주석의 문법이 H2 와 MySQL 에서 다르게 받아들여질 수 있다.
 */
@Tag("mysql")
class GroupSettingMysqlMigrationTest extends GroupSettingMigrationTest {

    @Override
    Database createDatabase() {
        MysqlTestDatabase database = MysqlTestDatabase.create();
        return new Database(database.url(), database.username(), database.password());
    }
}
