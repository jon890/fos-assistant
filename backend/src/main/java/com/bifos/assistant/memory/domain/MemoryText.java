package com.bifos.assistant.memory.domain;

/**
 * 푼 제목과 본문, 요약이다. 로그에 남기지 않는다.
 *
 * @param summary 요약 줄. 없으면 빈 글이다
 */
public record MemoryText(String title, String content, String summary) {

    @Override
    public String toString() {
        return "MemoryText[]";
    }
}
