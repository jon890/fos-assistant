package com.bifos.assistant.quality.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 배포 뒤 확인 실행기의 설정이다(docs/features/agent-quality/design.md 의 「확인 한 번의 흐름」).
 *
 * @param featureTimeout 기능 하나의 마감. 넘으면 그 기능만 {@code TIMED_OUT} 이다
 * @param pollInterval 기능이 끝났는지 다시 보는 간격
 * @param linkRetention 실패한 기능의 평가 대화와 실행 링크를 남기는 기간
 * @param cleanupCron 기간이 지난 평가 대화를 지우는 시각. Control Plane 의 시간대를 따른다
 * @param bundleLocation 평가 묶음 파일({@code *.json})을 읽는 폴더. e2e 는 테스트 묶음 폴더로 바꾼다
 * @param evalTimeoutOverride 있으면 모든 묶음의 문항 상한을 이 값으로 바꾼다. 테스트와 e2e 가 줄인다. 운영은 비운다
 */
@Validated
@ConfigurationProperties(prefix = "assistant.quality")
public record QualityProperties(
    @DefaultValue("10m") Duration featureTimeout,
    @DefaultValue("1s") Duration pollInterval,
    @DefaultValue("30d") Duration linkRetention,
    @DefaultValue("0 15 5 * * *") String cleanupCron,
    @DefaultValue("classpath:quality/bundles/") String bundleLocation,
    Duration evalTimeoutOverride) {

    /** 마감과 간격이 0 이하면 기동을 멈춘다. 0 이면 모든 기능이 바로 시간 초과로 끝나는데 기동은 성공해 알아채지 못한다. */
    public QualityProperties {
        if (featureTimeout.isZero() || featureTimeout.isNegative()) {
            throw new IllegalStateException("assistant.quality.feature-timeout must be positive: " + featureTimeout);
        }
        if (pollInterval.isZero() || pollInterval.isNegative()) {
            throw new IllegalStateException("assistant.quality.poll-interval must be positive: " + pollInterval);
        }
        if (evalTimeoutOverride != null && (evalTimeoutOverride.isZero() || evalTimeoutOverride.isNegative())) {
            throw new IllegalStateException("assistant.quality.eval-timeout-override must be positive: " + evalTimeoutOverride);
        }
    }
}
