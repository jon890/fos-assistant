package com.bifos.assistant.mcp.application;

import com.bifos.assistant.mcp.application.dto.McpCaller;
import com.bifos.assistant.mcp.application.port.ConnectorAttachRequests;
import com.bifos.assistant.shared.util.ToolFailureText;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 에이전트가 요청자의 연결을 지금 에이전트에 붙여 달라고 청하는 MCP 도구다(스펙 service-concepts REQ15). 붙이지 않고 승인 카드만 띄운다.
 *
 * <p>붙일 에이전트는 인자가 아니라 승인할 때 새로 만드는 전문 에이전트다(비서 중심 대화 스펙 REQ9). 하지 못했으면 {@code isError} 와 까닭이고,
 * 예외가 나도 turn 을 막지 않는다.
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


    /** 인자는 비지 않은 글 {@code connector} 하나다. 다른 키가 있거나 글이 비었거나 너무 길면 빈 값이고 인자 오류다. */
    public static Optional<String> connectorOf(JsonNode arguments) {
        JsonNode value = arguments.get("connector");
        if (arguments.size() != 1 || value == null || !value.isString()) {
            return Optional.empty();
        }
        String connector = value.asString().strip();
        return connector.isEmpty() || connector.length() > MAX_CONNECTOR_ID ? Optional.empty() : Optional.of(connector);
    }


    public Map<String, Object> attach(McpCaller caller, String connectorId) {
        try {
            var origin = caller.originExecution();
            Map<String, Object> body = requests.request(caller.user(), origin.id(), connectorId);
            String status = String.valueOf(body.get("status"));
            if (ConnectorAttachRequests.REQUESTED.equals(status) || ConnectorAttachRequests.ALREADY_ATTACHED.equals(status)
                || ConnectorAttachRequests.SPECIALIST_ATTACHED.equals(status)) {
                return McpToolService.success(JSON.writeValueAsString(body));
            }
            return McpToolService.jsonFailure(body, nextFor(status));
        } catch (RuntimeException ex) {
            log.warn("connector attach tool failed executionId={} kind={}", caller.executionId(), ex.getClass().getSimpleName());
            return McpToolService.jsonFailure(Map.of("status", "unavailable", "message", UNAVAILABLE), nextFor("unavailable"));
        }
    }


    /** 붙이기를 청하지 못한 까닭마다 모델이 해 볼 다음 방법이다(docs/features/tool-fallback/design.md 의 「바꾸는 실패 글」). */
    static ToolFailureText nextFor(String status) {
        return switch (status) {
            case "not_found" -> ToolFailureText.alternative(status, "connector_list 로 id 를 찾아 다시 부르고, 없는 서비스면 없다고 알리고 link 를 붙인다");
            case "unavailable" -> ToolFailureText.alternative(status, "다른 방법이 없으니 /connections 화면에서 붙이라고 안내하고 잠시 뒤 다시 해 달라고 알린다");
            default -> ToolFailureText.askUser(status);
        };
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
            "사용자가 이미 연결한 외부 서비스를 쓰게 해 달라고 하거나(「○○ 쓰게 해 줘」), 연결 화면에서 연결을 마쳤다고 하면 "
                + "「그 서비스만 맡는 새 전문 에이전트를 만들어 붙이기」 승인 카드를 띄운다. connector 는 connector_list 의 id 다. "
                + "connected 가 false 면 부르지 않고 link 로 연결을 안내한다. "
                + "status 가 specialist_attached 면 그 서비스가 붙은 전문 에이전트가 이미 있다. 카드는 없고 agent_code 의 에이전트에게 agent_delegate 로 맡긴다. "
                + "이 도구는 만들거나 붙이지 않는다. 사용자가 카드에서 승인해야 만들어 붙으므로 했다고 말하지 않고 승인하면 만들어 붙는다고 알린다. "
                + "isError 면 카드를 띄우지 못한 것이다. message 의 까닭을 알리고 link 가 있으면 그대로 마크다운 링크로 붙인다. "
                + "떼기와 이미 있는 다른 에이전트에 붙이기는 하지 못한다. /connections 화면을 안내한다.",
            "inputSchema", schema);
    }
}
