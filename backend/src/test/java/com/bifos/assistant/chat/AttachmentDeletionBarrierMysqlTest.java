package com.bifos.assistant.chat;

import com.bifos.assistant.testsupport.MysqlIntegrationTest;

/** 삭제 잠금과 파일 장벽 회귀를 Flyway가 만든 실제 MySQL 스키마에서 다시 돈다. */
@MysqlIntegrationTest
class AttachmentDeletionBarrierMysqlTest extends AttachmentDeletionBarrierTest {
}
