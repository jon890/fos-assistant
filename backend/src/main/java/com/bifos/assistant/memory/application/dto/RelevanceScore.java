package com.bifos.assistant.memory.application.dto;

/**
 * 관련 있다고 본 항목 하나의 점수다.
 *
 * @param memoryId 항목 번호
 * @param keyword 키워드 점수(BM25). 키워드로 관련 없으면 null
 * @param semantic 질문 벡터와의 코사인. 의미로 관련 없거나 의미 점수를 쓰지 않았으면 null
 * @param fused 순서를 정한 점수. 키워드만 쓰면 키워드 점수와 같다
 */
public record RelevanceScore(Long memoryId, Double keyword, Double semantic, double fused) {
}
