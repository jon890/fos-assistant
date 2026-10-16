package com.bifos.assistant.memory.application.relevance;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 질문과 기억 항목의 관련도 순위를 정한다(ADR-20261015 / memory-hybrid-selection). 조립과 {@code memory_search} 가 같은 계산을 쓴다.
 */
@Component
@RequiredArgsConstructor
public class MemoryRelevance {

    /** 키워드 점수만으로 정한 순위다. {@code ContextSelection.SEARCH_KEYWORD} 와 같은 값이다. */
    public static final String SEARCH_KEYWORD = "search-keyword-v1";

    private final KeywordScorer keywords;
    private final MemoryRelevanceProperties properties;


    /** 키워드 점수만 쓰는 계산이다. 테스트와 측정이 쓴다. */
    public static MemoryRelevance keywordOnly(MemoryRelevanceProperties properties) {
        return new MemoryRelevance(new KeywordScorer(), properties);
    }


    /** 관련 있는 후보를 점수 순으로 낸다. 질문이 비었거나 관련 있는 후보가 없으면 빈 목록이다. */
    public RelevanceResult score(String question, List<RelevanceCandidate> candidates) {
        List<RelevanceScore> ranked = keywords.score(question, candidates, properties.keywordMinCoverage())
            .stream()
            .map(score -> new RelevanceScore(score.memoryId(), score.bm25(), null, score.bm25()))
            .toList();
        return new RelevanceResult(SEARCH_KEYWORD, ranked);
    }
}
