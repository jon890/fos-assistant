package com.bifos.assistant.chat.application.model;

import java.time.Instant;

/** 현재 권한과 설치 판을 나타내는 불변 값이다. 비밀값은 포함하지 않는다. */
public record ContentDraftBindingStamp(Long agentId, Long connectionId, Long bindingId,
    Instant connectionUpdatedAt, Instant bindingUpdatedAt, String profile, String connectorId, String policyFingerprint) {
}
