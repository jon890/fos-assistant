package com.bifos.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** backend 의 모양 검사가 실제 mcp 패키지의 판정과 같은 fixture 를 같은 쪽으로 가른다. */
class McpToolResultShapeTest {
    @Test
    @DisplayName("Hermes 의 CallToolResult 가 받는 결과는 받고, 운영에서 거절된 값 객체 결과는 거절한다")
    void agreesWithCallToolResultFixture() throws Exception {
        JsonNode fixture = McpToolResultShape.fixture();
        assertThat(fixture.path("accepted").size()).isPositive();
        assertThat(fixture.path("rejected").size()).isPositive();
        for (JsonNode item : fixture.path("accepted")) {
            assertThat(McpToolResultShape.accepts(item.path("result"))).as(item.path("name").asString()).isTrue();
        }
        for (JsonNode item : fixture.path("rejected")) {
            assertThat(McpToolResultShape.accepts(item.path("result"))).as(item.path("name").asString()).isFalse();
        }
    }
}
