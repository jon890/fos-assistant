package com.bifos.assistant.memory.application.model;

import java.time.Instant;

/** 검색이 읽는 네 칸이다. 본문과 소유자 정보는 조회하지 않는다. */
public record MemorySearchItem(Long id, String title, int revision, Instant updatedAt) {
    /** 제목을 로그에 남기지 않는다. */
    @Override
    public String toString() {
        return "MemorySearchItem[id=" + id + ", revision=" + revision + "]";
    }
}
