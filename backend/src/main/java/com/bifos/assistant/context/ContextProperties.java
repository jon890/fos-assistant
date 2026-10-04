package com.bifos.assistant.context;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 실행마다 instructions 로 보낼 Memory 글자 수 상한과 색인 층에 떼어 둘 자리의 몫, 그리고 결과 항목이 오래됐다고 볼 시간이다.
 *
 * <p>{@code indexBudgetRatio} 는 상한을 나누는 수다. 4 면 상한의 1/4 까지를 색인 층 몫으로 먼저
 * 떼어 두고, 항상 층은 나머지 안에서 담는다. 색인이 그보다 짧으면 떼어 둔 자리가 남으므로 항상 층이
 * 그만큼 더 쓴다.
 *
 * <p>{@code resultStaleAfter} 는 결과가 끝난 시각과 묶음을 만든 시각의 차이가 이보다 크면 그 결과를 오래됐다고 보는
 * 시간이다(ADR-071). 비어 있거나 0 이하면 6시간이다.
 */
@Validated
@ConfigurationProperties(prefix = "assistant.context")
public record ContextProperties(long maxChars, int indexBudgetRatio, Duration resultStaleAfter) {

    /** 설정에 값이 없을 때 쓰는 몫이다. */
    private static final int DEFAULT_INDEX_BUDGET_RATIO = 4;

    /** 설정에 값이 없을 때 쓰는 결과의 신선도 기준이다. */
    private static final Duration DEFAULT_RESULT_STALE_AFTER = Duration.ofHours(6);

    public ContextProperties {
        if (indexBudgetRatio <= 0) {
            indexBudgetRatio = DEFAULT_INDEX_BUDGET_RATIO;
        }
        if (resultStaleAfter == null || resultStaleAfter.isNegative() || resultStaleAfter.isZero()) {
            resultStaleAfter = DEFAULT_RESULT_STALE_AFTER;
        }
    }
}
