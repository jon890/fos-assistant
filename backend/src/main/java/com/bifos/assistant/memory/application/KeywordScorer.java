package com.bifos.assistant.memory.application;

import com.bifos.assistant.memory.application.dto.KeywordScore;
import com.bifos.assistant.memory.application.dto.RelevanceCandidate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 질문과 기억 항목의 키워드 관련도를 BM25 로 센다(ADR-20261015 / memory-hybrid-selection).
 *
 * <p>색인을 저장하지 않고 호출마다 후보를 쪼갠다. 문서는 제목과 일반 본문을 이은 글이고, N 과 평균 길이는 넘겨받은 후보 전체로 센다.
 * 관련 판정은 BM25 의 절댓값이 아니라 질문 토큰 가운데 문서에 있는 토큰의 비율(coverage)로 한다. BM25 는 후보 집합마다 척도가 달라
 * 임계값을 고정할 수 없다.
 */
@Component
public class KeywordScorer {

    private static final double K1 = 1.2;
    private static final double B = 0.75;


    /**
     * coverage 가 {@code minCoverage} 이상인 후보만 BM25 내림차순으로 낸다. 같으면 번호 오름차순이다.
     */
    public List<KeywordScore> score(String question, List<RelevanceCandidate> candidates, double minCoverage) {
        Set<String> queryTokens = KoreanBigramTokenizer.tokens(question);
        if (queryTokens.isEmpty() || candidates.isEmpty()) {
            return List.of();
        }
        List<Map<String, Integer>> documents = new ArrayList<>(candidates.size());
        Map<String, Integer> documentFrequency = new HashMap<>();
        long totalLength = 0;
        for (RelevanceCandidate candidate : candidates) {
            Map<String, Integer> counts = KoreanBigramTokenizer.documentTermCounts(documentOf(candidate));
            documents.add(counts);
            totalLength += counts.values().stream().mapToInt(Integer::intValue).sum();
            for (String token : queryTokens) {
                if (counts.containsKey(token)) {
                    documentFrequency.merge(token, 1, Integer::sum);
                }
            }
        }
        int n = candidates.size();
        double averageLength = Math.max(1.0, (double)totalLength / n);
        List<KeywordScore> scores = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<String, Integer> counts = documents.get(i);
            int length = counts.values().stream().mapToInt(Integer::intValue).sum();
            int matched = 0;
            double bm25 = 0;
            for (String token : queryTokens) {
                Integer frequency = counts.get(token);
                if (frequency == null) {
                    continue;
                }
                matched++;
                int df = documentFrequency.get(token);
                double idf = Math.log(1 + (n - df + 0.5) / (df + 0.5));
                bm25 += idf * frequency * (K1 + 1) / (frequency + K1 * (1 - B + B * length / averageLength));
            }
            double coverage = (double)matched / queryTokens.size();
            if (matched > 0 && coverage >= minCoverage) {
                scores.add(new KeywordScore(candidates.get(i).memoryId(), bm25, coverage));
            }
        }
        scores.sort(Comparator.comparingDouble(KeywordScore::bm25).reversed().thenComparing(KeywordScore::memoryId));
        return scores;
    }


    private static String documentOf(RelevanceCandidate candidate) {
        String title = candidate.title() == null ? "" : candidate.title();
        return candidate.plainBody() == null ? title : title + " " + candidate.plainBody();
    }
}
