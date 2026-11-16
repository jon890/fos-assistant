package com.bifos.assistant.mcp.application;

import com.bifos.assistant.mcp.application.dto.McpCaller;
import com.bifos.assistant.mcp.application.port.ApprovalResumes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 에이전트가 사람 화면에서 멈춘 승인한 작업을 같은 승인으로 이어 가는 MCP 도구다(docs/features/browser-cowork/spec.md 의 REQ8, REQ9).
 *
 * <p>인자가 없다. 이어 갈 승인은 호출한 실행의 대화에서만 고른다. 결과는 늘 오류가 아닌 글이다. Hermes 는 같은 서버의 오류 결과가 연속
 * 3회면 그 서버를 잠시 막으므로, 이어 갈 승인이 없다는 답이 이 서버의 다른 도구를 막지 않게 한다.
 */
@Service
@RequiredArgsConstructor
public class McpApprovalResumeTool {
    public static final String NAME = "approval_resume";
    private final ApprovalResumes resumes;


    public Map<String, Object> resume(McpCaller caller) {
        return McpToolService.result(resumes.resume(caller.user(), caller.originExecution().conversationId()), false);
    }


    public Map<String, Object> definition() {
        var schema = new LinkedHashMap<String, Object>(Map.of("type", "object", "additionalProperties", false, "properties", Map.of(),
            "required", List.of()));
        return Map.of("name", NAME, "description",
            "승인한 주문서 채우기처럼 사용자가 승인한 작업이 로그인 같은 사람이 해야 하는 화면에서 멈췄을 때, 새 승인 없이 같은 승인으로 멈춘 단계부터 "
                + "한 번 이어 간다. 그 멈춤을 알린 뒤 사용자가 이 대화에 메시지를 보냈을 때만 부른다. 사용자가 그만두라고 하면 부르지 않는다. "
                + "결과는 승인한 작업의 결과이고, 이어 갈 승인이 없으면 그렇다고 알려 준다.",
            "inputSchema", schema);
    }
}
