package com.bifos.assistant.memory;

import com.bifos.assistant.testsupport.MysqlIntegrationTest;

/** 같은 동시 수정 계약을 Flyway 스키마의 실제 MySQL에서도 검사한다. */
@MysqlIntegrationTest
class MemoryCaptureConcurrencyMysqlTest extends MemoryCaptureConcurrencyTest {

}
