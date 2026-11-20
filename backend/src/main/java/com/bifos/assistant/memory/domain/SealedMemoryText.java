package com.bifos.assistant.memory.domain;

/**
 * 주인의 데이터 key 로 암호화한 제목과 본문, 요약이다(ADR-20261103 / memory-encryption).
 *
 * @param summary 요약 암호문. 이관 전 줄을 다시 암호화할 때는 {@code NULL} 그대로다
 * @param dataKeyId 암호문을 연 {@code user_data_key.id}
 */
public record SealedMemoryText(String title, String content, String summary, Long dataKeyId) {
}
