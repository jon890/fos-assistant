package com.bifos.assistant.chat;

import com.bifos.assistant.testsupport.MysqlIntegrationTest;

/** H2와 같은 실제 서비스 회귀를 Flyway MySQL 스키마에서도 실행한다. */
@MysqlIntegrationTest
class ContentDraftMysqlTest extends ContentDraftServiceTest {
}
