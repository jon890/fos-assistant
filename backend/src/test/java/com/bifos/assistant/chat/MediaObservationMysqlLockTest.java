package com.bifos.assistant.chat;

import com.bifos.assistant.testsupport.MysqlIntegrationTest;

/** 사용자 행 잠금 경합을 실제 MySQL 에서 실행한다. `@MysqlCase` 를 단 상위 클래스 검사만 돈다. */
@MysqlIntegrationTest
class MediaObservationMysqlLockTest extends MediaObservationLockTest {
}
