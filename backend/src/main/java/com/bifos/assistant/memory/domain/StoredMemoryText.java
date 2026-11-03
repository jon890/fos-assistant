package com.bifos.assistant.memory.domain;

import com.bifos.assistant.memory.domain.type.MemoryScope;

/**
 * 기억이나 revision 한 줄에 저장된 제목과 본문, 그 key 와 주인이다(ADR-20261103 / memory-encryption).
 *
 * <p>revision 은 원래 기억의 번호를 {@code memoryId} 로 든다. 그래서 같은 기억의 줄과 revision 은 같은 AAD 를 쓴다.
 *
 * @param ownerId {@code USER} 면 주인 사용자 번호, {@code GROUP} 이면 그룹 번호
 * @param dataKeyId 데이터 key 번호. 비어 있으면 평문이거나 옛 key 의 줄이다
 * @param legacyKeyId ADR-055 의 옛 환경 변수 key id. 비어 있으면 옛 key 로 암호화하지 않았다
 */
public record StoredMemoryText(
    Long memoryId, MemoryScope scope, Long ownerId, String title, String content, Long dataKeyId, String legacyKeyId) {

    /** 저장된 글을 로그에 남기지 않는다. */
    @Override
    public String toString() {
        return "StoredMemoryText[memoryId=" + memoryId + "]";
    }
}
