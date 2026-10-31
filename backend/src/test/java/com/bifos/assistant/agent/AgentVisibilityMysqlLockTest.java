package com.bifos.assistant.agent;

import com.bifos.assistant.testsupport.MysqlIntegrationTest;

/**
 * {@link AgentVisibilityLockTest} 를 MySQL 에서 함께 돌린다.
 *
 * <p>잠금을 기다린 뒤 커밋된 바인딩을 보는지는 MySQL 의 REPEATABLE READ 에서만 실패가 드러난다. H2 는 문장마다 새로 커밋된 값을
 * 읽어, 잠금 없는 읽기로 트랜잭션을 시작해도 잠금을 기다린 뒤의 값을 본다.
 */
@MysqlIntegrationTest
class AgentVisibilityMysqlLockTest extends AgentVisibilityLockTest {

}
