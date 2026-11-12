package com.bifos.assistant.connector.approval.application;

import com.bifos.assistant.testsupport.MysqlIntegrationTest;

/** 조건주문 왕복 가운데 claim 소비와 같은 의도 차단, 동시 승인의 잠금 검사를 실제 MySQL 에서 실행한다. `@MysqlCase` 를 단 상위 클래스 검사만 돈다. */
@MysqlIntegrationTest
class FinancialConditionalProducerMysqlRoundTripTest extends FinancialConditionalProducerRoundTripTest {
}
