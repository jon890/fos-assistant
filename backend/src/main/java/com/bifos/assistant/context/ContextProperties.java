package com.bifos.assistant.context;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 실행마다 instructions 로 보낼 Memory 글자 수 상한과 색인 층에 떼어 둘 자리의 몫, Memory 와 결과 항목의 신선도 기준이다.
 *
 * <p>{@code indexBudgetRatio} 는 상한을 나누는 수다. 4 면 상한의 1/4 까지를 색인 층 몫으로 먼저
 * 떼어 두고, 항상 층은 나머지 안에서 담는다. 색인이 그보다 짧으면 떼어 둔 자리가 남으므로 항상 층이
 * 그만큼 더 쓴다.
 *
 * <p>{@code resultStaleAfter} 는 결과가 끝난 시각과 묶음을 만든 시각의 차이가 이보다 크면 그 결과를 오래됐다고 보는
 * 시간이다(ADR-071). 비어 있거나 0 이하면 6시간이다.
 *
 * <p>{@code memoryStaleAfter} 는 Memory 를 고친 시각과 묶음을 만든 시각의 차이가 이보다 크면 오래됐다고 보는
 * 시간이다. {@code memoryCollectionStaleAfter} 에 collection 별 기준을 적으면 기본 기준보다 먼저 쓴다. 기준이
 * 0 이하면 그 Memory 의 신선도 판정을 끈다.
 *
 * <p>{@code factsMaxChars} 는 항상 층과 색인 층 사이에 짧은 개인 기억을 본문까지 싣는 개인 사실 구역의 글자 수 예산이다. 머리 줄과
 * 구분 줄까지 센다. 비어 있거나 음수면 2,000자이고, 0 이면 구역을 끈다.
 *
 * <p>{@code factsItemMaxChars} 는 개인 사실 구역의 후보가 될 수 있는 본문의 최대 글자 수다. 이보다 긴 본문은 자르지 않고 색인에 둔다.
 * 비어 있거나 0 이하면 200자다.
 */
@Validated
@ConfigurationProperties(prefix = "assistant.context")
public record ContextProperties(
        long maxChars,
        int indexBudgetRatio,
        Duration resultStaleAfter,
        Duration memoryStaleAfter,
        Map<String, Duration> memoryCollectionStaleAfter,
        Integer factsMaxChars,
        Integer factsItemMaxChars) {

    /** 설정에 값이 없을 때 쓰는 몫이다. */
    private static final int DEFAULT_INDEX_BUDGET_RATIO = 4;

    /** 설정에 값이 없을 때 쓰는 결과의 신선도 기준이다. */
    private static final Duration DEFAULT_RESULT_STALE_AFTER = Duration.ofHours(6);

    /** 설정에 값이 없을 때 쓰는 Memory 신선도 기준이다. */
    private static final Duration DEFAULT_MEMORY_STALE_AFTER = Duration.ofDays(180);

    /** 설정에 값이 없을 때 쓰는 개인 사실 구역의 예산이다. */
    private static final int DEFAULT_FACTS_MAX_CHARS = 2_000;

    /** 설정에 값이 없을 때 쓰는 개인 사실 구역 후보의 본문 길이 상한이다. */
    private static final int DEFAULT_FACTS_ITEM_MAX_CHARS = 200;

    public ContextProperties {
        if (indexBudgetRatio <= 0) {
            indexBudgetRatio = DEFAULT_INDEX_BUDGET_RATIO;
        }
        if (resultStaleAfter == null || resultStaleAfter.isNegative() || resultStaleAfter.isZero()) {
            resultStaleAfter = DEFAULT_RESULT_STALE_AFTER;
        }
        if (memoryStaleAfter == null) {
            memoryStaleAfter = DEFAULT_MEMORY_STALE_AFTER;
        }
        memoryCollectionStaleAfter =
                memoryCollectionStaleAfter == null ? Map.of() : Map.copyOf(memoryCollectionStaleAfter);
        if (factsMaxChars == null || factsMaxChars < 0) {
            factsMaxChars = DEFAULT_FACTS_MAX_CHARS;
        }
        if (factsItemMaxChars == null || factsItemMaxChars <= 0) {
            factsItemMaxChars = DEFAULT_FACTS_ITEM_MAX_CHARS;
        }
    }
}
