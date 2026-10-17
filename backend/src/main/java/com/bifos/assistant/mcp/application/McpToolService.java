package com.bifos.assistant.mcp.application;

import com.bifos.assistant.mcp.application.dto.McpCallContext;
import com.bifos.assistant.mcp.application.dto.McpCaller;
import com.bifos.assistant.agent.application.AgentConnectorBindings;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.ArtifactWriteRequest;
import com.bifos.assistant.chat.application.ArtifactWriteResult;
import com.bifos.assistant.chat.application.ArtifactWriteService;
import com.bifos.assistant.followup.application.FollowUpDueAt;
import com.bifos.assistant.followup.application.FollowUpService;
import com.bifos.assistant.followup.application.model.FollowUpProposalOutcome;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.application.model.MemorySearchCursor;
import com.bifos.assistant.memory.application.model.MemorySearchPage;
import com.bifos.assistant.memory.application.relevance.QueryEmbedder;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.orchestration.application.AgentDelegationService;
import com.bifos.assistant.orchestration.application.dto.DelegationResult;
import com.bifos.assistant.orchestration.application.dto.DelegationResult.Failure;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.util.ExternalData;
import com.bifos.assistant.usage.application.ContextSourceRef;
import com.bifos.assistant.usage.application.ExecutionContextSourceWriter;
import com.bifos.assistant.usage.application.ExecutionDeliveryWriter;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.DelegationKey;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    private static final String DUE_AT_FORMAT = "due_at 은 2026-10-05 나 2026-10-05T18:00 형식이다.";

    private final MemoryService memories;
    private final QueryEmbedder queryEmbedder;
    private final ArtifactWriteService artifacts;
    private final AgentDelegationService delegations;
    private final ExecutionDeliveryWriter deliveryWriter;
    /** {@code memory_read} 로 본문을 낸 항목을 그 실행의 문맥 기록 끝에 덧붙인다. */
    private final ExecutionContextSourceWriter contextSourceWriter;

    private final AgentRepository agents;
    private final AgentConnectorBindings bindings;
    private final FollowUpService followUps;
    private final McpMemoryRemember memoryRemember;
    private final McpMemoryLink memoryLink;
    private final McpToolCatalog catalog;
    private final Clock clock;


    public List<Map<String, Object>> tools() {
        return catalog.tools();
    }


    /**
     * 요청자를 정하지 못한 호출의 도구 결과다.
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
            String content = memories.contentOf(memory);
            log.info("memory read userId={} memoryId={} executionId={}", user.id(), id, caller.executionId());
            // 본문을 낸 호출만 남긴다. 「참고한 기억」 이 이 줄을 읽는다
            contextSourceWriter.append(
                caller.executionId(), new ContextSourceRef("MEMORY_READ", "memory:" + id, "INLINE", "UNKNOWN"));
            return result(content, false);
        } catch (ApiException ex) {
            if (ex.code() == ErrorCode.MEMORY_NOT_FOUND) {
                return result("Memory 항목을 읽을 수 없습니다.", true);
            }
            throw ex;
        }
    }


    /** 제목만 반환한다. 본문 사용 기록은 뒤이은 성공한 memory_read가 남긴다. */
    public Map<String, Object> searchMemory(McpCaller caller, String query, int limit, MemorySearchCursor cursor) {
        // 질문 임베딩은 검색의 2초 트랜잭션 밖에서 한다. 늦거나 실패하면 키워드 점수만 쓴다
        // 키워드만으로 매긴 검색의 다음 페이지는 같은 규칙으로 잇도록 임베딩하지 않는다
        Optional<float[]> queryVector = cursor.needsQueryVector() ? queryEmbedder.embed(query) : Optional.empty();
        MemorySearchPage page = memories.searchFor(
            caller.user(), memories.accessOf(caller.originExecution().agentId()), query, limit, cursor, queryVector);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", page.items());
        data.put("nextCursor", page.nextCursor());
        log.info(
            "memory search executionId={} resultCount={} selector={}",
            caller.executionId(),
            page.items().size(),
            page.selector());
        return result(ExternalData.wrap(JSON.writeValueAsString(data)), false);
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
     * <p>{@code SUCCEEDED} 는 답을, {@code FAILED} 는 오류 코드를, {@code CANCELLED} 는 답이 있으면 답을 싣는다.
     * run 번호, profile, 토큰 수, 금액, 예외 문구는 싣지 않는다. 물을 수 없는 실행은 없는 실행과 같은 결과다.
     * <p>끝난 결과를 돌려주면 부모가 받은 것으로 적는다. 그 결과를 부모 대화에 다시 전하지 않기 위해서다.
     * <p>연결용 에이전트의 답은 외부 서비스의 글을 담으므로 부모 대화에 전할 때와 같이 {@code <external-data>} 로
     * 감싼다. 에이전트 행이 없는 실행도 출처를 모르므로 감싼다(ADR-049).
     * @param wait 그 실행이 끝나기를 기다릴 시간. 0 이면 곧바로 답한다. 먼저 살펴보기 트리가 아니면 기다리지 않고 상한은
     * {@link AgentDelegationService} 가 줄인다
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
     * <p>짧게 기다려도 아직 {@code RUNNING} 이고 이번 호출이 실제로 중지 표시를 켰거나 Hermes 에 중지를 보냈으면
     * {@code stop_requested: true} 를 더한다. run 번호가 없어 아무것도 보내지 못한 끊긴 실행은 {@code RUNNING} 만 준다.
     * 이미 끝난 실행은 멈추지 않고 끝난 상태를 준다. 물을 수 없는 실행은 없는 실행과 같은 결과다.
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


    /**
     * 할 일을 {@code PROPOSED} 로 제안하고 그 결과를 한 줄 글로 돌려준다. 결과마다의 글과 {@code isError} 는 이 메서드가 갖는다.
     * <p>주인과 대화는 origin 실행에서 정한다. 인자로 받지 않는다(ADR-032). 값이 틀린 인자는 모델이 고쳐 다시 부를 수 있게
     * {@code isError} 와 무엇이 틀렸는지 한 줄로 답한다.
     * @param dueAt 모델이 준 기한 글. 없으면 null
     */
    public Map<String, Object> proposeFollowUp(McpCaller caller, String title, String dueAt, boolean waiting) {
        AgentExecution origin = caller.originExecution();
        Long conversationId = origin.conversationId();
        if (conversationId == null) {
            return result("대화 밖의 실행에서는 할 일을 제안할 수 없다.", true);
        }
        String stripped = title.strip();
        int length = stripped.codePointCount(0, stripped.length());
        if (length < 1 || length > FollowUpService.TITLE_MAX) {
            return result("title 은 1자부터 200자까지다.", true);
        }
        Instant due = null;
        if (dueAt != null) {
            Optional<Instant> parsed = FollowUpDueAt.parseLenient(dueAt);
            if (parsed.isEmpty()) {
                return result(DUE_AT_FORMAT, true);
            }
            due = parsed.get();
        }
        FollowUpProposalOutcome outcome = followUps.propose(caller.user(), conversationId, origin.id(), stripped, due, waiting);
        return switch (outcome) {
            case CREATED -> result("할 일로 제안했다. 사용자가 지금 화면에서 받아들이면 챙긴다.", false);
            case DUPLICATE -> result("같은 할 일이 이미 있다. 새로 만들지 않았다.", false);
            case DECLINED_BEFORE -> result("사용자가 이 할 일을 거절했다. 다시 제안하지 않는다.", true);
            case TOO_MANY_PROPOSALS, TOO_MANY_IN_RUN -> result("이 대화에 받아들이기를 기다리는 제안이 많다. 사용자가 정한 뒤에 제안한다.", true);
        };
    }


    /** {@link McpMemoryLink#link} 에 넘긴다. */
    public Map<String, Object> linkMemory(McpCaller caller, long fromId, long toId, String type, String label) {
        return memoryLink.link(caller, fromId, toId, type, label);
    }


    /** {@link McpMemoryRemember#remember} 에 넘긴다. */
    public Map<String, Object> remember(
        McpCaller caller,
        String title,
        String content,
        String evidence,
        Long memoryId,
        String collection,
        boolean sensitive) {
        return memoryRemember.remember(caller, title, content, evidence, memoryId, collection, sensitive);
    }


    private static String delegationFailureMessage(Failure failure) {
        return switch (failure) {
            case AGENT_UNAVAILABLE -> "맡길 수 없는 에이전트입니다.";
            case AGENT_DISABLED -> "꺼진 에이전트입니다.";
            case DEPTH_EXCEEDED -> "더 깊이 맡길 수 없습니다.";
            case TOO_MANY_CHILDREN -> "이미 맡긴 일이 많습니다. 앞의 일이 끝난 뒤 다시 맡겨 주세요.";
            case BUSY -> "지금은 맡길 수 없습니다. 직접 처리하거나 앞의 작업이 끝난 뒤 다시 맡겨 주세요.";
            case SUBMIT_FAILED -> "실행을 시작하지 못했습니다.";
            case CHECK_TARGET -> "먼저 살펴보기에서는 연결한 서비스의 도구를 직접 부르거나, 연결한 서비스의 에이전트에만 맡길 수 있습니다.";
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


    /** 연결이 붙은 에이전트의 실행이거나, 에이전트를 찾지 못한 실행이다(ADR-20261008 / read-data-flow). */
    private boolean isExternalResult(AgentExecution execution) {
        if (execution.agentId() == null) {
            return true;
        }
        return agents.findById(execution.agentId())
            .map(agent -> bindings.hasBindings(agent.id()))
            .orElse(true);
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


    static Map<String, Object> result(String text, boolean error) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(Map.of("type", "text", "text", text)));
        result.put("isError", error);
        return result;
    }
}
