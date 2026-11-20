package com.bifos.assistant.hermes.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HermesConnectorBodiesTest {

    @Test
    @DisplayName("중계 주소가 null 이면 본문을 그대로 두고, 있으면 owner_browser 를 더한다")
    void withOwnerBrowserAddsKeyOnlyWhenPresent() {
        Map<String, Object> request = Map.of("tool", "list");

        assertThat(HermesConnectorBodies.withOwnerBrowser(request, null)).isSameAs(request);
        assertThat(HermesConnectorBodies.withOwnerBrowser(request, "http://relay"))
            .containsEntry("tool", "list")
            .containsEntry("owner_browser", "http://relay");
    }


    @Test
    @DisplayName("인자 글이 JSON object 면 읽고, object 가 아니거나 읽지 못하면 null 이다")
    void argumentsReadsOnlyObjects() {
        assertThat(HermesConnectorBodies.arguments("{\"q\":1}").get("q").asInt()).isEqualTo(1);
        assertThat(HermesConnectorBodies.arguments("[1]")).isNull();
        assertThat(HermesConnectorBodies.arguments("{")).isNull();
    }
}
