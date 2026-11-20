package com.bifos.assistant.quality.application.dto;

import com.bifos.assistant.quality.domain.type.CheckReason;
import com.bifos.assistant.quality.domain.type.FeatureStatus;

/**
 * 기능 하나의 판정이다.
 *
 * @param reasonCode 통과하지 못한 까닭. 통과면 null
 */
public record FeatureVerdict(FeatureStatus status, String reasonCode) {

    public static FeatureVerdict passed() {
        return new FeatureVerdict(FeatureStatus.PASSED, null);
    }


    public static FeatureVerdict failed(CheckReason reason) {
        return new FeatureVerdict(FeatureStatus.FAILED, reason.name());
    }


    public static FeatureVerdict timedOut(CheckReason reason) {
        return new FeatureVerdict(FeatureStatus.TIMED_OUT, reason.name());
    }
}
