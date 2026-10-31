package com.bifos.assistant.chat;

import com.bifos.assistant.testsupport.MysqlIntegrationTest;

/** H2 회귀 가운데 `@MysqlCase` 를 단 동시 preview 와 유일 제약 검사만 Flyway MySQL 에서 다시 돈다. */
@MysqlIntegrationTest
class ContentDraftSnapshotMysqlTest extends ContentDraftSnapshotsTest {
}
