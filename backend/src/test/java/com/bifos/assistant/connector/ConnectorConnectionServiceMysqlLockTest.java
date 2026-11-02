package com.bifos.assistant.connector;

import com.bifos.assistant.testsupport.MysqlIntegrationTest;

/** 설치 시작의 커밋 관측과 늦은 응답의 CAS 를 실제 MySQL 에서도 실행한다. `@MysqlCase` 를 단 상위 클래스 검사만 돈다. */
@MysqlIntegrationTest
class ConnectorConnectionServiceMysqlLockTest extends ConnectorConnectionServiceLockTest {
}
