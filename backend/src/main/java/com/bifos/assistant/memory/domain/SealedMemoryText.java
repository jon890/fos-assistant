package com.bifos.assistant.memory.domain;

/**
 * 주인의 데이터 key 로 암호화한 제목과 본문이다(ADR-20261103 / memory-encryption).
 *
 * @param dataKeyId 두 암호문을 연 {@code user_data_key.id}
 */
public record SealedMemoryText(String title, String content, Long dataKeyId) {
}
