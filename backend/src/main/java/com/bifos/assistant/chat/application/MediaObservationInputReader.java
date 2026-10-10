package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.application.model.MediaObservationInput;
import com.bifos.assistant.chat.application.model.ObservationProvenance;
import com.bifos.assistant.chat.domain.type.ObservationProvenanceKind;
import com.bifos.assistant.chat.domain.type.ObservationStatus;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 제출자 선언만 읽는다. 출처와 최상위 근거는 서버가 만들고 실패에는 원문을 싣지 않는다. */
@Service
@RequiredArgsConstructor
public class MediaObservationInputReader {
    private final ObjectMapper json;

    public MediaObservationInput read(JsonNode node, ObservationProvenance source) {
        try {
            fields(node, Set.of("status", "summary", "claims", "uncertainties", "coverage", "errorCode"));
            text(node.get("status"), 32, true);
            text(node.get("summary"), 2000, false);
            text(node.get("errorCode"), 64, false);
            array(node.get("claims"), 50);
            array(node.get("uncertainties"), 30);
            JsonNode claims = node.path("claims");
            for (JsonNode claim : claims) {
                fields(claim, Set.of("kind", "text", "confidence", "evidence"));
                text(claim.get("kind"), 16, true);
                text(claim.get("text"), 500, true);
                text(claim.get("confidence"), 16, true);
                array(claim.get("evidence"), MediaObservationInput.MAX_BODY_BYTES);
                if (!claim.path("evidence").isArray() || claim.path("evidence").isEmpty()) {
                    throw invalid();
                }
                for (JsonNode evidence : claim.path("evidence")) {
                    text(evidence, 500, true);
                }
            }
            for (JsonNode uncertainty : node.path("uncertainties")) {
                text(uncertainty, 500, true);
            }
            JsonNode coverage = node.get("coverage");
            if (coverage != null && !coverage.isNull()) {
                fields(coverage, Set.of("mode", "region", "frame"));
                text(coverage.get("mode"), 16, true);
                JsonNode frame = coverage.get("frame");
                if (frame != null && !frame.isNull() && (!frame.isIntegralNumber() || !frame.canConvertToInt())) {
                    throw invalid();
                }
                JsonNode region = coverage.get("region");
                if (region != null && !region.isNull()) {
                    fields(region, Set.of("x", "y", "width", "height"));
                    for (String key : Set.of("x", "y", "width", "height")) {
                        JsonNode value = region.get(key);
                        if (value == null || !value.isNumber() || !Double.isFinite(value.doubleValue())) {
                            throw invalid();
                        }
                    }
                }
            }
            // 복사·변환 전에 누적 문자열 크기를 제한한다. 근거 배열도 이 예산을 함께 쓴다.
            budget(node, new int[] {0});
            var input = json.treeToValue(node, MediaObservationInput.class);
            var kind = source.kind();
            boolean user = kind == ObservationProvenanceKind.USER_CORRECTION;
            if (user && input.status() != ObservationStatus.SUCCEEDED && input.status() != ObservationStatus.PARTIAL) {
                throw invalid();
            }
            for (MediaObservationInput.Claim claim : input.claims()) {
                if (user
                        ? !"USER".equals(claim.kind())
                        : !Set.of("VISUAL", "OCR").contains(claim.kind())) {
                    throw invalid();
                }
            }
            var evidence = input.status() == ObservationStatus.FAILED
                    ? null
                    : new MediaObservationInput.Evidence(
                            kind,
                            user ? "USER_CORRECTION" : source.executionId().toString());
            input = new MediaObservationInput(
                    input.status(),
                    input.summary(),
                    input.claims(),
                    input.uncertainties(),
                    input.coverage(),
                    evidence,
                    input.errorCode());
            input.validate(source);
            if (json.writeValueAsBytes(input).length > MediaObservationInput.MAX_BODY_BYTES) {
                throw invalid();
            }
            return input;
        } catch (RuntimeException ex) {
            throw invalid();
        }
    }

    public static void fields(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject() || !allowed.containsAll(node.propertyNames())) {
            throw invalid();
        }
    }

    public static long revision(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() < 0) {
            throw invalid();
        }
        return node.longValue();
    }

    public static UUID requestId(JsonNode node) {
        if (node == null
                || !node.isTextual()
                || !node.asString()
                        .matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw invalid();
        }
        return UUID.fromString(node.asString());
    }

    public static Long assetId(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]*")) {
                throw invalid();
            }
            return Long.valueOf(value);
        } catch (NumberFormatException ex) {
            throw invalid();
        }
    }

    public static ApiException invalid() {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "invalid media observation");
    }

    private static void text(JsonNode value, int max, boolean required) {
        if (value == null || value.isNull()) {
            if (required) {
                throw invalid();
            }
        } else if (!value.isTextual()
                || value.asString().codePointCount(0, value.asString().length()) > max
                || required && value.asString().isBlank()) {
            throw invalid();
        }
    }

    private static void array(JsonNode value, int max) {
        if (value != null && !value.isNull() && (!value.isArray() || value.size() > max)) {
            throw invalid();
        }
    }

    private static void budget(JsonNode node, int[] bytes) {
        if (node.isTextual()) {
            bytes[0] += node.asString().getBytes(StandardCharsets.UTF_8).length;
            if (bytes[0] > MediaObservationInput.MAX_BODY_BYTES) {
                throw invalid();
            }
        } else if (node.isObject() || node.isArray()) {
            for (JsonNode child : node) {
                budget(child, bytes);
            }
        }
    }
}
