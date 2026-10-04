package com.bifos.assistant.mcp.presentation;

import com.bifos.assistant.agent.domain.AgentToolPolicy;
import com.bifos.assistant.chat.application.ArtifactWriteRequest;
import com.bifos.assistant.mcp.application.McpCallContext;
import com.bifos.assistant.mcp.application.McpCaller;
import com.bifos.assistant.mcp.application.McpCallerResolver;
import com.bifos.assistant.mcp.application.McpPrincipal;
import com.bifos.assistant.mcp.application.McpToolService;
import com.bifos.assistant.mcp.presentation.McpDtos.ArtifactWriteArguments;
import com.bifos.assistant.mcp.presentation.McpDtos.MemoryReadArguments;
import com.bifos.assistant.proactive.application.ProactiveCheckGuard;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@RestController
@RequiredArgsConstructor
public class McpController {
    private static final Pattern UUID_TEXT =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final String INVALID_ARGUMENTS = "인자 형식이 올바르지 않습니다.";
    private static final String MEMORY_READ = "memory_read";
    private static final String ARTIFACT_WRITE = "artifact_write";
    private static final String AGENT_LIST = "agent_list";
    private static final String AGENT_STATUS = "agent_status";
    private static final String AGENT_DELEGATE = "agent_delegate";
    private static final String AGENT_STOP = "agent_stop";
    private static final String WAIT_SECONDS = "wait_seconds";
    /** 먼저 살펴보기 트리에서 받는 도구. 읽기와 위임뿐이다. 새 도구를 더하면 여기 넣을지 함께 정한다(ADR-077). */
    private static final Set<String> CHECK_TREE_TOOLS =
            Set.of(MEMORY_READ, AGENT_LIST, AGENT_DELEGATE, AGENT_STATUS, AGENT_STOP);
    private final McpToolService tools;
    private final McpCallerResolver callers;
    private final ProactiveCheckGuard checkGuard;
    private final BuildProperties buildProperties;
    /** 받아들이는 도구 이름과 그 처리. 이름 검사와 분기가 이 한 곳에서 정해진다. */
    private final Map<String, ToolHandler> handlers = Map.of(
            MEMORY_READ, this::readMemory,
            ARTIFACT_WRITE, this::writeArtifact,
            AGENT_LIST, this::listAgents,
            AGENT_STATUS, this::agentStatus,
            AGENT_DELEGATE, this::agentDelegate,
            AGENT_STOP, this::agentStop);

    /** 요청자가 정해진 뒤 {@code _fos_ctx} 를 뗀 인자로 도구 하나를 처리한다. */
    @FunctionalInterface
    private interface ToolHandler {
        Map<String, Object> handle(McpCaller caller, JsonNode id, JsonNode arguments);
    }

    @PostMapping("/mcp")
    public ResponseEntity<?> handle(
            @RequestHeader(value = HttpHeaders.ORIGIN, required = false) String origin,
            @AuthenticationPrincipal McpPrincipal principal,
            @RequestBody JsonNode request) {
        if (origin != null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        JsonNode id = request.get("id");
        String method = request.path("method").asString();
        if ("notifications/initialized".equals(method)) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).build();
        }
        return ResponseEntity.ok(
                switch (method) {
                    case "initialize" ->
                        response(
                                id,
                                Map.of(
                                        "protocolVersion",
                                        "2025-03-26",
                                        "capabilities",
                                        Map.of("tools", Map.of("listChanged", false)),
                                        "serverInfo",
                                        Map.of(
                                                "name",
                                                AgentToolPolicy.CONTROL_PLANE_MCP,
                                                "version",
                                                buildProperties.getVersion())));
                    case "tools/list" -> response(id, Map.of("tools", tools.tools()));
                    case "tools/call" -> call(principal, id, request.path("params"));
                    default -> error(id, -32601, "Method not found");
                });
    }

    /**
     * 도구 호출을 다섯 단계로 판정한다. 모든 도구가 같은 순서를 지난다.
     *
     * <ol>
     *   <li>{@code params.name} 이 문자열이고 {@code params.arguments} 가 객체인지 본다. 아니면 {@code -32602}
     *   <li>이름으로 처리를 고른다. 모르는 도구면 {@code -32601}
     *   <li>원래 인자의 {@code _fos_ctx} 로 요청자를 정한다(ADR-032). 정하지 못하면 {@link McpToolService#invalidContext()}
     *   <li>요청자의 origin 실행이 먼저 살펴보기 트리이고 {@link #CHECK_TREE_TOOLS} 밖의 도구면
     *       {@link McpToolService#notAllowedInCheck()}
     *   <li>{@code _fos_ctx} 를 뗀 인자로 도구별 검사를 하고 그 요청자로 도구를 돌린다
     * </ol>
     */
    private Map<String, Object> call(McpPrincipal principal, JsonNode id, JsonNode params) {
        JsonNode name = params.get("name");
        JsonNode arguments = params.get("arguments");
        if (name == null || !name.isTextual() || arguments == null || !arguments.isObject()) {
            return invalidParams(id, INVALID_ARGUMENTS);
        }
        String toolName = name.asString();
        ToolHandler handler = handlers.get(toolName);
        if (handler == null) {
            return error(id, -32601, "Method not found");
        }
        JsonNode fosCtx = arguments.get(McpCallContext.FIELD);
        McpCaller caller;
        try {
            caller = callers.resolve(principal, toolName, fosCtx);
        } catch (ApiException ex) {
            if (ex.code() != ErrorCode.MCP_CALL_CONTEXT_INVALID) {
                throw ex;
            }
            return response(id, tools.invalidContext());
        }
        if (!CHECK_TREE_TOOLS.contains(toolName) && checkGuard.isCheckTree(caller.originExecution())) {
            return response(id, tools.notAllowedInCheck());
        }
        return handler.handle(caller, id, withoutCallContext(arguments));
    }

    /**
     * profile 플러그인이 모든 도구 인자에 덮어쓴 {@code _fos_ctx} 를 뗀 복사본을 만든다.
     *
     * <p>도구 규격은 그 키를 모르므로 떼지 않으면 {@code artifact_write} 가 모르는 키로 거절한다. Hermes 는
     * hook 이 더한 키를 도구 규격으로 검증하지 않는다. 받은 노드는 바꾸지 않는다.
     */
    private static JsonNode withoutCallContext(JsonNode arguments) {
        if (!arguments.has(McpCallContext.FIELD)) {
            return arguments;
        }
        ObjectNode copy = ((ObjectNode) arguments).deepCopy();
        copy.remove(McpCallContext.FIELD);
        return copy;
    }

    private Map<String, Object> readMemory(McpCaller caller, JsonNode id, JsonNode arguments) {
        JsonNode memoryId = arguments.get("id");
        if (memoryId == null || !memoryId.isIntegralNumber() || !memoryId.canConvertToLong()) {
            return invalidParams(id, INVALID_ARGUMENTS);
        }
        return response(id, tools.readMemory(caller, new MemoryReadArguments(memoryId.longValue()).id()));
    }

    /** 인자가 없는 도구다. {@code _fos_ctx} 를 뗀 뒤 키가 하나라도 남으면 인자 오류다. */
    private Map<String, Object> listAgents(McpCaller caller, JsonNode id, JsonNode arguments) {
        if (!arguments.isEmpty()) {
            return invalidParams(id, INVALID_ARGUMENTS);
        }
        return response(id, tools.listAgents(caller));
    }

    /**
     * 인자는 정수 {@code execution_id} 하나와 선택 {@code wait_seconds} 다. {@code wait_seconds} 는 0 이상 정수이고, 상한을
     * 넘는 값은 위임 쪽이 줄인다. 다른 키가 오면 인자 오류다.
     */
    private Map<String, Object> agentStatus(McpCaller caller, JsonNode id, JsonNode arguments) {
        JsonNode waitSeconds = arguments.get(WAIT_SECONDS);
        JsonNode executionIdOnly = arguments;
        if (waitSeconds != null) {
            if (!waitSeconds.isIntegralNumber() || !waitSeconds.canConvertToLong() || waitSeconds.longValue() < 0) {
                return invalidParams(id, INVALID_ARGUMENTS);
            }
            ObjectNode copy = ((ObjectNode) arguments).deepCopy();
            copy.remove(WAIT_SECONDS);
            executionIdOnly = copy;
        }
        if (!onlyExecutionId(executionIdOnly)) {
            return invalidParams(id, INVALID_ARGUMENTS);
        }
        Duration wait = waitSeconds == null ? Duration.ZERO : Duration.ofSeconds(waitSeconds.longValue());
        return response(id, tools.agentStatus(caller, arguments.get("execution_id").longValue(), wait));
    }

    /** 인자는 {@code agent_status} 와 같이 정수 {@code execution_id} 하나뿐이다. */
    private Map<String, Object> agentStop(McpCaller caller, JsonNode id, JsonNode arguments) {
        if (!onlyExecutionId(arguments)) {
            return invalidParams(id, INVALID_ARGUMENTS);
        }
        return response(
                id, tools.agentStop(caller, arguments.get("execution_id").longValue()));
    }

    private static boolean onlyExecutionId(JsonNode arguments) {
        JsonNode executionId = arguments.get("execution_id");
        return arguments.size() == 1
                && executionId != null
                && executionId.isIntegralNumber()
                && executionId.canConvertToLong();
    }

    /**
     * 인자는 {@code agent_code} 와 {@code task} 둘뿐이다. 다른 키가 오면 인자 오류다.
     *
     * <p>profile, 사용자, 부모를 인자로 받지 않는다. 모델이 준 값으로 그것을 정하지 않는다(ADR-017).
     */
    private Map<String, Object> agentDelegate(McpCaller caller, JsonNode id, JsonNode arguments) {
        if (arguments.size() != 2 || !text(arguments, "agent_code") || !text(arguments, "task")) {
            return invalidParams(id, INVALID_ARGUMENTS);
        }
        String task = arguments.get("task").asString();
        if (task.isBlank() || task.length() > McpToolService.TASK_MAX_CHARS) {
            return invalidParams(id, INVALID_ARGUMENTS);
        }
        return response(id, tools.delegate(caller, arguments.get("agent_code").asString(), task));
    }

    private Map<String, Object> writeArtifact(McpCaller caller, JsonNode id, JsonNode arguments) {
        if (!onlyArtifactFields(arguments)
                || !text(arguments, "conversation_id")
                || !text(arguments, "path")
                || !exactlyOneText(arguments, "content", "source_url")) {
            return invalidParams(id, INVALID_ARGUMENTS);
        }
        String conversationId = arguments.get("conversation_id").asString();
        if (!UUID_TEXT.matcher(conversationId).matches()) {
            return invalidParams(id, INVALID_ARGUMENTS);
        }
        UUID parsed;
        try {
            parsed = UUID.fromString(conversationId);
        } catch (IllegalArgumentException ex) {
            return invalidParams(id, INVALID_ARGUMENTS);
        }
        ArtifactWriteArguments value = ArtifactWriteArguments.from(arguments);
        try {
            return response(
                    id,
                    tools.writeArtifact(
                            caller,
                            new ArtifactWriteRequest(parsed, value.path(), value.content(), value.sourceUrl())));
        } catch (ApiException ex) {
            return invalidParams(id, artifactValidationReason(ex));
        }
    }

    private static boolean onlyArtifactFields(JsonNode arguments) {
        Set<String> allowed = Set.of("conversation_id", "path", "content", "source_url");
        return allowed.containsAll(arguments.propertyNames());
    }

    private static boolean text(JsonNode arguments, String name) {
        JsonNode value = arguments.get(name);
        return value != null && value.isTextual();
    }

    private static boolean exactlyOneText(JsonNode arguments, String first, String second) {
        boolean hasFirst = arguments.has(first);
        boolean hasSecond = arguments.has(second);
        return hasFirst != hasSecond
                && text(arguments, hasFirst ? first : second)
                && (!hasSecond || !arguments.get(second).asString().isBlank());
    }

    /** 내부 예외 문구를 그대로 내보내지 않고, 정해 둔 오류 이유만 반환한다. */
    private static String artifactValidationReason(ApiException exception) {
        String message = exception.getMessage();
        if (message == null) {
            return INVALID_ARGUMENTS;
        }
        return switch (message) {
            case "inline artifact content must be HTML or CSS", "URL artifact path must be an image" ->
                "허용하지 않은 확장자입니다.";
            case "artifact path is invalid", "artifact path is required" -> "경로 형식이 올바르지 않습니다.";
            case "artifact content must not exceed 5 MiB" -> "파일 크기가 5MB를 초과했습니다.";
            case "artifact source URL is invalid" -> "주소 형식이 올바르지 않습니다.";
            default -> INVALID_ARGUMENTS;
        };
    }

    private static Map<String, Object> invalidParams(JsonNode id, String reason) {
        Map<String, Object> body = base(id);
        body.put("error", Map.of("code", -32602, "message", "Invalid params", "data", reason));
        return body;
    }

    private static Map<String, Object> response(JsonNode id, Map<String, Object> result) {
        Map<String, Object> body = base(id);
        body.put("result", result);
        return body;
    }

    private static Map<String, Object> error(JsonNode id, int code, String message) {
        Map<String, Object> body = base(id);
        body.put("error", Map.of("code", code, "message", message));
        return body;
    }

    private static Map<String, Object> base(JsonNode id) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", id);
        return body;
    }
}
