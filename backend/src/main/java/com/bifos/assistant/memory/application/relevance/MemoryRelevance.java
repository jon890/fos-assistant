package com.bifos.assistant.memory.application.relevance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 질문과 기억 항목의 관련도 순위를 정한다(ADR-20261015 / memory-hybrid-selection). 조립과 {@code memory_search} 가 같은 계산을 쓴다.
 *
 * <p>질문 벡터가 있으면 키워드 순위와 의미 순위를 RRF({@code 1 / (rrfK + 순위)} 의 합)로 합친다. 한쪽에만 있는 항목은 그쪽 항만 더한다. 질문 벡터가
 * 없으면 키워드 점수만 쓴다. 관련 판정은 키워드 쪽은 coverage, 의미 쪽은 코사인이 각각 설정값 이상인 것이다.
 */
@Component
@RequiredArgsConstructor
public class MemoryRelevance {

    /** 키워드 점수만으로 정한 순위다. {@code ContextSelection.SEARCH_KEYWORD} 와 같은 값이다. */
    public static final String SEARCH_KEYWORD = "search-keyword-v1";

    /** 키워드와 의미 순위를 RRF 로 합친 순위다. {@code ContextSelection.SEARCH_HYBRID} 와 같은 값이다. */
    public static final String SEARCH_HYBRID = "search-hybrid-v1";

    private final KeywordScorer keywords;
    private final MemoryRelevanceProperties properties;
    private final MemoryVectorCache vectors;


    /** 키워드 점수만 쓰는 계산이다. 테스트와 측정이 쓴다. */
    public static MemoryRelevance keywordOnly(MemoryRelevanceProperties properties) {
        return new MemoryRelevance(new KeywordScorer(), properties, null);
    }


    /** 키워드 점수만으로 낸다. 질문이 비었거나 관련 있는 후보가 없으면 빈 목록이다. */
    public RelevanceResult score(String question, List<RelevanceCandidate> candidates) {
        return score(question, Optional.empty(), candidates);
    }


    /**
     * 관련 있는 후보를 점수 순으로 낸다. 같으면 번호 오름차순이다.
     *
     * @param queryVector 질문 벡터. 비어 있으면 키워드 점수만 쓴다
     */
    public RelevanceResult score(String question, Optional<float[]> queryVector, List<RelevanceCandidate> candidates) {
        List<KeywordScore> keyword = keywords.score(question, candidates, properties.keywordMinCoverage());
        if (queryVector.isEmpty() || vectors == null) {
            return new RelevanceResult(
                SEARCH_KEYWORD,
                keyword.stream().map(score -> new RelevanceScore(score.memoryId(), score.bm25(), null, score.bm25())).toList());
        }
        List<Map.Entry<Long, Double>> semantic = semanticRanking(queryVector.get(), vectors.vectorsFor(candidates));
        Map<Long, Double> keywordScores = new HashMap<>();
        Map<Long, Double> semanticScores = new HashMap<>();
        Map<Long, Double> fused = new HashMap<>();
        for (int i = 0; i < keyword.size(); i++) {
            keywordScores.put(keyword.get(i).memoryId(), keyword.get(i).bm25());
            fused.merge(keyword.get(i).memoryId(), rrf(i + 1), Double::sum);
        }
        for (int i = 0; i < semantic.size(); i++) {
            semanticScores.put(semantic.get(i).getKey(), semantic.get(i).getValue());
            fused.merge(semantic.get(i).getKey(), rrf(i + 1), Double::sum);
        }
        List<RelevanceScore> ranked = new ArrayList<>();
        for (Long id : fused.keySet()) {
            ranked.add(new RelevanceScore(id, keywordScores.get(id), semanticScores.get(id), fused.get(id)));
        }
        ranked.sort(Comparator.comparingDouble(RelevanceScore::fused).reversed().thenComparing(RelevanceScore::memoryId));
        return new RelevanceResult(SEARCH_HYBRID, ranked);
    }


    /** 코사인이 기준 이상인 항목을 코사인 내림차순, 같으면 번호 오름차순으로 낸다. */
    private List<Map.Entry<Long, Double>> semanticRanking(float[] query, Map<Long, float[]> candidates) {
        List<Map.Entry<Long, Double>> ranked = new ArrayList<>();
        candidates.forEach((memoryId, vector) -> {
            double similarity = cosine(query, vector);
            if (similarity >= properties.semanticMinSimilarity()) {
                ranked.add(Map.entry(memoryId, similarity));
            }
        });
        ranked.sort(Map.Entry.<Long, Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        return ranked;
    }


    private double rrf(int rank) {
        return 1.0 / (properties.rrfK() + rank);
    }


    /** 차원이 다르거나 길이가 0 인 벡터는 관련 없다. */
    static double cosine(float[] left, float[] right) {
        if (left.length != right.length || left.length == 0) {
            return 0;
        }
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < left.length; i++) {
            dot += (double) left[i] * right[i];
            leftNorm += (double) left[i] * left[i];
            rightNorm += (double) right[i] * right[i];
        }
        return leftNorm == 0 || rightNorm == 0 ? 0 : dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }
}
