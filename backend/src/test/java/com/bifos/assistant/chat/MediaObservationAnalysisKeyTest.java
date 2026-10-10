package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.chat.application.MediaObservationAnalysisKey;
import com.bifos.assistant.chat.application.model.MediaObservationInput.Coverage;
import com.bifos.assistant.chat.application.model.MediaObservationInput.Region;
import com.bifos.assistant.chat.application.model.ObservationProvenance;
import com.bifos.assistant.chat.domain.type.ObservationProvenanceKind;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

class MediaObservationAnalysisKeyTest {
    private final JsonMapper json =
            JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    private final String fingerprint = "a".repeat(64);
    private final Coverage original = new Coverage("ORIGINAL", null, null);
    private final ObservationProvenance source =
            source(123L, "provider", null, "model", "model-v3", 1, "media-observation-v1", Instant.EPOCH);

    @Test
    @DisplayName("독립 고정 SHA vector와 mapper 공백 설정을 검증한다")
    void matchesIndependentShaVectorsWithCompactSerialization() {
        assertThat(key(fingerprint, source, original))
                .isEqualTo("aa1b156a69c9ef357fa56ae208545c1a5f9fbf2001a60992906327af48351b7f");
        assertThat(key(fingerprint, source, new Coverage("CROP", new Region(-0.0, .25, .5, .5), null)))
                .isEqualTo("f37585120842ab363a07509706726b48e209dafe58d197d0ed5db562dfb8a953");
        assertThat(json.isEnabled(SerializationFeature.INDENT_OUTPUT)).isTrue();
    }

    @Test
    @DisplayName("미확인 provider 또는 model에는 분석 key를 만들지 않는다")
    void omitsKeyForUnknownProviderOrModel() {
        for (var unknown : List.of(
                source(123L, "UNKNOWN", null, "model", null, 1, "media-observation-v1", Instant.EPOCH),
                source(123L, "provider", null, "UNKNOWN", null, 1, "media-observation-v1", Instant.EPOCH),
                source(123L, "UNKNOWN", null, "UNKNOWN", null, 1, "media-observation-v1", Instant.EPOCH))) {
            assertThat(key(fingerprint, unknown, original)).isNull();
        }
        assertThat(key(
                        fingerprint,
                        source(123L, "provider", null, "model", null, 1, "media-observation-v1", Instant.EPOCH),
                        original))
                .isNotNull();
    }

    @Test
    @DisplayName("여덟 조건을 각각 구분하고 실행과 관측 시각을 제외한다")
    void distinguishesEachConditionButExcludesExecutionAndObservationTime() {
        String expected = key(fingerprint, source, original);
        assertThat(key("b".repeat(64), source, original)).isNotEqualTo(expected);
        for (var changed : List.of(
                source(123L, "Provider", null, "model", "model-v3", 1, "media-observation-v1", Instant.EPOCH),
                source(123L, "provider ", null, "model", "model-v3", 1, "media-observation-v1", Instant.EPOCH),
                source(123L, "provider", "", "model", "model-v3", 1, "media-observation-v1", Instant.EPOCH),
                source(123L, "provider", " ", "model", "model-v3", 1, "media-observation-v1", Instant.EPOCH),
                source(123L, "provider", null, "other", "model-v3", 1, "media-observation-v1", Instant.EPOCH),
                source(123L, "provider", null, "model", null, 1, "media-observation-v1", Instant.EPOCH),
                source(123L, "provider", null, "model", "model-v3", 2, "media-observation-v1", Instant.EPOCH),
                source(123L, "provider", null, "model", "model-v3", 1, "v2", Instant.EPOCH))) {
            assertThat(key(fingerprint, changed, original)).isNotEqualTo(expected);
        }
        assertThat(key(
                        fingerprint,
                        source(456L, "provider", null, "model", "model-v3", 1, "media-observation-v1", Instant.now()),
                        original))
                .isEqualTo(expected);
        var coverages = List.of(
                original,
                new Coverage("OVERVIEW", null, null),
                new Coverage("FIRST_FRAME", null, 0),
                new Coverage("CROP", new Region(0, .25, .5, .5), null));
        assertThat(coverages.stream()
                        .map(value -> key(fingerprint, source, value))
                        .toList())
                .doesNotHaveDuplicates();
        assertThat(key(fingerprint, source, new Coverage("CROP", new Region(0, .25, .5, .5), null)))
                .isEqualTo(key(fingerprint, source, new Coverage("CROP", new Region(-0.0, .25, .5, .5), null)))
                .isNotEqualTo(key(fingerprint, source, new Coverage("CROP", new Region(1e-15, .25, .5, .5), null)));
    }

    private String key(String hash, ObservationProvenance provenance, Coverage coverage) {
        return MediaObservationAnalysisKey.compute(json, hash, provenance, coverage);
    }

    private static ObservationProvenance source(
            Long execution,
            String provider,
            String providerVersion,
            String model,
            String modelVersion,
            int schema,
            String prompt,
            Instant observedAt) {
        return new ObservationProvenance(
                ObservationProvenanceKind.MODEL_RESULT,
                execution,
                provider,
                providerVersion,
                model,
                modelVersion,
                schema,
                prompt,
                observedAt);
    }
}
