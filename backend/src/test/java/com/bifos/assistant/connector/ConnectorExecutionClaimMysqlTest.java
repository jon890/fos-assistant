package com.bifos.assistant.connector;

import com.bifos.assistant.testsupport.MysqlIntegrationTest;

/** 행 잠금에 기대는 발급·소비·철회 경합 검사를 실제 MySQL 에서 실행한다. `@MysqlCase` 를 단 상위 클래스 검사만 돈다. */
@MysqlIntegrationTest
class ConnectorExecutionClaimMysqlTest extends ConnectorExecutionClaimTest {
}
