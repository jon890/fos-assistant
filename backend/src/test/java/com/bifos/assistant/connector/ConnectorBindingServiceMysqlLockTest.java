package com.bifos.assistant.connector;

import com.bifos.assistant.testsupport.MysqlIntegrationTest;

/**
 * {@link ConnectorBindingServiceLockTest} 의 `@MysqlCase` 검사를 MySQL 에서 돌린다.
 *
 * <p>잠금 순서 검사는 MySQL 의 REPEATABLE READ 에서만 실패가 드러난다. H2 는 문장마다 새로 커밋된 값을 읽어, 잠금 없는 읽기로
 * 트랜잭션을 시작해도 잠금을 기다린 뒤의 값을 본다.
 */
@MysqlIntegrationTest
class ConnectorBindingServiceMysqlLockTest extends ConnectorBindingServiceLockTest {
}
