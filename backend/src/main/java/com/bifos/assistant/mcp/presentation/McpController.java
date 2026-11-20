package com.bifos.assistant.mcp.presentation;

import static com.bifos.assistant.mcp.presentation.McpRpc.INVALID_ARGUMENTS;
import static com.bifos.assistant.mcp.presentation.McpRpc.error;
import static com.bifos.assistant.mcp.presentation.McpRpc.invalidParams;
import static com.bifos.assistant.mcp.presentation.McpRpc.response;
import static com.bifos.assistant.mcp.presentation.McpRpc.text;

import com.bifos.assistant.agent.domain.AgentToolPolicy;
import com.bifos.assistant.mcp.application.dto.McpCallContext;
import com.bifos.assistant.mcp.application.dto.McpCaller;
import com.bifos.assistant.mcp.application.McpConnectorListTool;
import com.bifos.assistant.mcp.application.McpContentDraftSnapshots;
import com.bifos.assistant.mcp.application.McpContentDraftTools;
import com.bifos.assistant.mcp.application.McpFileSpaceTools;
import com.bifos.assistant.mcp.application.McpApprovalResumeTool;
import com.bifos.assistant.mcp.application.McpFinancialLimitTools;
import com.bifos.assistant.mcp.application.McpCallerResolver;
import com.bifos.assistant.mcp.application.McpMediaObservationTools;
import com.bifos.assistant.mcp.application.dto.McpPrincipal;
import com.bifos.assistant.mcp.application.McpToolService;
import com.bifos.assistant.mcp.presentation.McpDtos.FollowUpProposeArguments;
import com.bifos.assistant.mcp.presentation.McpDtos.MediaObservationListArguments;
import com.bifos.assistant.mcp.presentation.McpDtos.MediaObservationRecordArguments;
import com.bifos.assistant.mcp.presentation.McpDtos.MemoryReadArguments;
import com.bifos.assistant.mcp.presentation.McpDtos.MemoryRememberArguments;
import com.bifos.assistant.mcp.presentation.McpDtos.MemorySearchArguments;
import com.bifos.assistant.proactive.check.application.ProactiveCheckGuard;
import com.bifos.assistant.proactive.check.domain.ProactiveCheck;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@RestController
@RequiredArgsConstructor
public class McpController {
    private static final String SEARCH_RESTART = "검색 순위가 앞 페이지와 달라졌습니다. cursor 없이 다시 검색하세요.";
    private static final String MEMORY_READ = "memory_read";
    private static final String MEMORY_SEARCH = "memory_search";
    private static final String ARTIFACT_WRITE = "artifact_write";
    private static final String FILE_SPACE_PUT = McpFileSpaceTools.PUT;
    private static final String AGENT_LIST = "agent_list";
    private static final String AGENT_STATUS = "agent_status";
    private static final String AGENT_DELEGATE = "agent_delegate";
    private static final String AGENT_STOP = "agent_stop";
    private static final String FOLLOW_UP_PROPOSE = "follow_up_propose";
    private static final Set<String> FOLLOW_UP_PROPOSE_FIELDS = Set.of("title", "due_at", "waiting");
    private static final String MEMORY_REMEMBER = "memory_remember";
    private static final String WAIT_SECONDS = "wait_seconds";
    /** 먼저 살펴보기에서 읽기, 위임과 사람이 받아들여야 하는 할 일 제안을 받는다. */
    private static final Set<String> CHECK_TREE_TOOLS = Set.of(
        MEMORY_READ,
        MEMORY_SEARCH,
        AGENT_LIST,
        AGENT_DELEGATE,
        AGENT_STATUS,
        AGENT_STOP,
        FOLLOW_UP_PROPOSE,
        McpConnectorListTool.NAME,
        McpMediaObservationTools.LIST);
    /** 쓰기 도구를 허용한 살펴보기가 더 받는 도구. 그 살펴보기의 점검 대화에만 쓰고 결과물은 그 폴더에 남는다(ADR-082). */
    private static final Set<String> CHECK_TREE_WRITE_TOOLS = Set.of(ARTIFACT_WRITE, FILE_SPACE_PUT);

    private final McpToolService tools;
    private final McpArtifactCalls artifactCalls;
    private final McpFileSpaceCalls fileSpaceCalls;
    private final McpCallerResolver callers;
    private final McpMediaObservationTools mediaObservations;
    private final McpContentDraftTools contentDrafts;
    private final McpContentDraftSnapshots contentSnapshots;
    private final McpFinancialLimitTools financialLimits;
    private final McpApprovalResumeTool approvalResume;
    private final McpConnectorListTool connectorList;
    private final ProactiveCheckGuard checkGuard;
    private final BuildProperties buildProperties;
    private final Map<String, ToolHandler> handlers = handlers();


    /** 받아들이는 도구 이름과 그 처리. 이름 검사와 분기가 이 한 곳에서 정해진다. */
    private Map<String, ToolHandler> handlers() {
        return Map.ofEntries(
            Map.entry(MEMORY_READ, this::readMemory),
            Map.entry(MEMORY_SEARCH, this::searchMemory),
            Map.entry(ARTIFACT_WRITE, (caller, id, args) -> artifactCalls.writeArtifact(caller, id, args)),
            Map.entry(FILE_SPACE_PUT, (caller, id, args) -> fileSpaceCalls.put(caller, id, args)),
            Map.entry(AGENT_LIST, this::listAgents),
            Map.entry(McpConnectorListTool.NAME, this::listConnectors),
            Map.entry(AGENT_STATUS, this::agentStatus),
            Map.entry(AGENT_DELEGATE, this::agentDelegate),
            Map.entry(AGENT_STOP, this::agentStop),
            Map.entry(FOLLOW_UP_PROPOSE, this::proposeFollowUp),
            Map.entry(MEMORY_REMEMBER, this::remember),
            Map.entry(McpMediaObservationTools.LIST, (caller, id, args) -> validated(id, args,
                MediaObservationListArguments::from, value -> this.mediaObservations.list(caller, value.afterAssetId(), value.limit()))),
            Map.entry(McpMediaObservationTools.RECORD, (caller, id, args) -> validated(id, args,
                MediaObservationRecordArguments::from, value -> this.mediaObservations.record(caller, value.assetId(), value.expectedRevision(), value.requestId(), value.observation()))),
            Map.entry(McpContentDraftTools.WRITE, (caller, id, args) -> validated(id, args,
                McpDtos.ContentDraftWriteArguments::from, value -> this.contentDrafts.write(caller, value.requestId(), value.draftId(), value.expectedRevision(), value.draft()))),
            Map.entry(McpContentDraftTools.READ, (caller, id, args) -> validated(id, args,
                McpDtos.ContentDraftReadArguments::from, value -> this.contentDrafts.read(caller, value.draftId(), value.revision()))),
            Map.entry(McpContentDraftSnapshots.PREVIEW, (caller, id, args) -> validated(id, args, McpDtos.ContentDraftPreviewArguments::from,
                value -> this.contentSnapshots.preview(caller, value.draftId(), value.revision(), value.bindingId()))),
            Map.entry(McpContentDraftSnapshots.SAVE, (caller, id, args) -> validated(id, args,
                McpDtos.ContentDraftSaveArguments::from, value -> this.contentSnapshots.save(caller, value.snapshotId()))),
            Map.entry(McpFinancialLimitTools.READ, (caller, id, args) -> validated(id, args,
                McpDtos.OrderLimitReadArguments::from, value -> this.financialLimits.read(caller))),
            Map.entry(McpFinancialLimitTools.CHANGE, (caller, id, args) -> validated(id, args,
                McpDtos.OrderLimitChangeArguments::from, value -> this.financialLimits.change(caller, value.name(), value.value()))),
            Map.entry(McpApprovalResumeTool.NAME, (caller, id, args) -> validated(id, args,
                McpDtos.ApprovalResumeArguments::from, value -> this.approvalResume.resume(caller))));
    }


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
     * 이름과 인자 모양을 확인한 뒤 서명으로 origin을 정하고 살펴보기 권한을 검사한다.
     * {@code _fos_ctx}는 도구 인자에서 떼며, 도구마다 같은 판정 순서를 유지한다(ADR-032).
     * 살펴보기의 추가 쓰기는 {@link #CHECK_TREE_WRITE_TOOLS}와 해당 점검 대화에만 허용한다.
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
        if (!CHECK_TREE_TOOLS.contains(toolName)) {
            // 살펴보기 줄을 한 번만 읽어 트리인지와 쓰기 허용을 함께 본다.
            Optional<ProactiveCheck> check = checkGuard.checkOf(caller.originExecution());
            if (check.isPresent() && !writeAllowedInCheck(toolName, check.get(), caller, arguments)) {
                return response(id, tools.notAllowedInCheck());
            }
        }
        return handler.handle(caller, id, McpCallContext.strip(arguments));
    }


    /**
     * 쓰기 도구를 허용한 살펴보기가 이 쓰기 도구를 받는가(ADR-082). 결과물은 그 살펴보기의 점검 대화에만 쓴다. 요청한 대화가 그 대화가
     * 아니거나 대화 식별자를 읽지 못하면 받지 않는다. 같은 사용자의 다른 대화에 웹 결과에서 온 글을 쓰지 않게 하기 위해서다.
     * {@code file_space_put} 은 대화 인자가 없어 origin 실행의 대화로 본다.
     */
    private boolean writeAllowedInCheck(String toolName, ProactiveCheck check, McpCaller caller, JsonNode arguments) {
        if (!CHECK_TREE_WRITE_TOOLS.contains(toolName) || !check.writesAllowed()) {
            return false;
        }
        if (FILE_SPACE_PUT.equals(toolName)) {
            // 이 도구에는 대화 인자가 없다. origin 실행의 대화가 점검 대화인지로 본다.
            return check.conversationId() != null
                && check.conversationId().equals(caller.originExecution().conversationId());
        }
        JsonNode conversationId = arguments.get("conversation_id");
        if (conversationId == null
            || !conversationId.isTextual()
            || !McpArtifactCalls.UUID_TEXT.matcher(conversationId.asString()).matches()) {
            return false;
        }
        return checkGuard.isCheckConversation(check, UUID.fromString(conversationId.asString()));
    }


    /** 역직렬화가 끝나기 전 실패도 원문과 cause를 공통 오류 로그로 보내지 않는다. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> malformed() {
        return ResponseEntity.ok(error(null, -32700, "Parse error"));
    }


    private <T> Map<String, Object> validated(JsonNode id, JsonNode arguments,
        Function<JsonNode, T> parse, Function<T, Map<String, Object>> action) {
        try {
            return response(id, action.apply(parse.apply(arguments)));
        } catch (ApiException ex) {
            return invalidParams(id, INVALID_ARGUMENTS);
        }
    }


    private Map<String, Object> readMemory(McpCaller caller, JsonNode id, JsonNode arguments) {
        JsonNode memoryId = arguments.get("id");
        if (memoryId == null || !memoryId.isIntegralNumber() || !memoryId.canConvertToLong()) {
            return invalidParams(id, INVALID_ARGUMENTS);
        }
        return response(id, tools.readMemory(caller, new MemoryReadArguments(memoryId.longValue()).id()));
    }


    /** 검색 범위는 origin 실행에서만 정한다. null과 모르는 키는 인자 오류다. */
    private Map<String, Object> searchMemory(McpCaller caller, JsonNode id, JsonNode arguments) {
        JsonNode limit = arguments.get("limit");
        JsonNode cursor = arguments.get("cursor");
        if (!Set.of("query", "limit", "cursor").containsAll(arguments.propertyNames())
            || !text(arguments, "query")
            || (limit != null
                && (!limit.isIntegralNumber()
                    || !limit.canConvertToInt()
                    || limit.intValue() < 1
                    || limit.intValue() > 50))
            || (cursor != null && !cursor.isString())) {
            return invalidParams(id, INVALID_ARGUMENTS);
        }
        Optional<MemorySearchArguments> parsed = MemorySearchArguments.from(arguments);
        if (parsed.isEmpty()) {
            return invalidParams(id, INVALID_ARGUMENTS);
        }
        MemorySearchArguments value = parsed.get();
        try {
            return response(id, tools.searchMemory(caller, value.query(), value.limit(), value.cursor()));
        } catch (ApiException ex) {
            if (ex.code() != ErrorCode.VALIDATION_FAILED) {
                throw ex;
            }
            return invalidParams(id, SEARCH_RESTART);
        }
    }


    /** 인자가 없는 도구다. {@code _fos_ctx} 를 뗀 뒤 키가 하나라도 남으면 인자 오류다. */
    private Map<String, Object> listAgents(McpCaller caller, JsonNode id, JsonNode arguments) {
        if (!arguments.isEmpty()) {
            return invalidParams(id, INVALID_ARGUMENTS);
        }
        return response(id, tools.listAgents(caller));
    }


    /** {@code agent_list} 와 같이 인자가 없는 도구다. 키가 하나라도 남으면 인자 오류다. */
    private Map<String, Object> listConnectors(McpCaller caller, JsonNode id, JsonNode arguments) {
        if (!arguments.isEmpty()) {
            return invalidParams(id, INVALID_ARGUMENTS);
        }
        return response(id, connectorList.list(caller));
    }


    /**
     * 인자는 정수 {@code execution_id} 와 0 이상 정수인 선택 {@code wait_seconds} 다. 상한은 위임 쪽이 줄이고 다른 키는 인자 오류다.
     */
    private Map<String, Object> agentStatus(McpCaller caller, JsonNode id, JsonNode arguments) {
        JsonNode waitSeconds = arguments.get(WAIT_SECONDS);
        JsonNode executionIdOnly = arguments;
        if (waitSeconds != null) {
            if (!waitSeconds.isIntegralNumber() || !waitSeconds.canConvertToLong() || waitSeconds.longValue() < 0) {
                return invalidParams(id, INVALID_ARGUMENTS);
            }
            ObjectNode copy = ((ObjectNode)arguments).deepCopy();
            copy.remove(WAIT_SECONDS);
            executionIdOnly = copy;
        }
        if (!onlyExecutionId(executionIdOnly)) {
            return invalidParams(id, INVALID_ARGUMENTS);
        }
        Duration wait = waitSeconds == null ? Duration.ZERO : Duration.ofSeconds(waitSeconds.longValue());
        return response(
            id, tools.agentStatus(caller, arguments.get("execution_id").longValue(), wait));
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
     * profile, 사용자, 부모를 인자로 받지 않는다. 모델이 준 값으로 그것을 정하지 않는다(ADR-017).
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


    /**
     * 인자는 {@code title}, {@code due_at}, {@code waiting} 셋만 받는다. 다른 키가 오면 인자 오류다.
     *
     * <p>사용자와 대화를 인자로 받지 않는다(ADR-032). 모델은 쓰지 않는 선택 인자에 {@code null} 을 자주 보내므로 선택 인자의
     * {@code null} 은 없는 것으로 본다. 필수인 {@code title} 의 {@code null} 은 빠진 것이다.
     */
    private Map<String, Object> proposeFollowUp(McpCaller caller, JsonNode id, JsonNode arguments) {
        if (!FOLLOW_UP_PROPOSE_FIELDS.containsAll(arguments.propertyNames())
            || !text(arguments, "title")
            || !nullOr(arguments, "due_at", JsonNode::isString)
            || !nullOr(arguments, "waiting", JsonNode::isBoolean)) {
            return invalidParams(id, INVALID_ARGUMENTS);
        }
        FollowUpProposeArguments value = FollowUpProposeArguments.from(arguments);
        return response(
            id, tools.proposeFollowUp(caller, value.title(), value.dueAt(), Boolean.TRUE.equals(value.waiting())));
    }


    /**
     * 인자는 {@link MemoryRememberArguments#FIELDS} 만 받고 다른 키가 오면 인자 오류다(ADR-20261007 / memory-remember).
     *
     * <p>사용자와 범위를 인자로 받지 않는다. 바로 저장할지를 정하는 인자도 없다. 그 판정은 실행의 출처로 한다. 선택 인자의
     * {@code null} 은 없는 것으로 본다.
     */
    private Map<String, Object> remember(McpCaller caller, JsonNode id, JsonNode arguments) {
        if (!MemoryRememberArguments.FIELDS.containsAll(arguments.propertyNames())
            || !text(arguments, "title")
            || !text(arguments, "content")
            || !nullOr(arguments, "evidence", JsonNode::isString)
            || !nullOr(arguments, "collection", JsonNode::isString)
            || !nullOr(arguments, "sensitive", JsonNode::isBoolean)
            || !nullOr(arguments, "archivable", JsonNode::isBoolean)
            || !nullOr(arguments, "memory_id", node -> node.isIntegralNumber() && node.canConvertToLong())) {
            return invalidParams(id, INVALID_ARGUMENTS);
        }
        MemoryRememberArguments value = MemoryRememberArguments.from(arguments);
        return response(
            id,
            tools.remember(
                caller,
                value.title(),
                value.content(),
                value.evidence(),
                value.memoryId(),
                value.collection(),
                Boolean.TRUE.equals(value.sensitive()),
                Boolean.TRUE.equals(value.archivable())));
    }


    /** 키가 없거나 {@code null} 이거나, 그 값이 {@code type} 을 만족한다. */
    private static boolean nullOr(JsonNode arguments, String name, Predicate<JsonNode> type) {
        JsonNode value = arguments.get(name);
        return value == null || value.isNull() || type.test(value);
    }
}
