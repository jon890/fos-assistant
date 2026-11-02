package com.bifos.assistant.connector.approval.application;

import com.bifos.assistant.testsupport.MysqlIntegrationTest;

/** 금융 왕복 가운데 claim 과 정리, 동시 준비의 잠금 경합 검사를 실제 MySQL 에서 실행한다. `@MysqlCase` 를 단 상위 클래스 검사만 돈다. */
@MysqlIntegrationTest
class FinancialConnectorProducerMysqlRoundTripTest extends FinancialConnectorProducerRoundTripTest {
}
