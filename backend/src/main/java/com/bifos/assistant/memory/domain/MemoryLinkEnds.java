package com.bifos.assistant.memory.domain;

/** 기억 연결 하나의 양 끝 번호다. 연결 엔티티를 읽지 않고 잠글 기억 줄을 정할 때 쓴다. */
public record MemoryLinkEnds(Long fromMemoryId, Long toMemoryId) {
}
