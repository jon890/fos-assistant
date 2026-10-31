package com.bifos.assistant.chat;

import com.bifos.assistant.testsupport.MysqlIntegrationTest;

/** H2 서비스 회귀 가운데 `@MysqlCase` 를 단 동시성 검사만 Flyway MySQL 스키마에서 다시 돈다. */
@MysqlIntegrationTest
class ContentDraftMysqlTest extends ContentDraftServiceTest {
}
