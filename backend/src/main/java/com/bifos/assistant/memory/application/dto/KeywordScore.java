package com.bifos.assistant.memory.application.dto;

/**
 * 키워드 점수기가 관련 있다고 본 항목 하나다.
 *
 * @param memoryId 항목 번호
 * @param bm25 BM25 점수. 같은 후보 집합 안에서만 견줄 수 있다
 * @param coverage 질문 토큰 가운데 항목에 있는 토큰의 비율
 */
public record KeywordScore(Long memoryId, double bm25, double coverage) {
}
