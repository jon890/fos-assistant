package com.bifos.assistant.context;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 실행마다 instructions 로 보낼 Memory 글자 수 상한과 색인 층에 떼어 둘 자리의 몫이다.
 *
 * <p>{@code indexBudgetRatio} 는 상한을 나누는 수다. 4 면 상한의 1/4 까지를 색인 층 몫으로 먼저
 * 떼어 두고, 항상 층은 나머지 안에서 담는다. 색인이 그보다 짧으면 떼어 둔 자리가 남으므로 항상 층이
 * 그만큼 더 쓴다.
 */
@ConfigurationProperties(prefix = "assistant.context")
public record ContextProperties(long maxChars, int indexBudgetRatio) {

    /** 설정에 값이 없을 때 쓰는 몫이다. */
    private static final int DEFAULT_INDEX_BUDGET_RATIO = 4;

    public ContextProperties {
        if (indexBudgetRatio <= 0) {
            indexBudgetRatio = DEFAULT_INDEX_BUDGET_RATIO;
        }
    }
}
