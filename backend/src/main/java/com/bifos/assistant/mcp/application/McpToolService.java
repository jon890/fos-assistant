package com.bifos.assistant.mcp.application;

import com.bifos.assistant.mcp.application.dto.McpCallContext;
import com.bifos.assistant.mcp.application.dto.McpCaller;
import com.bifos.assistant.agent.application.AgentCapabilitiesReader;
import com.bifos.assistant.agent.application.dto.AgentCapabilities;
import com.bifos.assistant.agent.application.port.AgentConnectorBindings;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.artifact.application.dto.ArtifactWriteRequest;
import com.bifos.assistant.chat.artifact.application.dto.ArtifactWriteResult;
import com.bifos.assistant.chat.artifact.application.ArtifactWriteService;
import com.bifos.assistant.chat.artifact.domain.ArtifactSourceRejectedException;
import com.bifos.assistant.followup.application.FollowUpDueAt;
import com.bifos.assistant.followup.application.FollowUpService;
import com.bifos.assistant.followup.application.dto.FollowUpProposalOutcome;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.application.dto.MemorySearchCursor;
import com.bifos.assistant.memory.application.dto.MemorySearchPage;
import com.bifos.assistant.memory.application.QueryEmbedder;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.orchestration.application.AgentDelegationService;
import com.bifos.assistant.orchestration.application.DelegationWaiter;
import com.bifos.assistant.orchestration.application.dto.DelegationWait;
import com.bifos.assistant.orchestration.application.dto.DelegationResult;
import com.bifos.assistant.orchestration.application.dto.DelegationResult.Failure;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.util.ExternalData;
import com.bifos.assistant.shared.util.ToolFailureText;
import com.bifos.assistant.usage.execution.application.dto.ContextSourceRef;
import com.bifos.assistant.usage.execution.application.ExecutionContextSourceWriter;
import com.bifos.assistant.usage.execution.application.ExecutionDeliveryWriter;
import com.bifos.assistant.usage.execution.application.ExecutionOutputs;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.DelegationKey;
import com.bifos.assistant.usage.execution.domain.type.ExecutionStatus;
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
    static final ToolFailureText INVALID_CONTEXT = ToolFailureText.askUser("호출 맥락을 확인할 수 없습니다. 새 대화에서 다시 시도해 주세요.");
    /** 서버 쪽 실패의 다음 방법이다. 같은 호출을 다시 부르지 않는다(docs/features/tool-fallback/spec.md 의 DECISION10). */
    static final String SERVER_FAILURE_NEXT = "다른 도구가 있으면 그것으로 해 보고, 없으면 잠시 뒤 다시 해 달라고 알린다";
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
    private static final ToolFailureText NOT_ALLOWED_IN_CHECK = ToolFailureText.askUser("먼저 살펴보기에서는 쓸 수 없는 도구입니다.");
    /** 기다려도 끝나지 않은 맡김에 붙이는 지시다. 결과를 짐작한 글과 결과 답이 겹치지 않게 한다. */
    static final String DELEGATE_RUNNING_NOTE = "아직 끝나지 않았다. 결과는 끝나면 이 대화의 다음 차례에 자동으로 전달된다. "
        + "이번 답은 누구에게 무엇을 맡겼는지 한 줄로 끝내고 결과를 짐작해 쓰지 않는다.";

    /** {@code agent_delegate} 의 {@code task} 길이 상한. 대화 메시지 상한과 같다. */
    public static final int TASK_MAX_CHARS = 8000;

    private static final ToolFailureText DUE_AT_FORMAT = ToolFailureText.alternative(
        "due_at 은 2026-10-05 나 2026-10-05T18:00 형식이다.", "형식에 맞게 고쳐 다시 부른다");
    private static final ToolFailureText ARTIFACT_FAILED = ToolFailureText.alternative("결과물을 저장할 수 없습니다.", SERVER_FAILURE_NEXT);

    private final MemoryService memories;
    private final QueryEmbedder queryEmbedder;
    private final ArtifactWriteService artifacts;
    private final AgentDelegationService delegations;
    private final DelegationWaiter waiter;
    private final AgentCapabilitiesReader capabilitiesReader;
    private final ExecutionDeliveryWriter deliveryWriter;
    private final ExecutionOutputs executionOutputs;
    /** {@code memory_read} 로 본문을 낸 항목을 그 실행의 문맥 기록 끝에 덧붙인다. */
    private final ExecutionContextSourceWriter contextSourceWriter;

    private final AgentRepository agents;
    private final AgentConnectorBindings bindings;
    private final McpFollowUpTools followUpTools;
    private final McpMemoryRemember memoryRemember;
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
        return failure(INVALID_CONTEXT);
    }


    /** 먼저 살펴보기 트리에서 받지 않는 도구를 부른 호출의 도구 결과다. 도구는 돌리지 않는다(ADR-080). */
    public Map<String, Object> notAllowedInCheck() {
        return failure(NOT_ALLOWED_IN_CHECK);
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
            return success(content);
        } catch (ApiException ex) {
            if (ex.code() == ErrorCode.MEMORY_NOT_FOUND) {
                return failure(ToolFailureText.alternative("Memory 항목을 읽을 수 없습니다.", "memory_search 로 다른 항목을 찾는다"));
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
        return success(ExternalData.wrap(JSON.writeValueAsString(data)));
    }


    public Map<String, Object> writeArtifact(McpCaller caller, ArtifactWriteRequest request) {
        CurrentUser user = caller.user();
        try {
            ArtifactWriteResult written = artifacts.write(user, request);
            return success(toJson(written));
        } catch (ApiException ex) {
            if (ex.code() == ErrorCode.VALIDATION_FAILED) {
                if (ex instanceof ArtifactSourceRejectedException rejected) {
                    log.warn(
                        "artifact source rejected userId={} executionId={} errorCode={} reason={}",
                        user.id(), caller.executionId(), ex.code(), rejected.reason());
                    // 막힌 호스트를 다른 길로 열지 않고 허용된 다른 호스트를 쓰게 한다(docs/features/tool-fallback/spec.md 의 DECISION9).
                    return failure("allowed_host".equals(rejected.reason())
                        ? ToolFailureText.alternative(
                            "source_url의 호스트가 허용되지 않았습니다. 제공받은 주소는 글자 그대로 전달하세요.",
                            "허용된 이미지 호스트의 주소로 다시 부른다")
                        : ToolFailureText.alternative(
                            "source_url 주소 형식이 올바르지 않습니다. 제공받은 주소는 글자 그대로 전달하세요.",
                            "HTTPS 이미지 주소인지 확인하고 주소를 고쳐 다시 부른다"));
                }
                throw ex;
            }
            log.warn(
                "artifact write failed userId={} executionId={} exceptionClass={} errorCode={} reason={}",
                user.id(),
                caller.executionId(),
                ex.getClass().getSimpleName(),
                ex.code(),
                safeArtifactFailureMessage(ex));
            return failure(ARTIFACT_FAILED);
        } catch (RuntimeException ex) {
            log.warn(
                "artifact write failed userId={} executionId={} exceptionClass={} errorCode={} reason={}",
                user.id(),
                caller.executionId(),
                ex.getClass().getSimpleName(),
                ErrorCode.INTERNAL_ERROR,
                "unexpected artifact write failure");
            return failure(ARTIFACT_FAILED);
        }
    }


    /**
     * 요청자가 쓸 수 있는 에이전트를 JSON 배열로 돌려준다. {@code code}, {@code name} 에 더해 소개와 할 수 있는 일을 비어 있지 않을 때만
     * 싣는다(docs/features/agent-team/spec.md 의 REQ2). 매 위임 판단에 실리므로 빈 칸을 빼 글을 줄인다. profile, 주소, 모델, 공개
     * 범위는 싣지 않는다.
     */
    public Map<String, Object> listAgents(McpCaller caller) {
        List<Agent> listable = delegations.list(caller.user());
        Map<Long, AgentCapabilities> capabilities = capabilitiesReader.read(listable);
        List<Map<String, Object>> listed = listable.stream()
            .map(agent -> agentSummary(agent, capabilities.getOrDefault(agent.id(), AgentCapabilities.NONE)))
            .toList();
        return success(JSON.writeValueAsString(listed));
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
                return success(JSON.writeValueAsString(statusOf(execution)));
            })
            .orElseGet(() -> jsonFailure(failureBody("NOT_FOUND", EXECUTION_NOT_FOUND), ToolFailureText.askUser(EXECUTION_NOT_FOUND)));
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
                return success(JSON.writeValueAsString(status));
            })
            .orElseGet(() -> jsonFailure(failureBody("NOT_FOUND", EXECUTION_NOT_FOUND), ToolFailureText.askUser(EXECUTION_NOT_FOUND)));
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
            return jsonFailure(failureBody(failure.name(), delegationFailureMessage(failure)), delegationFailure(failure));
        }
        // 대화 turn 의 맡김은 정한 시간까지 기다려 결과를 함께 돌려준다(ADR-20261121 / delegate-wait)
        DelegationWait waited = waiter.await(origin, delegated.executionId());
        if (waited.deliverable()) {
            markDeliveredIfFinished(waited.execution());
            return success(JSON.writeValueAsString(statusOf(waited.execution())));
        }
        Map<String, Object> started = new LinkedHashMap<>();
        started.put("execution_id", delegated.executionId());
        ExecutionStatus status = waited.waited() && waited.execution() != null ? waited.execution().status() : delegated.status();
        started.put("status", status.name());
        if (waited.waited() && status == ExecutionStatus.RUNNING) {
            started.put("note", DELEGATE_RUNNING_NOTE);
        }
        return success(JSON.writeValueAsString(started));
    }


    /**
     * 할 일을 남기고 그 결과를 한 줄 글로 돌려준다. 결과마다의 글과 {@code isError} 는 이 메서드가 갖는다.
     * <p>모델이 사용자가 시켰다고 했고({@code requested}) 실행의 출처가 맞으면 바로 챙기는 {@code OPEN} 이고, 아니면 {@code PROPOSED}
     * 제안이다(스펙 service-concepts REQ11). 출처 판정은 {@link McpFollowUpTools#userDirected} 가 갖는다.
     * <p>주인과 대화는 origin 실행에서 정한다. 인자로 받지 않는다(ADR-032). 값이 틀린 인자는 모델이 고쳐 다시 부를 수 있게
     * {@code isError} 와 무엇이 틀렸는지 한 줄로 답한다.
     * @param dueAt 모델이 준 기한 글. 없으면 null
     * @param requested 모델이 사용자가 직접 시킨 할 일이라고 했는가
     */
    public Map<String, Object> proposeFollowUp(McpCaller caller, String title, String dueAt, boolean waiting, boolean requested) {
        AgentExecution origin = caller.originExecution();
        Long conversationId = origin.conversationId();
        if (conversationId == null) {
            return failure(ToolFailureText.askUser("대화 밖의 실행에서는 할 일을 제안할 수 없다."));
        }
        String stripped = title.strip();
        int length = stripped.codePointCount(0, stripped.length());
        if (length < 1 || length > FollowUpService.TITLE_MAX) {
            return failure(ToolFailureText.alternative("title 은 1자부터 200자까지다.", "길이에 맞게 고쳐 다시 부른다"));
        }
        Instant due = null;
        if (dueAt != null) {
            Optional<Instant> parsed = FollowUpDueAt.parseLenient(dueAt);
            if (parsed.isEmpty()) {
                return failure(DUE_AT_FORMAT);
            }
            due = parsed.get();
        }
        boolean direct = requested && followUpTools.userDirected(caller);
        FollowUpProposalOutcome outcome = followUpTools.save(caller, stripped, due, waiting, direct);
        return switch (outcome) {
            case OPENED -> success("할 일로 적었다. 바로 챙긴다. 답에 /now 링크를 붙인다.");
            case CREATED -> success("할 일로 제안했다. 사용자가 지금 화면에서 받아들이면 챙긴다.");
            case DUPLICATE -> success("같은 할 일이 이미 있다. 새로 만들지 않았다.");
            case DECLINED_BEFORE -> failure(ToolFailureText.askUser("사용자가 이 할 일을 거절했다. 다시 제안하지 않는다."));
            case TOO_MANY_PROPOSALS -> failure(
                ToolFailureText.askUser("이 대화에 받아들이기를 기다리는 제안이 많다. 사용자가 정한 뒤에 제안한다."));
            case TOO_MANY_IN_RUN -> failure(ToolFailureText.askUser(direct
                ? "이번 답에서 이미 할 일을 많이 적었다. 더 적지 않았다. 남은 것은 사용자에게 알린다."
                : "이 대화에 받아들이기를 기다리는 제안이 많다. 사용자가 정한 뒤에 제안한다."));
        };
    }


    /** {@link McpMemoryRemember#remember} 에 넘긴다. */
    public Map<String, Object> remember(
        McpCaller caller,
        String title,
        String content,
        String summary,
        String evidence,
        Long memoryId,
        String collection,
        boolean sensitive,
        boolean archivable,
        List<Long> replaces) {
        return memoryRemember.remember(
            caller, title, content, summary, evidence, memoryId, collection, sensitive, archivable, replaces);
    }


    /** 위임 실패마다 모델이 해 볼 다음 방법이다. 글의 앞부분은 {@link #delegationFailureMessage} 다. */
    private static ToolFailureText delegationFailure(Failure failure) {
        String message = delegationFailureMessage(failure);
        return switch (failure) {
            case AGENT_UNAVAILABLE, AGENT_DISABLED -> ToolFailureText.alternative(message, "맡기지 않고 직접 처리하거나 다른 에이전트에 맡긴다");
            case DEPTH_EXCEEDED, CHECK_LIMIT -> ToolFailureText.alternative(message, "맡기지 않고 직접 처리한다");
            case TOO_MANY_CHILDREN -> ToolFailureText.alternative(message, "맡긴 일이 끝나기를 기다리거나 멈춘 뒤 다시 맡긴다");
            case BUSY -> ToolFailureText.alternative(message, "직접 처리하거나 앞의 작업이 끝난 뒤 다시 맡긴다");
            case SUBMIT_FAILED -> ToolFailureText.alternative(message, SERVER_FAILURE_NEXT);
            case CHECK_TARGET -> ToolFailureText.alternative(message, "연결한 서비스의 도구를 직접 부르거나 연결한 서비스의 에이전트에 맡긴다");
        };
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


    private static Map<String, Object> agentSummary(Agent agent, AgentCapabilities capabilities) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("code", agent.code());
        summary.put("name", agent.name());
        if (agent.description() != null) {
            summary.put("description", agent.description());
        }
        if (!capabilities.connectors().isEmpty()) {
            summary.put("connectors", capabilities.connectors());
        }
        if (!capabilities.skills().isEmpty()) {
            summary.put("skills", capabilities.skills());
        }
        return summary;
    }


    private Map<String, Object> statusOf(AgentExecution execution) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("execution_id", execution.id());
        status.put("status", execution.status().name());
        ExecutionStatus value = execution.status();
        if (value == ExecutionStatus.SUCCEEDED || value == ExecutionStatus.CANCELLED) {
            String output = executionOutputs.open(execution);
            if (output != null) {
                status.put("output", isExternalResult(execution) ? ExternalData.wrap(output) : output);
            }
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


    private static Map<String, Object> failureBody(String code, String message) {
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


    static Map<String, Object> success(String text) {
        return toolResult(text, false);
    }


    /** 끝 줄이 있는 실패 글만 받는다(docs/features/tool-fallback/spec.md 의 REQ3). */
    static Map<String, Object> failure(ToolFailureText failure) {
        return toolResult(failure.text(), true);
    }


    /** 실패를 JSON 글로 돌려주는 도구의 결과다. 글 끝에 줄을 붙이면 JSON 이 깨지므로 같은 끝 줄을 {@code next} 칸에 넣는다. */
    static Map<String, Object> jsonFailure(Map<String, ?> fields, ToolFailureText next) {
        return jsonFailure(fields, next, false);
    }


    /** @param external 외부 서비스에서 온 글이 들어 있어 {@code <external-data>} 로 감싸야 하는가 */
    static Map<String, Object> jsonFailure(Map<String, ?> fields, ToolFailureText next, boolean external) {
        Map<String, Object> body = new LinkedHashMap<>(fields);
        body.put("next", next.endLine());
        String json = JSON.writeValueAsString(body);
        return toolResult(external ? ExternalData.wrap(json) : json, true);
    }


    private static Map<String, Object> toolResult(String text, boolean error) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(Map.of("type", "text", "text", text)));
        result.put("isError", error);
        return result;
    }
}
