package com.bifos.assistant.connector;

import com.bifos.assistant.testsupport.MysqlIntegrationTest;

/** guard 왕복 가운데 커밋 경쟁 검사를 실제 MySQL 에서 실행한다. `@MysqlCase` 를 단 상위 클래스 검사만 돈다. */
@MysqlIntegrationTest
class ConnectorGuardBindingMysqlRoundTripTest extends ConnectorGuardBindingRoundTripTest {
}
