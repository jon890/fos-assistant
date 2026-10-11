package com.bifos.assistant.chat.presentation;

import com.bifos.assistant.chat.application.MediaObservationInputReader;
import com.bifos.assistant.chat.application.model.MediaObservationInput;
import com.bifos.assistant.chat.application.model.MediaObservationPage;
import com.bifos.assistant.chat.application.model.MediaObservationView;
import com.bifos.assistant.chat.application.model.ObservationProvenance;
import com.bifos.assistant.chat.domain.type.ObservationProvenanceKind;
import com.bifos.assistant.chat.domain.type.ObservationStatus;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import tools.jackson.databind.JsonNode;

/** 사진 관찰 REST 요청과 응답을 모은다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class MediaObservationDtos {
    public record MediaObservationCorrectionRequest(long expectedRevision, UUID requestId, JsonNode observation) {
        public static MediaObservationCorrectionRequest from(JsonNode node) {
            MediaObservationInputReader.fields(node, Set.of("expectedRevision", "requestId", "observation"));
            return new MediaObservationCorrectionRequest(
                MediaObservationInputReader.revision(node.get("expectedRevision")),
                MediaObservationInputReader.requestId(node.get("requestId")),
                node.get("observation"));
        }


        @Override
        public String toString() {
            return "MediaObservationCorrectionRequest[body omitted]";
        }
    }

    public record MediaObservationPageResponse(List<MediaObservationResponse> items, String nextAfterAssetId) {
        public static MediaObservationPageResponse from(MediaObservationPage page) {
            return new MediaObservationPageResponse(
                page.items().stream().map(MediaObservationResponse::from).toList(), page.nextAfterAssetId());
        }
    }

    public record MediaObservationResponse(
        String assetId,
        int ordinal,
        String sourceFingerprint,
        Long revision,
        ObservationStatus status,
        MediaObservationInput observation,
        ObservationProvenance provenance,
        Instant expiresAt,
        String errorCode,
        String sourceAssurance) {
        public static MediaObservationResponse from(MediaObservationView view) {
            String assurance = view.observation() == null || view.provenance() == null
                ? null
                : view.provenance().kind() == ObservationProvenanceKind.USER_CORRECTION
                    ? "USER_CORRECTION"
                    : "MODEL_UNVERIFIED";
            return new MediaObservationResponse(
                view.assetId(),
                view.ordinal(),
                view.sourceFingerprint(),
                view.revision(),
                view.status(),
                view.observation(),
                view.provenance(),
                view.expiresAt(),
                view.errorCode(),
                assurance);
        }


        @Override
        public String toString() {
            return "MediaObservationResponse[body omitted]";
        }
    }
}
