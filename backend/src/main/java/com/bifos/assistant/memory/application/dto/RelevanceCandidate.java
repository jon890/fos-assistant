package com.bifos.assistant.memory.application.dto;

/**
 * 관련도를 셀 기억 항목 하나다.
 *
 * @param memoryId 항목 번호
 * @param title 제목
 * @param plainBody 일반 본문. 민감 항목과 암호화한 항목이면 null 이고, 그 항목은 제목만으로 센다
 */
public record RelevanceCandidate(Long memoryId, String title, String plainBody) {

    @Override
    public String toString() {
        return "RelevanceCandidate[memoryId=" + memoryId + "]";
    }
}
