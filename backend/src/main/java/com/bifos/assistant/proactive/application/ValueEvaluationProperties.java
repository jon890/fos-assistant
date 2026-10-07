package com.bifos.assistant.proactive.application;

import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.hermes.HermesProfileName;
import com.bifos.assistant.model.domain.ModelChoice;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** 시스템 판단 profile 은 설치 때 받는다. 사용자 화면에 에이전트를 만들지 않는다. */
@ConfigurationProperties(prefix = "assistant.value-evaluation")
@Validated
public record ValueEvaluationProperties(
        @DefaultValue("false") boolean enabled,
        String profile,
        String provider,
        String model,
        String reasoningEffort,
        @DefaultValue("30s") Duration timeout,
        @DefaultValue("SUBSCRIPTION") CostMode costMode) {

    public ValueEvaluationProperties {
        if (timeout == null
                || timeout.isNegative()
                || timeout.isZero()
                || timeout.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalArgumentException("value evaluation timeout must be positive and at most 2 minutes");
        }
        ModelChoice.of(provider, model, reasoningEffort);
        if (enabled && !HermesProfileName.isValid(profile)) {
            throw new IllegalArgumentException("a valid system decision profile is required");
        }
    }
}
