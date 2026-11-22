package com.bifos.assistant.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.mcp.application.dto.McpCallContext;
import com.bifos.assistant.mcp.application.dto.McpCaller;
import com.bifos.assistant.mcp.application.port.ConnectorListing;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@code connector_list} 가 목록을 글 하나로 감싸고, 읽기 실패를 실패 결과와 안내로 바꾸는지 확인한다(스펙 service-concepts REQ5). */
class McpConnectorListToolTest {

    private static final CurrentUser USER = new CurrentUser(1L, "user@example.com", "user", 10L, UserRole.MEMBER);

    private final ConnectorListing listing = mock(ConnectorListing.class);
    private final McpConnectorListTool tool = new McpConnectorListTool(listing);


    @Test
    @DisplayName("목록을 JSON 글 하나로 감싼다")
    void wrapsListAsTextResult() {
        McpCaller caller = caller();
        when(listing.list(USER, caller.originExecution().agentId())).thenReturn(List.of(Map.of("id", "calendar")));

        Map<String, Object> result = tool.list(caller);

        assertThat(result).containsEntry("isError", false);
        assertThat(text(result)).isEqualTo("[{\"id\":\"calendar\"}]");
    }


    @Test
    @DisplayName("목록을 읽지 못하면 실패 결과와 연결 화면 안내를 준다")
    void failureTellsToGuideConnectionsScreen() {
        when(listing.list(any(), any())).thenThrow(new IllegalStateException("catalog"));

        Map<String, Object> result = tool.list(caller());

        assertThat(result).containsEntry("isError", true);
        assertThat(text(result)).contains("/connections", "연결했다고 말하지 않는다");
    }


    @Test
    @DisplayName("정의는 인자를 받지 않고 연결했다고 말하지 말라고 한다")
    void definitionTakesNoArgumentsAndSaysNotToClaimConnection() {
        Map<String, Object> definition = tool.definition();

        assertThat(definition).containsEntry("name", "connector_list");
        assertThat((String)definition.get("description")).contains("link", "연결했다고 말하지 않는다", "/connections");
        assertThat(((Map<?, ?>)definition.get("inputSchema")).get("additionalProperties")).isEqualTo(false);
    }


    @SuppressWarnings("unchecked")
    private static String text(Map<String, Object> result) {
        return (String)((List<Map<String, Object>>)result.get("content")).getFirst().get("text");
    }


    private static McpCaller caller() {
        AgentExecution execution = mock(AgentExecution.class);
        when(execution.agentId()).thenReturn(7L);
        when(execution.id()).thenReturn(3L);
        return new McpCaller(USER, execution, new McpCallContext("root", "session", "call"));
    }
}
