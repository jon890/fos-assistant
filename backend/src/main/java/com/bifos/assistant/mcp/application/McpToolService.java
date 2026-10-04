package com.bifos.assistant.mcp.application;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.ArtifactWriteRequest;
import com.bifos.assistant.chat.application.ArtifactWriteResult;
import com.bifos.assistant.chat.application.ArtifactWriteService;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.orchestration.application.AgentDelegationService;
import com.bifos.assistant.orchestration.application.DelegationResult;
import com.bifos.assistant.orchestration.application.DelegationResult.Failure;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.util.ExternalData;
import com.bifos.assistant.usage.application.ExecutionDeliveryWriter;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.DelegationKey;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
@Slf4j
@RequiredArgsConstructor
public class McpToolService {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String INVALID_CONTEXT = "호출 맥락을 확인할 수 없습니다. 새 대화에서 다시 시도해 주세요.";
    private static final Set<String> SAFE_ARTIFACT_FAILURE_MESSAGES = Set.of(
            "this conversation does not exist",
            "could not store artifact",
            "artifact source download is busy",
            "artifact source download interrupted",
            "artifact source download timed out",
            "could not download artifact source",
            "artifact source host has no addresses",
            "artifact source host resolves to a non-public address",
            "could not connect to artifact source",
            "artifact source did not return success",
            "artifact source content type is invalid",
            "artifact source length is invalid",
            "artifact source length is truncated",
            "artifact source is too large",
            "artifact source URL is invalid",
            "artifact source host is not allowed");
    private static final String EXECUTION_NOT_FOUND = "실행을 찾을 수 없습니다.";
    private static final String NOT_ALLOWED_IN_CHECK = "먼저 살펴보기에서는 쓸 수 없는 도구입니다.";
    /** {@code agent_delegate} 의 {@code task} 길이 상한. 대화 메시지 상한과 같다. */
    public static final int TASK_MAX_CHARS = 8000;

    private final MemoryService memories;
    private final ArtifactWriteService artifacts;
    private final AgentDelegationService delegations;
    private final ExecutionDeliveryWriter deliveryWriter;
    private final AgentRepository agents;
    private final Clock clock;

    public List<Map<String, Object>> tools() {
        return List.of(
                Map.of(
                        "name",
                        "memory_read",
                        "description",
                        "지금 묻는 사람의 Memory 항목 본문을 번호로 읽는다. 번호는 지시문의 색인에 있다.",
                        "inputSchema",
                        Map.of(
                                "type",
                                "object",
                                "properties",
                                Map.of("id", Map.of("type", "integer")),
                                "required",
                                List.of("id"))),
                Map.of(
                        "name",
                        "artifact_write",
                        "description",
                        "대화의 결과물에 쓸 파일을 저장한다. conversation_id에는 대화 UUID, path에는 폴더 안 상대 경로를 준다. content로 html 또는 css 본문을 쓰거나 source_url로 png, jpg, jpeg, gif, webp 이미지를 가져온다. 두 방식은 하나만 쓰며 파일 하나는 5MB를 넘을 수 없다. 같은 path는 새 내용으로 바뀐다.",
                        "inputSchema",
                        Map.of(
                                "type", "object",
                                "additionalProperties", false,
                                "properties",
                                        Map.of(
                                                "conversation_id", Map.of("type", "string"),
                                                "path", Map.of("type", "string"),
                                                "content", Map.of("type", "string"),
                                                "source_url", Map.of("type", "string")),
                                "required", List.of("conversation_id", "path"),
                                "oneOf",
                                        List.of(
                                                Map.of(
                                                        "required",
                                                        List.of("content"),
                                                        "not",
                                                        Map.of("required", List.of("source_url"))),
                                                Map.of(
                                                        "required",
                                                        List.of("source_url"),
                                                        "not",
                                                        Map.of("required", List.of("content")))))),
                Map.of(
                        "name",
                        "agent_list",
                        "description",
                        "지금 묻는 사람이 일을 맡길 수 있는 에이전트의 code 와 이름을 읽는다.",
                        "inputSchema",
                        Map.of("type", "object", "additionalProperties", false, "properties", Map.of())),
                Map.of(
                        "name",
                        "agent_delegate",
                        "description",
                        "다른 에이전트에게 일을 맡기고 실행 번호를 바로 돌려받는다. 끝날 때까지 기다리지 않는다. agent_code 에는 agent_list 로 받은 code 를, task 에는 그 에이전트에게 줄 지시를 넣는다. 맡긴 뒤 남은 일을 계속하고, 할 일이 끝나면 맡긴 일을 알리고 답을 마친다. 맡긴 일이 끝나면 그 결과가 이 대화의 다음 차례에 자동으로 전달된다.",
                        "inputSchema",
                        Map.of(
                                "type",
                                "object",
                                "additionalProperties",
                                false,
                                "properties",
                                Map.of(
                                        "agent_code", Map.of("type", "string"),
                                        "task", Map.of("type", "string", "minLength", 1, "maxLength", TASK_MAX_CHARS)),
                                "required",
                                List.of("agent_code", "task"))),
                Map.of(
                        "name",
                        "agent_status",
                        "description",
                        "다른 에이전트에게 맡긴 실행의 지금 상태와 결과를 읽는다. execution_id 에는 agent_delegate 로 받은 번호를 넣는다. 이번 답에 그 결과가 필요하면 wait_seconds(최대 20)로 끝나기를 기다린다. 필요하지 않으면 끝난 결과는 다음 turn 에 자동으로 전달되므로 반복해서 부르지 않는다.",
                        "inputSchema",
                        Map.of(
                                "type",
                                "object",
                                "additionalProperties",
                                false,
                                "properties",
                                Map.of(
                                        "execution_id", Map.of("type", "integer"),
                                        "wait_seconds", Map.of("type", "integer", "minimum", 0)),
                                "required",
                                List.of("execution_id"))),
                Map.of(
                        "name",
                        "agent_stop",
                        "description",
                        "다른 에이전트에게 맡긴 실행 하나를 멈추고 그 뒤의 상태를 돌려준다. execution_id 에는 agent_delegate 로 받은 번호를 넣는다. 그 실행이 다시 맡긴 실행은 멈추지 않는다. 이미 끝난 실행은 끝난 상태를 그대로 돌려준다. 중지를 요청했는데 아직 RUNNING 이면 stop_requested 가 true 이고, 결과는 agent_status 로 다시 읽는다. stop_requested 가 없는 RUNNING 은 멈추지 못한 것이다.",
                        "inputSchema",
                        Map.of(
                                "type",
                                "object",
                                "additionalProperties",
                                false,
                                "properties",
                                Map.of("execution_id", Map.of("type", "integer")),
                                "required",
                                List.of("execution_id"))));
    }

    /**
     * 요청자를 정하지 못한 호출의 도구 결과다.
     *
     * <p>서명 오류, profile 불일치, 부모 없음, 부모 둘 이상, 사용자 없음을 모두 이 결과 하나로 답한다(ADR-032).
     */
    public Map<String, Object> invalidContext() {
        return result(INVALID_CONTEXT, true);
    }

    /** 먼저 살펴보기 트리에서 받지 않는 도구를 부른 호출의 도구 결과다. 도구는 돌리지 않는다(ADR-080). */
    public Map<String, Object> notAllowedInCheck() {
        return result(NOT_ALLOWED_IN_CHECK, true);
    }

    public Map<String, Object> readMemory(McpCaller caller, Long id) {
        CurrentUser user = caller.user();
        try {
            Memory memory = memories.bodyFor(
                    user, memories.accessOf(caller.originExecution().agentId()), id);
            log.info("memory read userId={} memoryId={} executionId={}", user.id(), id, caller.executionId());
            return result(memories.contentOf(memory), false);
        } catch (ApiException ex) {
            if (ex.code() == ErrorCode.MEMORY_NOT_FOUND) {
                return result("Memory 항목을 읽을 수 없습니다.", true);
            }
            throw ex;
        }
    }

    public Map<String, Object> writeArtifact(McpCaller caller, ArtifactWriteRequest request) {
        CurrentUser user = caller.user();
        try {
            ArtifactWriteResult written = artifacts.write(user, request);
            return result(toJson(written), false);
        } catch (ApiException ex) {
            if (ex.code() == ErrorCode.VALIDATION_FAILED) {
                throw ex;
            }
            log.warn(
                    "artifact write failed userId={} executionId={} exceptionClass={} errorCode={} reason={}",
                    user.id(),
                    caller.executionId(),
                    ex.getClass().getSimpleName(),
                    ex.code(),
                    safeArtifactFailureMessage(ex));
            return result("결과물을 저장할 수 없습니다.", true);
        } catch (RuntimeException ex) {
            log.warn(
                    "artifact write failed userId={} executionId={} exceptionClass={} errorCode={} reason={}",
                    user.id(),
                    caller.executionId(),
                    ex.getClass().getSimpleName(),
                    ErrorCode.INTERNAL_ERROR,
                    "unexpected artifact write failure");
            return result("결과물을 저장할 수 없습니다.", true);
        }
    }

    /** 요청자가 쓸 수 있는 에이전트를 {@code code} 와 {@code name} 만 담은 JSON 배열로 돌려준다. profile, 주소, 모델, 공개 범위는 싣지 않는다. */
    public Map<String, Object> listAgents(McpCaller caller) {
        List<Map<String, Object>> listed = delegations.list(caller.user()).stream()
                .map(McpToolService::agentSummary)
                .toList();
        return result(JSON.writeValueAsString(listed), false);
    }

    /**
     * 맡긴 실행 하나의 상태를 JSON 글로 돌려준다.
     *
     * <p>{@code SUCCEEDED} 는 답을, {@code FAILED} 는 오류 코드를, {@code CANCELLED} 는 답이 있으면 답을 싣는다.
     * run 번호, profile, 토큰 수, 금액, 예외 문구는 싣지 않는다. 물을 수 없는 실행은 없는 실행과 같은 결과다.
     *
     * <p>끝난 결과를 돌려주면 부모가 받은 것으로 적는다. 그 결과를 부모 대화에 다시 전하지 않기 위해서다.
     *
     * <p>연결용 에이전트의 답은 외부 서비스의 글을 담으므로 부모 대화에 전할 때와 같이 {@code <external-data>} 로
     * 감싼다. 에이전트 행이 없는 실행도 출처를 모르므로 감싼다(ADR-049).
     *
     * @param wait 그 실행이 끝나기를 기다릴 시간. 0 이면 곧바로 답한다. 상한은 {@link AgentDelegationService} 가 줄인다
     */
    public Map<String, Object> agentStatus(McpCaller caller, Long executionId, Duration wait) {
        return delegations
                .status(caller.user(), caller.originExecution(), executionId, wait)
                .map(execution -> {
                    markDeliveredIfFinished(execution);
                    return result(JSON.writeValueAsString(statusOf(execution)), false);
                })
                .orElseGet(() -> result(JSON.writeValueAsString(failure("NOT_FOUND", EXECUTION_NOT_FOUND)), true));
    }

    /**
     * 맡긴 실행 하나를 멈추고 그 뒤의 상태를 {@link #agentStatus} 와 같은 모양의 JSON 글로 돌려준다.
     *
     * <p>짧게 기다려도 아직 {@code RUNNING} 이고 이번 호출이 실제로 중지 표시를 켰거나 Hermes 에 중지를 보냈으면
     * {@code stop_requested: true} 를 더한다. run 번호가 없어 아무것도 보내지 못한 끊긴 실행은 {@code RUNNING} 만 준다.
     * 이미 끝난 실행은 멈추지 않고 끝난 상태를 준다. 물을 수 없는 실행은 없는 실행과 같은 결과다.
     *
     * <p>끝난 결과를 돌려주면 {@link #agentStatus} 처럼 부모가 받은 것으로 적는다.
     */
    public Map<String, Object> agentStop(McpCaller caller, Long executionId) {
        return delegations
                .stop(caller.user(), caller.originExecution(), executionId)
                .map(stop -> {
                    AgentExecution execution = stop.execution();
                    markDeliveredIfFinished(execution);
                    Map<String, Object> status = statusOf(execution);
                    if (execution.status() == ExecutionStatus.RUNNING && stop.stopRequested()) {
                        status.put("stop_requested", true);
                    }
                    return result(JSON.writeValueAsString(status), false);
                })
                .orElseGet(() -> result(JSON.writeValueAsString(failure("NOT_FOUND", EXECUTION_NOT_FOUND)), true));
    }

    /**
     * 다른 에이전트의 실행을 시작하고 번호를 JSON 글로 돌려준다. 제출까지만 기다린다.
     *
     * <p>부모는 요청자를 정한 origin 실행이다. 같은 호출을 알아보는 키는 그 실행의 profile 과 서명한 {@code _fos_ctx} 의 세
     * 값으로 만든다(ADR-032 「{@code delegation_key}」). 거절하면 정해 둔 코드와 한국어 한 줄만 싣는다.
     */
    public Map<String, Object> delegate(McpCaller caller, String agentCode, String task) {
        AgentExecution origin = caller.originExecution();
        McpCallContext context = caller.context();
        DelegationKey key = DelegationKey.of(
                origin.profileName(), context.rootSessionId(), context.sessionId(), context.toolCallId());
        DelegationResult delegated = delegations.delegate(caller.user(), origin, key, agentCode, task);
        if (!delegated.accepted()) {
            Failure failure = delegated.failure();
            return result(JSON.writeValueAsString(failure(failure.name(), delegationFailureMessage(failure))), true);
        }
        Map<String, Object> started = new LinkedHashMap<>();
        started.put("execution_id", delegated.executionId());
        started.put("status", delegated.status().name());
        return result(JSON.writeValueAsString(started), false);
    }

    private static String delegationFailureMessage(Failure failure) {
        return switch (failure) {
            case AGENT_UNAVAILABLE -> "맡길 수 없는 에이전트입니다.";
            case AGENT_DISABLED -> "꺼진 에이전트입니다.";
            case DEPTH_EXCEEDED -> "더 깊이 맡길 수 없습니다.";
            case TOO_MANY_CHILDREN -> "이미 맡긴 일이 많습니다. 앞의 일이 끝난 뒤 다시 맡겨 주세요.";
            case BUSY -> "지금은 맡길 수 없습니다. 직접 처리하거나 앞의 작업이 끝난 뒤 다시 맡겨 주세요.";
            case SUBMIT_FAILED -> "실행을 시작하지 못했습니다.";
            case CHECK_TARGET -> "먼저 살펴보기에서는 연결한 서비스의 에이전트에만 맡길 수 있습니다.";
            case CHECK_LIMIT -> "이번 살펴보기에서는 더 맡길 수 없습니다.";
        };
    }

    /** 끝난 실행이면 결과를 전했다고 적는다. {@code RUNNING} 은 결과가 아직 없으므로 적지 않는다. */
    private void markDeliveredIfFinished(AgentExecution execution) {
        if (execution.status() != ExecutionStatus.RUNNING) {
            deliveryWriter.markResultDelivered(execution.id(), clock.instant());
        }
    }

    private static Map<String, Object> agentSummary(Agent agent) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("code", agent.code());
        summary.put("name", agent.name());
        return summary;
    }

    private Map<String, Object> statusOf(AgentExecution execution) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("execution_id", execution.id());
        status.put("status", execution.status().name());
        ExecutionStatus value = execution.status();
        if ((value == ExecutionStatus.SUCCEEDED || value == ExecutionStatus.CANCELLED)
                && execution.outputText() != null) {
            status.put(
                    "output",
                    isExternalResult(execution) ? ExternalData.wrap(execution.outputText()) : execution.outputText());
        }
        if (value == ExecutionStatus.FAILED && execution.errorCode() != null) {
            status.put("error_code", execution.errorCode());
        }
        return status;
    }

    /** 연결용 에이전트의 실행이거나 에이전트를 찾지 못한 실행이다. */
    private boolean isExternalResult(AgentExecution execution) {
        if (execution.agentId() == null) {
            return true;
        }
        return agents.findById(execution.agentId()).map(Agent::connectorManaged).orElse(true);
    }

    private static Map<String, Object> failure(String code, String message) {
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("code", code);
        failure.put("message", message);
        return failure;
    }

    private static String safeArtifactFailureMessage(ApiException exception) {
        String message = exception.getMessage();
        return message != null && SAFE_ARTIFACT_FAILURE_MESSAGES.contains(message) ? message : "artifact write failed";
    }

    private static String toJson(ArtifactWriteResult result) {
        return JSON.writeValueAsString(Map.of("path", result.path(), "byteSize", result.byteSize()));
    }

    private static Map<String, Object> result(String text, boolean error) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(Map.of("type", "text", "text", text)));
        result.put("isError", error);
        return result;
    }
}
