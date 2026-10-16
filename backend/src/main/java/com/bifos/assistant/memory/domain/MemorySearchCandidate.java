package com.bifos.assistant.memory.domain;

import java.time.Instant;

/**
 * {@code memory_search} 가 점수를 매길 후보 한 줄이다.
 *
 * @param plainContent 민감도가 {@code NORMAL} 이고 암호화하지 않은 줄의 본문. 그 밖의 줄은 SQL 이 null 로 바꾼다
 */
public record MemorySearchCandidate(Long id, String title, String plainContent, int revision, Instant updatedAt) {

    /** 제목과 본문을 로그에 남기지 않는다. */
    @Override
    public String toString() {
        return "MemorySearchCandidate[id=" + id + "]";
    }
}
