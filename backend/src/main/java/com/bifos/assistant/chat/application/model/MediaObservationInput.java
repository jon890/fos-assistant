package com.bifos.assistant.chat.application.model;

import com.bifos.assistant.chat.domain.type.ObservationProvenanceKind;
import com.bifos.assistant.chat.domain.type.ObservationStatus;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** 관찰 본문은 Unicode code point와 직렬화한 UTF-8 크기로 제한한다. */
@JsonPropertyOrder({"status", "summary", "claims", "uncertainties", "coverage", "evidence", "errorCode"})
public record MediaObservationInput(
        ObservationStatus status,
        String summary,
        List<Claim> claims,
        List<String> uncertainties,
        Coverage coverage,
        Evidence evidence,
        String errorCode) {
    public static final int MAX_BODY_BYTES = 32 * 1024;

    public MediaObservationInput {
        claims = freeze(claims);
        uncertainties = freeze(uncertainties);
    }

    public void validate(ObservationProvenance source) {
        if (status == null
                || status == ObservationStatus.NOT_ANALYZED
                || status == ObservationStatus.UNAVAILABLE
                || claims.size() > 50
                || uncertainties.size() > 30
                || source == null
                || source.kind() == null
                || source.kind() == ObservationProvenanceKind.SYNTHETIC_MEASUREMENT
                || source.schemaVersion() != ObservationProvenance.SCHEMA_VERSION
                || !ObservationProvenance.PROMPT_VERSION.equals(source.promptVersion())) {
            throw invalid();
        }
        text(summary, 2000, false);
        uncertainties.forEach(value -> text(value, 500, true));
        for (Claim claim : claims) {
            if (claim == null
                    || claim.kind() == null
                    || claim.confidence() == null
                    || !Set.of("VISUAL", "OCR", "USER").contains(claim.kind())
                    || !Set.of("CONFIRMED", "UNCERTAIN").contains(claim.confidence())
                    || claim.evidence().isEmpty()) {
                throw invalid();
            }
            text(claim.text(), 500, true);
            claim.evidence().forEach(value -> text(value, 500, true));
        }
        if (source.kind() == ObservationProvenanceKind.USER_CORRECTION) {
            if (source.executionId() != null
                    || source.provider() != null
                    || source.providerVersion() != null
                    || source.model() != null
                    || source.modelVersion() != null) {
                throw invalid();
            }
        } else {
            if (source.executionId() == null || source.executionId() <= 0) {
                throw invalid();
            }
            text(source.provider(), 128, true);
            text(source.model(), 128, true);
            text(source.providerVersion(), 128, false);
            text(source.modelVersion(), 128, false);
        }
        if (status == ObservationStatus.FAILED) {
            if (errorCode == null
                    || !errorCode.matches("[A-Z][A-Z0-9_]{0,63}")
                    || summary != null
                    || !claims.isEmpty()
                    || !uncertainties.isEmpty()
                    || coverage != null
                    || evidence != null) {
                throw invalid();
            }
            return;
        }
        if (errorCode != null
                || coverage == null
                || status == ObservationStatus.PARTIAL && uncertainties.isEmpty()
                || status == ObservationStatus.SUCCEEDED && !uncertainties.isEmpty()
                || status != ObservationStatus.PROCESSING && evidence == null) {
            throw invalid();
        }
        coverage.validate();
        if (evidence != null) {
            text(evidence.reference(), 128, true);
            String reference = source.kind() == ObservationProvenanceKind.USER_CORRECTION
                    ? "USER_CORRECTION"
                    : source.executionId().toString();
            if (evidence.kind() != source.kind() || !reference.equals(evidence.reference())) {
                throw invalid();
            }
        }
    }

    private static <T> List<T> freeze(List<T> values) {
        return values == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static void text(String value, int limit, boolean required) {
        if (value == null ? required : value.codePointCount(0, value.length()) > limit || required && value.isBlank()) {
            throw invalid();
        }
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "invalid media observation");
    }

    @JsonPropertyOrder({"kind", "text", "confidence", "evidence"})
    public record Claim(String kind, String text, String confidence, List<String> evidence) {
        public Claim {
            evidence = freeze(evidence);
        }
    }

    @JsonPropertyOrder({"kind", "reference"})
    public record Evidence(ObservationProvenanceKind kind, String reference) {}

    @JsonPropertyOrder({"mode", "region", "frame"})
    public record Coverage(String mode, Region region, Integer frame) {
        private void validate() {
            if (mode == null
                    || !Set.of("ORIGINAL", "OVERVIEW", "CROP", "FIRST_FRAME").contains(mode)
                    || "CROP".equals(mode) != (region != null)
                    || ("FIRST_FRAME".equals(mode) ? !Integer.valueOf(0).equals(frame) : frame != null)) {
                throw invalid();
            }
            if (region != null
                    && (!Double.isFinite(region.x())
                            || !Double.isFinite(region.y())
                            || !Double.isFinite(region.width())
                            || !Double.isFinite(region.height())
                            || region.x() < 0
                            || region.y() < 0
                            || region.width() <= 0
                            || region.height() <= 0
                            || region.x() + region.width() > 1
                            || region.y() + region.height() > 1)) {
                throw invalid();
            }
        }
    }

    @JsonPropertyOrder({"x", "y", "width", "height"})
    public record Region(double x, double y, double width, double height) {}
}
