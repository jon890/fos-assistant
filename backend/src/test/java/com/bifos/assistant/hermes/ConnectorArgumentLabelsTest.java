package com.bifos.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.hermes.dto.ConnectorArgumentLabels;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ConnectorArgumentLabelsTest {
    @Test
    @DisplayName("인자 표시 이름은 잘못된 키와 타입 및 충돌과 제어문자를 거절한다")
    void rejectsMalformedMetadata() {
        for (Map<?, ?> labels : List.of(Map.of("a", 1), Map.of("a", List.of()), Map.of("a", ""),
            Map.of("__proto__", "이름"), Map.of("constructor", "이름"), Map.of("prototype", "이름"),
            Map.of("a".repeat(32), "이름"), Map.of("a", " 이름"), Map.of("a", "이름 "),
            Map.of("a", "가".repeat(81)), Map.of("a", "겹침", "b", "겹침"), Map.of("a", "b", "b", "이름"))) {
            assertThat(ConnectorArgumentLabels.read(labels)).isEmpty();
        }
        for (int code : new int[]{0, 9, 10, 31, 127, 159, 0x61c, 0x200e, 0x2028, 0x2029, 0x202e, 0x2066, 0xd800}) {
            assertThat(ConnectorArgumentLabels.read(Map.of("a", "앞" + (char) code + "뒤"))).isEmpty();
        }
    }

    @Test
    @DisplayName("Unicode 글자 수와 compact JSON의 UTF-8 바이트 및 항목 수 경계를 지킨다")
    void enforcesUnicodeAndByteBounds() {
        assertThat(ConnectorArgumentLabels.read(Map.of("a", "😀".repeat(80)))).containsKey("a");
        Map<String, String> labels = new LinkedHashMap<>();
        for (int index = 0; index < 64; index++) {
            labels.put("k" + index, "😀".repeat(60) + index);
        }
        int remaining = 16384 - JsonMapper.builder().build().writeValueAsBytes(labels).length;
        for (int index = 0; index < 64; index++) {
            String value = labels.get("k" + index);
            int added = Math.min(80 - value.codePointCount(0, value.length()), remaining);
            labels.put("k" + index, value + "x".repeat(added));
            remaining -= added;
        }
        assertThat(remaining).isZero();
        assertThat(ConnectorArgumentLabels.read(labels)).isEqualTo(labels);
        labels.put("k63", labels.get("k63") + "x");
        assertThat(ConnectorArgumentLabels.read(labels)).isEmpty();
        labels.clear();
        for (int index = 0; index < 65; index++) {
            labels.put("k" + index, "이름" + index);
        }
        assertThat(ConnectorArgumentLabels.read(labels)).isEmpty();
    }
}
