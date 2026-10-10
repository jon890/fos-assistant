package com.bifos.assistant.chat.application.model;

import com.bifos.assistant.chat.domain.type.ObservationProvenanceKind;
import java.time.Instant;

/** observedAt은 저장한 revision의 서버 시각으로 확정한다. */
public record ObservationProvenance(
        ObservationProvenanceKind kind,
        Long executionId,
        String provider,
        String providerVersion,
        String model,
        String modelVersion,
        int schemaVersion,
        String promptVersion,
        Instant observedAt) {
    public static final int SCHEMA_VERSION = 1;
    public static final String PROMPT_VERSION = "media-observation-v1";
    /** provider 또는 model을 확인하지 못한 제출에 쓰며 분석 결과 재사용에서는 제외한다. */
    public static final String UNKNOWN_IDENTITY = "UNKNOWN";
}
