package com.bifos.assistant.memory;

import com.bifos.assistant.testsupport.MysqlIntegrationTest;

/** 수정과 승인이 동시에 들어오는 잠금 회귀를 실제 MySQL 에서 다시 돈다. */
@MysqlIntegrationTest
class MemoryServiceMysqlTest extends MemoryServiceTest {
}
