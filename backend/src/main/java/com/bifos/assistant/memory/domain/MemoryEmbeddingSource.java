package com.bifos.assistant.memory.domain;

/**
 * 임베딩할 항목 하나의 글이다. 민감도가 {@code NORMAL} 이고 암호화하지 않은 항목만 이 모양으로 읽는다.
 */
public record MemoryEmbeddingSource(Long memoryId, String title, String content) {

    /** 제목과 본문을 로그에 남기지 않는다. */
    @Override
    public String toString() {
        return "MemoryEmbeddingSource[memoryId=" + memoryId + "]";
    }
}
