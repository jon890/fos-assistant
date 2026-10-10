package com.bifos.assistant.chat.application.model;

import com.bifos.assistant.chat.domain.type.ObservationStatus;
import java.time.Instant;

public record MediaObservationView(
        String assetId,
        int ordinal,
        String sourceFingerprint,
        Long revision,
        ObservationStatus status,
        MediaObservationInput observation,
        ObservationProvenance provenance,
        Instant expiresAt,
        String errorCode) {}
