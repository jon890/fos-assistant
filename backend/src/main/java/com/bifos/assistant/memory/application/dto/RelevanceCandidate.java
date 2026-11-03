package com.bifos.assistant.memory.application.dto;

import com.bifos.assistant.memory.domain.type.MemoryScope;

/**
 * 관련도를 셀 기억 항목 하나다.
 *
 * @param memoryId 항목 번호
 * @param title 제목
 * @param plainBody 본문. null 이면 그 항목은 제목만으로 센다
 * @param scope 항목의 범위. 임베딩 지문을 만들 주인 key 를 고른다
 * @param ownerId {@code USER} 면 주인 사용자 번호, {@code GROUP} 이면 그룹 번호
 */
public record RelevanceCandidate(Long memoryId, String title, String plainBody, MemoryScope scope, Long ownerId) {

    @Override
    public String toString() {
        return "RelevanceCandidate[memoryId=" + memoryId + "]";
    }
}
