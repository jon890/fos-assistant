package com.bifos.assistant.memory;

import com.bifos.assistant.testsupport.MysqlIntegrationTest;

/**
 * H2 에서 확인한 기억 암호화를 Flyway 로 만든 실제 MySQL 스키마에서도 확인한다(ADR-20261103 / memory-encryption).
 *
 * <p>이관 전후의 건수와 줄마다의 평문 해시 대조, 옛 key 줄 0건, 데이터 key 를 풀지 못할 때의 저장 거절이 여기서도 돈다.
 */
@MysqlIntegrationTest
class MemoryEncryptionMysqlTest extends MemoryEncryptionTest {
}
