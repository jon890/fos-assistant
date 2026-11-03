package com.bifos.assistant.memory.domain;

/** 푼 제목과 본문이다. 로그에 남기지 않는다. */
public record MemoryText(String title, String content) {

    @Override
    public String toString() {
        return "MemoryText[]";
    }
}
