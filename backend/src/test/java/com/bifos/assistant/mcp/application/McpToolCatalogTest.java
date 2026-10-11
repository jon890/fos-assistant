package com.bifos.assistant.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.orchestration.application.DelegationProperties;
import com.bifos.assistant.shared.config.LiveProperties;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class McpToolCatalogTest {
    @Test
    @DisplayName("정의 순서를 유지하고 호출할 때마다 먼저 살펴보기의 현재 대기 상한을 읽는다")
    @SuppressWarnings("unchecked")
    void keepsDefinitionOrderAndReadsCurrentWaitMaxAtEveryCall() {
        LiveProperties<DelegationProperties> properties = mock(LiveProperties.class);
        var remember = mock(McpMemoryRemember.class);
        var observations = mock(McpMediaObservationTools.class);
        Map<String, Object> list = Map.of("name", McpMediaObservationTools.LIST);
        Map<String, Object> record = Map.of("name", McpMediaObservationTools.RECORD);
        Map<String, Object> memory = Map.of("name", "memory_remember");
        when(observations.listDefinition()).thenReturn(list);
        when(observations.recordDefinition()).thenReturn(record);
        when(remember.definition()).thenReturn(memory);
        when(properties.current()).thenReturn(settings(20), settings(40));
        var catalog = new McpToolCatalog(properties, remember, observations);

        var first = catalog.tools();
        assertThat(first).extracting(tool -> tool.get("name"))
            .doesNotHaveDuplicates()
            .containsExactly("memory_read", "artifact_write", "agent_list", "agent_delegate", "agent_status",
                "agent_stop", "follow_up_propose", McpMediaObservationTools.LIST, McpMediaObservationTools.RECORD,
                "memory_remember");
        assertThat(first).contains(list, record, memory);
        assertThat(description(first))
            .contains("결과는 끝나면 자동으로 전달되므로 기다리려고 반복해서 부르지 않는다. 사용자가 진행 상황을 물을 때 한 번 부른다.")
            .endsWith("먼저 살펴보기에서는 wait_seconds 로 끝나기를 기다린다(최대 20초).");
        assertThat(description(catalog.tools())).endsWith("먼저 살펴보기에서는 wait_seconds 로 끝나기를 기다린다(최대 40초).");
    }

    private static String description(List<Map<String, Object>> definitions) {
        return definitions.stream().filter(tool -> "agent_status".equals(tool.get("name")))
            .map(tool -> (String)tool.get("description")).findFirst().orElseThrow();
    }

    private static DelegationProperties settings(int waitSeconds) {
        return new DelegationProperties(2, 4, 16, Duration.ofSeconds(30), 1000, Duration.ofSeconds(waitSeconds));
    }
}
