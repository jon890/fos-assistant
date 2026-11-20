package com.bifos.assistant.mcp.application;

import com.bifos.assistant.mcp.application.dto.McpCaller;
import com.bifos.assistant.mcp.application.port.MemoryForgetRequests;
import com.bifos.assistant.shared.util.ToolFailureText;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * 에이전트가 요청자의 기억 하나를 지워 달라고 청하는 MCP 도구다(스펙 service-concepts REQ13). 지우지 않고 승인 카드만 띄운다.
 *
 * <p>결과에 기억의 제목과 내용이 없다. 하지 못했으면 {@code isError} 와 까닭이고, 예외가 나도 turn 을 막지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpMemoryForgetTool {
    public static final String NAME = "memory_forget";
    static final String UNAVAILABLE = "기억 지우기를 지금 청하지 못했다. 지웠다고 말하지 않고, 잠시 뒤 다시 부탁하거나 /memory 화면에서 지우라고 안내한다.";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final MemoryForgetRequests requests;


    public Map<String, Object> forget(McpCaller caller, long memoryId) {
        try {
            Map<String, Object> body = requests.request(caller.user(), caller.originExecution().id(), memoryId);
            if (MemoryForgetRequests.REQUESTED.equals(body.get("status"))) {
                return McpToolService.success(JSON.writeValueAsString(body));
            }
            return McpToolService.jsonFailure(body, nextFor(String.valueOf(body.get("status"))));
        } catch (RuntimeException ex) {
            log.warn("memory forget tool failed executionId={} kind={}", caller.executionId(), ex.getClass().getSimpleName());
            return McpToolService.jsonFailure(Map.of("status", "unavailable", "message", UNAVAILABLE), nextFor("unavailable"));
        }
    }


    /** 기억 지우기를 청하지 못한 까닭마다 모델이 해 볼 다음 방법이다(docs/features/tool-fallback/design.md 의 「바꾸는 실패 글」). */
    static ToolFailureText nextFor(String status) {
        return switch (status) {
            case "not_found" -> ToolFailureText.alternative(status, "memory_search 로 지울 기억의 번호를 찾아 다시 부르고, 없으면 사용자에게 되묻는다");
            case "unavailable" -> ToolFailureText.alternative(status, "다른 방법이 없으니 /memory 화면에서 지우라고 안내하고 잠시 뒤 다시 해 달라고 알린다");
            default -> ToolFailureText.askUser(status);
        };
    }


    public Map<String, Object> definition() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("properties", Map.of("memory_id", Map.of("type", "integer")));
        schema.put("required", List.of("memory_id"));
        return Map.of(
            "name", NAME,
            "description",
            "사용자가 기억을 지워 달라고 하면(「그 기억 지워 줘」, 「그건 잊어 줘」) 기억 지우기 승인 카드를 띄운다. 이 도구는 지우지 않는다. "
                + "사용자가 카드에서 승인해야 지워지므로 지웠다고 말하지 않고 승인 카드에서 승인하면 지워진다고 알린다. "
                + "memory_id 는 지시문의 요약 구역이나 색인, memory_search 결과의 번호다. 어느 기억인지 모르면 memory_search 로 찾고, "
                + "여럿이면 사용자에게 되묻는다. 번호를 찾지 못한 기억과 문서, 기억을 고치는 일은 /memory 화면 링크로 안내한다. "
                + "isError 면 카드를 띄우지 못한 것이다. message 의 까닭을 알린다.",
            "inputSchema", schema);
    }
}
