package com.bifos.assistant.memory.domain;

import com.bifos.assistant.memory.domain.type.MemoryScope;

import java.time.Instant;

/**
 * {@code memory_search} 가 점수를 매길 후보 한 줄이다.
 *
 * @param plainContent 푼 본문
 * @param ownerId {@code USER} 면 주인 사용자 번호, {@code GROUP} 이면 그룹 번호
 */
public record MemorySearchCandidate(
    Long id, String title, String plainContent, int revision, Instant updatedAt, MemoryScope scope, Long ownerId) {

    /** 제목과 본문을 로그에 남기지 않는다. */
    @Override
    public String toString() {
        return "MemorySearchCandidate[id=" + id + "]";
    }
}
