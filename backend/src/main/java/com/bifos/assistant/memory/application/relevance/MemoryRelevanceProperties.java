package com.bifos.assistant.memory.application.relevance;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 질문과 기억의 관련 판정 기준이다(ADR-20261015 / memory-hybrid-selection).
 *
 * <p>{@code keywordMinCoverage} 는 질문 토큰 가운데 항목에 있어야 하는 토큰의 비율이다. 비어 있으면 0.3 이다.
 *
 * <p>{@code semanticMinSimilarity} 는 질문 벡터와 항목 벡터의 코사인이 이 값 이상일 때 의미로 관련 있다고 보는 기준이다. 모델마다 척도가
 * 달라 실제 모델로 잰 회수 측정의 결과로 정한다. 비어 있으면 0.5 다.
 *
 * <p>{@code rrfK} 는 키워드 순위와 의미 순위를 합치는 RRF 의 k 다. 비어 있으면 60 이다.
 *
 * <p>두 비율이 0 이상 1 이하가 아니거나 {@code rrfK} 가 1 미만이면 기동에서 멈춘다.
 */
@Validated
@ConfigurationProperties(prefix = "assistant.memory.relevance")
public record MemoryRelevanceProperties(
    @DecimalMin("0") @DecimalMax("1") Double keywordMinCoverage,
    @DecimalMin("0") @DecimalMax("1") Double semanticMinSimilarity,
    @Min(1) Integer rrfK) {

    private static final double DEFAULT_KEYWORD_MIN_COVERAGE = 0.3;
    private static final double DEFAULT_SEMANTIC_MIN_SIMILARITY = 0.5;
    private static final int DEFAULT_RRF_K = 60;


    public MemoryRelevanceProperties {
        keywordMinCoverage = ratio("keyword-min-coverage", keywordMinCoverage, DEFAULT_KEYWORD_MIN_COVERAGE);
        semanticMinSimilarity = ratio("semantic-min-similarity", semanticMinSimilarity, DEFAULT_SEMANTIC_MIN_SIMILARITY);
        if (rrfK == null) {
            rrfK = DEFAULT_RRF_K;
        }
        if (rrfK < 1) {
            throw new IllegalArgumentException("memory relevance: rrf-k 는 1 이상이어야 한다: " + rrfK);
        }
    }


    /** 모든 값을 기본값으로 둔 설정이다. 테스트와 측정이 쓴다. */
    public static MemoryRelevanceProperties defaults() {
        return new MemoryRelevanceProperties(null, null, null);
    }


    private static double ratio(String name, Double value, double fallback) {
        if (value == null) {
            return fallback;
        }
        if (value.isNaN() || value < 0 || value > 1) {
            throw new IllegalArgumentException("memory relevance: " + name + " 는 0 이상 1 이하여야 한다: " + value);
        }
        return value;
    }
}
