package com.bifos.assistant.mcp.application;

import com.bifos.assistant.mcp.application.dto.McpCaller;
import com.bifos.assistant.mcp.application.port.ConnectorListing;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * 사용자가 외부 서비스 연결을 부탁했을 때 플러그인 목록과 연결 화면 링크를 주는 읽기 도구다(스펙 service-concepts REQ5).
 *
 * <p>목록을 읽다 실패해도 turn 을 막지 않는다. 실패 결과로 「읽지 못했다」 와 안내할 주소를 돌려준다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpConnectorListTool {
    public static final String NAME = "connector_list";
    static final String FAILED = "플러그인 목록을 읽지 못했다. 연결했다고 말하지 않고 /connections 링크로 외부 서비스 연결 화면을 안내한다.";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ConnectorListing listing;


    public Map<String, Object> list(McpCaller caller) {
        try {
            return McpToolService.result(JSON.writeValueAsString(listing.list(caller.user(), caller.originExecution().agentId())), false);
        } catch (RuntimeException ex) {
            log.warn("connector list failed executionId={} reason={}", caller.executionId(), ex.getClass().getSimpleName());
            return McpToolService.result(FAILED, true);
        }
    }


    public Map<String, Object> definition() {
        Map<String, Object> schema = new LinkedHashMap<>(
            Map.of("type", "object", "additionalProperties", false, "properties", Map.of(), "required", List.of()));
        return Map.of("name", NAME, "description",
            "사용자가 외부 서비스 연결을 부탁했을 때(「구글 캘린더 연결해 줘」) 부른다. 플러그인마다 id, title, connected(내 계정이 연결됐는가), "
                + "thisAgent(이 에이전트에 붙은 상태: none 안 붙음, ready 지금 쓸 수 있음, pending 반영을 기다림), link(연결 화면 주소)를 준다. "
                + "답에는 그 서비스의 link 를 그대로 마크다운 링크로 붙이고 그 화면에서 계정을 연결하라고 안내한다. 에이전트는 연결하지 못하므로 연결했다고 말하지 않는다. "
                + "목록에 없는 서비스면 없다고 알리고 /connections 링크를 붙인다.",
            "inputSchema", schema);
    }
}
