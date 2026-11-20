package com.bifos.assistant.mcp.application;

import com.bifos.assistant.mcp.application.dto.McpCaller;
import com.bifos.assistant.mcp.application.port.ConnectorAttachRequests;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * 에이전트가 요청자의 연결을 지금 에이전트에 붙여 달라고 청하는 MCP 도구다(스펙 service-concepts REQ15). 붙이지 않고 승인 카드만 띄운다.
 *
 * <p>붙일 에이전트는 인자가 아니라 origin 실행의 에이전트다. 하지 못했으면 {@code isError} 와 까닭이고, 예외가 나도 turn 을 막지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpConnectorAttachTool {
    public static final String NAME = "connector_attach";
    /** 플러그인 번호의 글자 수 상한이다. 승인 줄의 {@code connector_id} 칸과 같다. */
    static final int MAX_CONNECTOR_ID = 64;
    static final String UNAVAILABLE = "붙이기를 지금 청하지 못했다. 붙였다고 말하지 않고, 잠시 뒤 다시 부탁하거나 /connections 화면에서 붙이라고 안내한다.";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ConnectorAttachRequests requests;


    /** 플러그인 번호로 받을 수 있는 글인가. 비었거나 너무 길면 인자 오류다. */
    public static boolean accepts(String connectorId) {
        return !connectorId.isEmpty() && connectorId.length() <= MAX_CONNECTOR_ID;
    }


    public Map<String, Object> attach(McpCaller caller, String connectorId) {
        try {
            var origin = caller.originExecution();
            Map<String, Object> body = requests.request(caller.user(), origin.id(), origin.agentId(), connectorId);
            Object status = body.get("status");
            boolean done = ConnectorAttachRequests.REQUESTED.equals(status) || ConnectorAttachRequests.ALREADY_ATTACHED.equals(status);
            return McpToolService.result(JSON.writeValueAsString(body), !done);
        } catch (RuntimeException ex) {
            log.warn("connector attach tool failed executionId={} kind={}", caller.executionId(), ex.getClass().getSimpleName());
            return McpToolService.result(JSON.writeValueAsString(Map.of("status", "unavailable", "message", UNAVAILABLE)), true);
        }
    }


    public Map<String, Object> definition() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("properties", Map.of("connector", Map.of("type", "string", "minLength", 1, "maxLength", MAX_CONNECTOR_ID)));
        schema.put("required", List.of("connector"));
        return Map.of(
            "name", NAME,
            "description",
            "사용자가 이미 연결한 외부 서비스를 지금 이 에이전트에서 쓰게 해 달라고 하거나(「○○ 여기서 쓰게 해 줘」), 연결 화면에서 "
                + "연결을 마쳤다고 하면 「이 에이전트에 붙이기」 승인 카드를 띄운다. connector 는 connector_list 의 id 다. "
                + "connector_list 에서 connected 가 true 이고 thisAgent 가 none 일 때 부른다. connected 가 false 면 부르지 않고 link 로 연결을 안내한다. "
                + "이 도구는 붙이지 않는다. 사용자가 카드에서 승인해야 붙으므로 붙였다고 말하지 않고 승인 카드에서 승인하면 붙는다고 알린다. "
                + "isError 면 카드를 띄우지 못한 것이다. message 의 까닭을 알리고 link 가 있으면 그대로 마크다운 링크로 붙인다. "
                + "떼기와 다른 에이전트에 붙이기는 하지 못한다. /connections 화면을 안내한다.",
            "inputSchema", schema);
    }
}
