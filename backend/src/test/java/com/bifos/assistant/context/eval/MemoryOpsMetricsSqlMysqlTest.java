package com.bifos.assistant.context.eval;

import com.bifos.assistant.testsupport.MysqlIntegrationTest;

/**
 * H2 에서 확인한 운영 집계 쿼리를 Flyway 로 만든 실제 MySQL 스키마에서도 돌린다. 운영에서 돌 쿼리라 MySQL 이 거절하는 문법이 없어야 한다.
 *
 * <p>저장소 인터페이스 밖의 쿼리라 {@code RepositoryQueryMysqlTest} 가 찾지 못하므로 이 검사가 그 몫을 한다.
 */
@MysqlIntegrationTest
class MemoryOpsMetricsSqlMysqlTest extends MemoryOpsMetricsSqlTest {

}
