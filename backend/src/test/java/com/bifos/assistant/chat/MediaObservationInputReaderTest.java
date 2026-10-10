package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.chat.application.MediaObservationInputReader;
import com.bifos.assistant.chat.application.model.ObservationProvenance;
import com.bifos.assistant.chat.domain.type.ObservationProvenanceKind;
import com.bifos.assistant.chat.domain.type.ObservationStatus;
import com.bifos.assistant.shared.error.ApiException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class MediaObservationInputReaderTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final MediaObservationInputReader reader = new MediaObservationInputReader(json);
    private final ObservationProvenance model = ObservationFixture.model();
    private final ObservationProvenance user = new ObservationProvenance(
            ObservationProvenanceKind.USER_CORRECTION, null, null, null, null, null, 1, "media-observation-v1", null);

    @Test
    @DisplayName("선택 null을 허용하고 서버가 출처 근거를 만든다")
    void acceptsOptionalNullsAndMakesEvidence() {
        var input = reader.read(json.readTree("""
                {"status":"SUCCEEDED","summary":null,"claims":null,"uncertainties":null,
                 "coverage":{"mode":"ORIGINAL","region":null,"frame":null},"errorCode":null}
                """), model);
        assertThat(input.claims()).isEmpty();
        assertThat(input.uncertainties()).isEmpty();
        assertThat(input.evidence().reference()).isEqualTo("123");
        assertThat(reader.read(json.readTree("""
                {"status":"FAILED","errorCode":"MODEL_ERROR"}
                """), model).evidence()).isNull();
        assertThat(reader.read(json.readTree("""
                {"status":"PARTIAL","uncertainties":["범위 미확인"],"coverage":{"mode":"FIRST_FRAME","frame":0}}
                """), user).evidence().reference()).isEqualTo("USER_CORRECTION");
    }

    @Test
    @DisplayName("모르는 칸과 형 변환 상태 불일치 근거 주입을 거절한다")
    void rejectsUnknownFieldsTypeCoercionStatusAndInjectedEvidence() {
        for (String body : List.of(
                "null",
                "[]",
                "{}",
                "{\"status\":\"FAILED\",\"errorCode\":\"secret raw error\"}",
                "{\"status\":\"SUCCEEDED\",\"coverage\":null}",
                "{\"status\":\"SUCCEEDED\",\"summary\":1,\"coverage\":{\"mode\":\"ORIGINAL\"}}",
                "{\"status\":\"SUCCEEDED\",\"evidence\":{},\"coverage\":{\"mode\":\"ORIGINAL\"}}",
                "{\"status\":\"SUCCEEDED\",\"coverage\":{\"mode\":\"ORIGINAL\",\"extra\":1}}",
                "{\"status\":\"SUCCEEDED\",\"coverage\":{\"mode\":\"FIRST_FRAME\",\"frame\":0.0}}",
                "{\"status\":\"SUCCEEDED\",\"coverage\":{\"mode\":\"CROP\",\"region\":{\"x\":\"0\",\"y\":0,\"width\":1,\"height\":1}}}",
                "{\"status\":\"SUCCEEDED\",\"claims\":[{\"kind\":\"USER\",\"text\":\"s\",\"confidence\":\"CONFIRMED\",\"evidence\":[\"s\"]}],\"coverage\":{\"mode\":\"ORIGINAL\"}}")) {
            assertThatThrownBy(() -> reader.read(json.readTree(body), model))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getMessage()).isEqualTo("invalid media observation");
                        assertThat(ex.getCause()).isNull();
                    });
        }
        assertThatThrownBy(
                        () -> reader.read(json.readTree("{\"status\":\"FAILED\",\"errorCode\":\"MODEL_ERROR\"}"), user))
                .isInstanceOf(ApiException.class);
        assertThat(reader.read(json.readTree("{\"status\":\"FAILED\",\"errorCode\":\"MODEL_ERROR\"}"), model)
                        .status())
                .isEqualTo(ObservationStatus.FAILED);
    }

    @Test
    @DisplayName("중첩된 모르는 칸과 UTF-8 초과를 저장 전에 거절한다")
    void rejectsNestedUnknownsAndUtf8OverflowBeforeStoring() {
        var node = json.createObjectNode().put("status", "SUCCEEDED").put("summary", "😀".repeat(2001));
        node.putObject("coverage").put("mode", "ORIGINAL");
        assertThatThrownBy(() -> reader.read(node, model)).isInstanceOf(ApiException.class);
        node.put("summary", "합성 OCR");
        var claims = node.putArray("claims");
        var claim = claims.addObject().put("kind", "OCR").put("text", "합성 OCR").put("confidence", "CONFIRMED");
        var evidence = claim.putArray("evidence");
        for (int index = 0; index < 30; index++) {
            evidence.add("😀".repeat(500));
        }
        assertThatThrownBy(() -> reader.read(node, model)).isInstanceOf(ApiException.class);
        evidence.removeAll().add("합성 OCR");
        claim.put("provider", "injected");
        assertThatThrownBy(() -> reader.read(node, model)).isInstanceOf(ApiException.class);
        claim.remove("provider");
        node.withObject("coverage")
                .put("mode", "CROP")
                .putObject("region")
                .put("x", 0)
                .put("y", 0)
                .put("width", 1)
                .put("height", 1)
                .put("extra", 1);
        assertThatThrownBy(() -> reader.read(node, model)).isInstanceOf(ApiException.class);
    }
}
