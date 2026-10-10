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

/** 검증된 분석 조건만 고정 배열로 직렬화한다. 결과 본문과 실행 시각은 포함하지 않는다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class MediaObservationAnalysisKey {

    public static String compute(
            ObjectMapper json, String fingerprint, ObservationProvenance source, Coverage coverage) {
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

    private static String coordinate(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
