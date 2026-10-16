package com.bifos.assistant.context;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 질문과 관련된 기억 구역의 예산이다(ADR-20261015 / memory-hybrid-selection).
 *
 * <p>{@code maxItems} 는 구역에 싣는 항목 수의 상한이다. 비어 있으면 3 이고, 0 이면 구역을 끈다.
 *
 * <p>{@code itemMaxChars} 는 구역에 실을 수 있는 본문의 최대 글자 수다. 이보다 긴 본문은 자르지 않고 색인에 둔다. 비어 있으면 600자다.
 *
 * <p>{@code maxChars} 는 머리 줄과 구분 줄까지 센 구역의 글자 수 예산이다. 비어 있으면 1,500자다.
 */
@Validated
@ConfigurationProperties(prefix = "assistant.context.search")
public record MemorySearchSectionProperties(@Min(0) Integer maxItems, @Min(0) Integer itemMaxChars, @Min(0) Integer maxChars) {


    private static final int DEFAULT_MAX_ITEMS = 3;
    private static final int DEFAULT_ITEM_MAX_CHARS = 600;
    private static final int DEFAULT_MAX_CHARS = 1_500;

    public MemorySearchSectionProperties {
        maxItems = maxItems == null ? DEFAULT_MAX_ITEMS : maxItems;
        itemMaxChars = itemMaxChars == null ? DEFAULT_ITEM_MAX_CHARS : itemMaxChars;
        maxChars = maxChars == null ? DEFAULT_MAX_CHARS : maxChars;
    }


    /** 모든 값을 기본값으로 둔 설정이다. 테스트와 측정이 쓴다. */
    public static MemorySearchSectionProperties defaults() {
        return new MemorySearchSectionProperties(null, null, null);
    }
}
