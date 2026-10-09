package com.bifos.assistant.proactive.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 행동 정책의 설치 설정이다. 두 기간은 0 보다 커야 하고, 아니면 기동을 멈춘다.
 *
 * @param executionEnabled 설치가 읽기 전용 자동 실행을 연다. 기본은 꺼짐이고, 꺼져 있으면 사용자 동의와 상관없이 실행하지 않는다
 * @param maxEvaluationAge 이보다 오래된 평가는 실행 근거가 아니다. 넘으면 {@code STALE_EVALUATION} 이다
 * @param maxEvidenceAge 이보다 오래 전에 확인한 근거는 실행 근거가 아니다. 넘으면 {@code STALE_EVIDENCE} 이다
 */
@ConfigurationProperties(prefix = "assistant.autonomy")
@Validated
public record AutonomyProperties(
        @DefaultValue("false") boolean executionEnabled,
        @DefaultValue("1h") Duration maxEvaluationAge,
        @DefaultValue("72h") Duration maxEvidenceAge) {

    public AutonomyProperties {
        if (!positive(maxEvaluationAge) || !positive(maxEvidenceAge)) {
            throw new IllegalArgumentException("autonomy ages must be positive");
        }
    }

    private static boolean positive(Duration value) {
        return value != null && !value.isNegative() && !value.isZero();
    }
}
