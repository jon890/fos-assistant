package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.application.model.MediaObservationInput.Coverage;
import com.bifos.assistant.chat.application.model.ObservationProvenance;
import com.bifos.assistant.shared.util.Sha256;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

/** 미확인 정체성을 제외한 분석 조건을 고정 배열로 직렬화한다. 실제 provider 신원을 검증하지는 않는다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class MediaObservationAnalysisKey {

    public static String compute(
            ObjectMapper json, String fingerprint, ObservationProvenance source, Coverage coverage) {
        if (hasUnknownIdentity(source)) {
            return null;
        }
        var region = coverage.region();
        List<String> coordinates = region == null
                ? null
                : List.of(
                        coordinate(region.x()),
                        coordinate(region.y()),
                        coordinate(region.width()),
                        coordinate(region.height()));
        return Sha256.hex(json.writer()
                .without(SerializationFeature.INDENT_OUTPUT)
                .writeValueAsString(Arrays.asList(
                        fingerprint,
                        source.schemaVersion(),
                        source.promptVersion(),
                        source.provider(),
                        source.providerVersion(),
                        source.model(),
                        source.modelVersion(),
                        Arrays.asList(coverage.mode(), coordinates, coverage.frame()))));
    }

    public static boolean hasUnknownIdentity(ObservationProvenance source) {
        return ObservationProvenance.UNKNOWN_IDENTITY.equals(source.provider())
                || ObservationProvenance.UNKNOWN_IDENTITY.equals(source.model());
    }

    private static String coordinate(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
