package com.bifos.assistant.memory.domain;

import com.bifos.assistant.memory.domain.type.MemoryScope;

/**
 * 임베딩할 항목 하나의 푼 글이다.
 *
 * @param ownerId {@code USER} 면 주인 사용자 번호, {@code GROUP} 이면 그룹 번호. 지문을 만들 주인 key 를 고른다
 */
public record MemoryEmbeddingSource(Long memoryId, MemoryScope scope, Long ownerId, String title, String content) {

    /** 제목과 본문을 로그에 남기지 않는다. */
    @Override
    public String toString() {
        return "MemoryEmbeddingSource[memoryId=" + memoryId + "]";
    }
}
