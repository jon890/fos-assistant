package com.bifos.assistant.feedback.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 판단 피드백 기록의 보관 설정이다. 뜻은 {@code docs/backend/decision-feedback.md} 의 「보관과 삭제」 가 갖는다.
 *
 * <p>값이 없거나 0 이하면 기본값으로 둔다.
 *
 * @param retention 사건을 남기는 기간
 */
@Validated
@ConfigurationProperties(prefix = "assistant.decision-feedback")
public record DecisionFeedbackProperties(Duration retention) {

    private static final Duration DEFAULT_RETENTION = Duration.ofDays(365);

    public DecisionFeedbackProperties {
        retention = retention == null || retention.isZero() || retention.isNegative() ? DEFAULT_RETENTION : retention;
    }
}
