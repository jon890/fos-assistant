package com.bifos.assistant.chat;

import com.bifos.assistant.testsupport.MysqlIntegrationTest;

/** Flyway 스키마 검증과 사용자 행 잠금 경합을 실제 MySQL에서 실행한다. */
@MysqlIntegrationTest
class MediaObservationMysqlLockTest extends MediaObservationLockTest {
}
