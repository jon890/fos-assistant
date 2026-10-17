package com.bifos.assistant.memory.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 임베딩으로 기억 사이의 연결을 찾는 설정이다(ADR-20261015 / memory-link). 임베딩이 꺼져 있으면 쓰지 않는다. 기본값은 2026-10-17 사용자 결정이고 운영
 * 집계로 고친다.
 *
 * @param supersedeMinSimilarity 대체 후보가 되는 코사인 유사도의 아래 값
 * @param supersedeMaxPerItem 새 항목 하나가 만드는 대체 제안의 상한
 * @param relatedMinSimilarity 자동 {@code RELATED} 를 맺는 코사인 유사도의 아래 값
 * @param relatedTopK 서로의 상위 몇 안에 들어야 맺는지
 * @param relatedMaxPerItem 항목 하나가 갖는 효력 있는 {@code RELATED} 의 상한
 */
@Validated
@ConfigurationProperties(prefix = "assistant.memory.link")
public record MemoryLinkProperties(
    @DecimalMin("0.0") @DecimalMax("1.0") Double supersedeMinSimilarity,
    @Min(0) Integer supersedeMaxPerItem,
    @DecimalMin("0.0") @DecimalMax("1.0") Double relatedMinSimilarity,
    @Min(1) Integer relatedTopK,
    @Min(0) Integer relatedMaxPerItem) {

    public MemoryLinkProperties {
        supersedeMinSimilarity = supersedeMinSimilarity == null ? 0.85 : supersedeMinSimilarity;
        supersedeMaxPerItem = supersedeMaxPerItem == null ? 2 : supersedeMaxPerItem;
        relatedMinSimilarity = relatedMinSimilarity == null ? 0.7 : relatedMinSimilarity;
        relatedTopK = relatedTopK == null ? 3 : relatedTopK;
        relatedMaxPerItem = relatedMaxPerItem == null ? 5 : relatedMaxPerItem;
    }


    /** 기본값이다. 테스트가 쓴다. */
    public static MemoryLinkProperties defaults() {
        return new MemoryLinkProperties(null, null, null, null, null);
    }
}
