package com.bifos.assistant.chat;

import com.bifos.assistant.testsupport.MysqlIntegrationTest;

/** `@MysqlCase` 를 단 잠금·조회 시점 검사만 실제 MySQL 에서 다시 돌려 메시지 저장과 빈 대화 삭제의 순서를 검증한다. */
@MysqlIntegrationTest
class ChatMessageParentGuardMysqlTest extends ChatMessageParentGuardTest {

}
