package com.bifos.assistant.chat.attachment.application.dto;

import com.bifos.assistant.chat.attachment.domain.type.ObservationStatus;
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
    String errorCode) {
}
