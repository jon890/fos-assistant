package com.bifos.assistant.chat;

import com.bifos.assistant.testsupport.MysqlIntegrationTest;

/** Flyway 스키마와 실제 MySQL 의 잠금·조회 시점에서도 메시지 저장과 빈 대화 삭제의 순서를 검증한다. */
@MysqlIntegrationTest
class ChatMessageParentGuardMysqlTest extends ChatMessageParentGuardTest {

}
