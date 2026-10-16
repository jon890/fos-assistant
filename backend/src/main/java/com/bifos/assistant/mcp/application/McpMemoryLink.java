package com.bifos.assistant.mcp.application;

import static com.bifos.assistant.mcp.application.McpToolService.result;

import com.bifos.assistant.memory.application.MemoryLinkService;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.application.model.MemoryAccess;
import com.bifos.assistant.memory.domain.entity.MemoryLink;
import com.bifos.assistant.memory.domain.type.MemoryLinkOrigin;
import com.bifos.assistant.memory.domain.type.MemoryLinkStatus;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.usage.domain.AgentExecution;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Control Plane MCP 도구 {@code memory_link} 의 정의와 처리다(ADR-20261015 / memory-link). 계약은 {@code docs/features/memory.md} 의
 * 「기억 사이의 연결」 이 갖는다.
 *
 * <p>대체는 늘 제안으로 남고 사용자가 받아들여야 바뀐다. 모델은 옛 기억을 고치거나 지우지 못한다. 사용자와 범위를 인자로 받지 않고 origin
 * 실행의 사용자와 에이전트의 접근 범위로 두 항목을 본다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class McpMemoryLink {

    public static final String TOOL = "memory_link";
    public static final String SUPERSEDES = "SUPERSEDES";
    public static final String RELATED = "RELATED";

    /** 한 실행이 맺는 연결 수의 상한이다. 모델이 기억 사이를 한꺼번에 엮어 승인 카드를 채우지 않게 한다. */
    static final int PER_EXECUTION_MAX = 10;

    private static final String UNLINKABLE = "연결할 수 없는 기억입니다.";

    private final MemoryService memories;
    private final MemoryLinkService links;


    /** {@code tools/list} 에 싣는 도구 정의다. */
    Map<String, Object> definition() {
        return Map.of(
            "name",
            TOOL,
            "description",
            "기억 두 개를 잇는다. 번호는 지시문이나 memory_search 결과에 있는 것만 쓴다. "
                + "SUPERSEDES 는 from_id 의 새 기억이 to_id 의 옛 기억을 대신한다는 제안이고 사용자가 받아들여야 바뀐다. "
                + "RELATED 는 관련 있는 두 기억을 바로 잇는다. 연결의 뜻은 label 에 적는다.",
            "inputSchema",
            Map.of(
                "type",
                "object",
                "additionalProperties",
                false,
                "properties",
                Map.of(
                    "from_id",
                    Map.of("type", "integer"),
                    "to_id",
                    Map.of("type", "integer"),
                    "type",
                    Map.of("type", "string", "enum", List.of(SUPERSEDES, RELATED)),
                    "label",
                    Map.of("type", "string", "maxLength", MemoryLinkService.LABEL_MAX)),
                "required",
                List.of("from_id", "to_id", "type")));
    }


    /**
     * 두 항목을 잇고 그 결과를 한 줄 글로 돌려준다. 볼 수 없는 항목과 규칙을 어긴 쌍은 까닭을 가리지 않고 같은 글이다. 다르게 답하면 볼 수 없는 항목이
     * 있다는 것이 새어 나간다.
     *
     * @param type {@link #SUPERSEDES} 나 {@link #RELATED}. 인자 검사가 이미 거른 값이다
     * @param label {@code RELATED} 의 뜻. 없으면 null
     */
    Map<String, Object> link(McpCaller caller, long fromId, long toId, String type, String label) {
        AgentExecution origin = caller.originExecution();
        MemoryAccess access = memories.accessOf(origin.agentId());
        if (access.isEmpty() || !links.visibleToAgent(caller.user(), access, fromId)
            || !links.visibleToAgent(caller.user(), access, toId)) {
            return result(UNLINKABLE, true);
        }
        if (links.countByExecution(origin.id()) >= PER_EXECUTION_MAX) {
            return result("이번 실행에서 이미 연결을 " + PER_EXECUTION_MAX + "개 맺었습니다. 더 맺지 않습니다.", true);
        }
        MemoryLink linked;
        try {
            linked = SUPERSEDES.equals(type)
                ? links.proposeSupersede(caller.user(), fromId, toId, MemoryLinkOrigin.AGENT, origin.id())
                : links.relate(caller.user(), fromId, toId, label, MemoryLinkOrigin.AGENT, null, origin.id());
        } catch (ApiException ex) {
            return result(UNLINKABLE, true);
        }
        log.info(
            "memory link userId={} executionId={} linkId={} type={} status={}",
            caller.user().id(),
            origin.id(),
            linked.id(),
            linked.type(),
            linked.status());
        if (!SUPERSEDES.equals(type)) {
            return result("연결했습니다.", false);
        }
        return linked.status() == MemoryLinkStatus.ACTIVE
            ? result("이미 사용자가 받아들인 대체입니다.", false)
            : result("대체 제안으로 남겼습니다. 사용자가 받아들여야 바뀝니다. 답에서 아직 승인 전이라는 것을 알립니다.", false);
    }
}
