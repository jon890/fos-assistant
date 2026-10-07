package com.bifos.assistant.proactive.domain;

/** 요청 모델과 실제 모델은 별개다. 실제 모델을 읽지 못하면 null 로 남긴다. */
public record DecisionProviderInfo(
        String adapter,
        String version,
        String requestedProvider,
        String requestedModel,
        String actualProvider,
        String actualModel,
        Long executionId) {}
