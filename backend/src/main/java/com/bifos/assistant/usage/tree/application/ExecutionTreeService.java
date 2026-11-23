package com.bifos.assistant.usage.tree.application;

import com.bifos.assistant.usage.execution.application.dto.ContextSourceRef;
import com.bifos.assistant.usage.execution.domain.InternalValuePolicy;
import com.bifos.assistant.usage.execution.application.dto.ConnectorToolName;
import com.bifos.assistant.usage.execution.application.port.ConnectorToolNames;
import com.bifos.assistant.usage.tree.application.dto.ExecutionEventView;
import com.bifos.assistant.usage.tree.application.dto.ExecutionNode;
import com.bifos.assistant.usage.tree.application.dto.ExecutionTree;
import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.ExecutionContextSource;
import com.bifos.assistant.usage.execution.domain.ExecutionEvent;
import com.bifos.assistant.usage.subagent.domain.SubagentUsageJob;
import com.bifos.assistant.usage.execution.domain.type.ExecutionEventType;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.execution.infra.ExecutionContextSourceRepository;
import com.bifos.assistant.usage.execution.infra.ExecutionEventRepository;
import com.bifos.assistant.usage.subagent.infra.SubagentUsageJobRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실행 하나를 그 사건과 자식 실행까지 묶어 낸다.
 *
 * <p>어느 실행 번호를 주든 그 실행이 속한 트리의 루트부터 낸다. 화면이 자식 실행에서 들어와도 전체를
 * 보게 하기 위해서다.
 *
 * <p>트리를 데이터베이스에서 재귀로 만들지 않는다. {@code root_execution_id} 로 한 번에 읽어 메모리에서
 * 잇는다. 한 실행의 자식 수가 많아질 일이 없고, 재귀 질의는 읽기 어렵다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ExecutionTreeService {

    /**
     * 위로 올라가는 횟수와 아래로 내려가는 깊이의 상한이다.
     *
     * <p>잘못 적힌 {@code parent_execution_id} 하나로 순환이 생기면 응답이 끝나지 않는다.
     *
     * <p>화면의 {@code execution-node.tsx} 가 같은 값을 따로 갖는다. 여기를 키우면 화면이 그리지
     * 않는 깊이에 {@code truncated} 가 실려, 잘렸다는 것이 아무 데도 보이지 않게 된다. 함께 바꾼다.
     */
    static final int MAX_DEPTH = 8;

    private static final String DELEGATE_TOOL = "agent_delegate";
    private static final Duration DELEGATE_SKEW = Duration.ofSeconds(2);

    private final AgentExecutionRepository executions;
    private final ExecutionEventRepository events;
    private final AgentService agents;
    private final SubagentUsageJobRepository jobs;
    private final ExecutionContextSourceRepository contextSources;
    private final ConnectorToolNames connectorToolNames;


    /**
     * 질의를 여럿 내므로 한 트랜잭션으로 묶는다.
     *
     * <p>루트를 찾고, 자손을 읽고, 사건을 읽고, 노드마다 에이전트를 읽는다. 따로 돌면 그 사이에 바뀐
     * 자료를 섞어 읽는다.
     */
    @Transactional(readOnly = true)
    public ExecutionTree of(CurrentUser user, Long executionId) {
        return ofOwner(user.id(), user, executionId);
    }


    /**
     * 주인이 아닌 사람이 그 주인의 트리를 본다. 관리자가 평가 전용 사용자의 실행을 열 때만 쓴다(docs/features/agent-quality/design.md 의
     * 「관리자 API」). 주인을 고르는 판정은 부르는 쪽이 한다. 내부 값을 보일지는 보는 사람 {@code user} 로 정한다.
     *
     * @param ownerUserId 트리의 주인. 실행이 이 사용자의 것이 아니면 없는 것과 같다
     */
    @Transactional(readOnly = true)
    public ExecutionTree ofOwner(Long ownerUserId, CurrentUser user, Long executionId) {
        AgentExecution asked = executions.findById(executionId).orElseThrow(ExecutionTreeService::notFound);
        requireOwner(ownerUserId, asked);

        Ascent ascent = climbToRoot(asked);
        AgentExecution root = ascent.root();
        // 자식이 남의 것일 수 없지만, 루트를 찾아 올라간 뒤에도 주인을 확인한다.
        requireOwner(ownerUserId, root);

        List<AgentExecution> descendants = executions.findByRootExecutionId(root.id());
        Map<Long, List<AgentExecution>> byParent = byParent(descendants);
        Set<Long> used = new LinkedHashSet<>();
        Set<Long> cut = new LinkedHashSet<>();
        Branch rootBranch = branch(root, byParent, used, cut, 1);
        warnAboutUnreachable(root, descendants, used, cut);

        Map<Long, List<ExecutionEvent>> byExecution = eventsOf(used);
        Map<Long, List<ContextSourceRef>> sources = InternalValuePolicy.visibleTo(user) ? sourcesOf(used) : Map.of();
        Map<String, ConnectorToolName> toolNames = connectorToolNames.namesOf(byExecution.values().stream()
            .flatMap(List::stream)
            .filter(event -> event.eventType().isTool())
            .map(ExecutionEvent::toolName)
            .toList());
        ExecutionNode rootNode = node(
            rootBranch, byExecution, usageStatuses(used, byExecution), sources, toolNames, user);
        return new ExecutionTree(rootNode, ascent.truncated() || isTruncatedSomewhere(rootBranch));
    }


    /**
     * 루트를 찾는다.
     *
     * <p>{@code root_execution_id} 가 적혀 있으면 그것을 그대로 쓴다. 비어 있으면
     * {@code parent_execution_id} 를 따라 올라간다. 올라간 횟수가 상한을 넘으면 마지막으로 닿은 실행을
     * 루트로 삼고 잘랐다고 알린다. 잘린 것이 노드의 아래가 아니라 위쪽이라, 이때는 트리의 값만 참으로
     * 둔다.
     */
    private Ascent climbToRoot(AgentExecution asked) {
        if (asked.rootExecutionId() != null) {
            return new Ascent(executions.findById(asked.rootExecutionId()).orElse(asked), false);
        }
        AgentExecution current = asked;
        for (int climbed = 0; current.parentExecutionId() != null; climbed++) {
            if (climbed == MAX_DEPTH) {
                return new Ascent(current, true);
            }
            AgentExecution parent = executions.findById(current.parentExecutionId()).orElse(null);
            if (parent == null || Objects.equals(parent.id(), current.id())) {
                return new Ascent(current, false);
            }
            current = parent;
        }
        return new Ascent(current, false);
    }


    /** 루트에 매달린 실행을 부모 번호로 묶는다. */
    private static Map<Long, List<AgentExecution>> byParent(List<AgentExecution> descendants) {
        return descendants.stream()
            .filter(execution -> execution.parentExecutionId() != null)
            .collect(Collectors.groupingBy(
                AgentExecution::parentExecutionId, LinkedHashMap::new, Collectors.toList()));
    }


    /**
     * 한 실행과 그 아래를 잇는다.
     *
     * <p>이미 붙인 실행은 다시 붙이지 않는다. 그러지 않으면 같은 가지가 무한히 자란다. 상한 깊이에서
     * 자식이 남아 있으면 그 노드에 잘랐다고 적고, 잘라 낸 가지를 {@code cut} 에 모은다. 일부러 자른
     * 것을 데이터가 어긋난 것과 섞어 경고하지 않기 위해서다.
     */
    private Branch branch(
        AgentExecution execution,
        Map<Long, List<AgentExecution>> byParent,
        Set<Long> used,
        Set<Long> cut,
        int depth) {
        used.add(execution.id());
        List<AgentExecution> waiting = byParent.getOrDefault(execution.id(), List.of()).stream()
            .filter(child -> !used.contains(child.id()))
            .toList();
        if (waiting.isEmpty()) {
            return new Branch(execution, false, List.of());
        }
        if (depth == MAX_DEPTH) {
            waiting.forEach(child -> markCut(child, byParent, cut));
            return new Branch(execution, true, List.of());
        }
        List<Branch> children = new ArrayList<>();
        for (AgentExecution child : waiting) {
            if (used.contains(child.id())) {
                continue;
            }
            children.add(branch(child, byParent, used, cut, depth + 1));
        }
        return new Branch(execution, false, List.copyOf(children));
    }


    /** 상한에서 잘라 낸 가지를 통째로 모은다. 같은 실행을 두 번 밟지 않아 순환에서도 끝난다. */
    private static void markCut(AgentExecution execution, Map<Long, List<AgentExecution>> byParent, Set<Long> cut) {
        if (!cut.add(execution.id())) {
            return;
        }
        byParent.getOrDefault(execution.id(), List.of()).forEach(child -> markCut(child, byParent, cut));
    }


    /**
     * 루트에서 닿지 않는 실행을 남긴다.
     *
     * <p>{@code root_execution_id} 로 읽어 왔지만 부모 사슬이 루트까지 닿지 않는 줄이다. 데이터가 어긋난
     * 것이고, 그것 때문에 응답이 끝나지 않으면 안 되므로 트리에 넣지 않는다.
     *
     * <p>상한 깊이에서 우리가 일부러 자른 가지는 여기서 뺀다. 원인이 달라서다. 그쪽은 노드의
     * {@code truncated} 가 화면에 알린다.
     */
    private static void warnAboutUnreachable(
        AgentExecution root, List<AgentExecution> descendants, Set<Long> used, Set<Long> cut) {
        List<Long> dropped = descendants.stream()
            .map(AgentExecution::id)
            .filter(id -> !used.contains(id) && !cut.contains(id))
            .toList();
        if (!dropped.isEmpty()) {
            log.warn("루트에 닿지 않아 트리에서 뺀 실행이 있다 rootExecutionId={} executionIds={}", root.id(), dropped);
        }
    }


    /** 트리에 담긴 실행들의 사건을 한 번에 읽어 실행 번호로 묶는다. */
    private Map<Long, List<ExecutionEvent>> eventsOf(Set<Long> executionIds) {
        if (executionIds.isEmpty()) {
            return Map.of();
        }
        return events.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(executionIds).stream()
            .collect(Collectors.groupingBy(ExecutionEvent::executionId, LinkedHashMap::new, Collectors.toList()));
    }


    /**
     * 트리에 담긴 실행들에 실은 문맥 항목의 참조를 한 번에 읽어 실행 번호로 묶는다. 실은 순서를 지킨다.
     *
     * <p>내부 값이라 관리자에게 낼 때만 읽는다.
     */
    private Map<Long, List<ContextSourceRef>> sourcesOf(Set<Long> executionIds) {
        if (executionIds.isEmpty()) {
            return Map.of();
        }
        return contextSources.findByIdExecutionIdInOrderByIdExecutionIdAscIdPositionAsc(executionIds).stream()
            .collect(Collectors.groupingBy(
                ExecutionContextSource::executionId,
                LinkedHashMap::new,
                Collectors.mapping(
                    row -> new ContextSourceRef(
                        row.source(), row.sourceRef(), row.bodyMode(), row.freshness()),
                    Collectors.toList())));
    }


    /**
     * 실행 트리를 응답으로 옮긴다. 도구 사건의 {@code detail} 은 보는 사람에 맞춰 싣는다.
     *
     * <p>{@link InternalValuePolicy} 가 허락하지 않는 사람에게는 노드의 에이전트 코드, 모델, 토큰, 금액, 시각
     * 구간과 실은 문맥 항목을 비우고 {@code PROVIDER_SWITCHED} 사건을 사건째 뺀다. 근거는 ADR-063 에 있다.
     */
    private ExecutionNode node(
        Branch branch,
        Map<Long, List<ExecutionEvent>> byExecution,
        Map<Long, Map<String, String>> usageStatuses,
        Map<Long, List<ContextSourceRef>> sources,
        Map<String, ConnectorToolName> toolNames,
        CurrentUser viewer) {
        AgentExecution execution = branch.execution();
        Agent agent = agents.findById(execution.agentId()).orElse(null);
        boolean internal = InternalValuePolicy.visibleTo(viewer);
        List<ExecutionEvent> own = byExecution.getOrDefault(execution.id(), List.of());
        Map<Integer, String> delegatedNames = delegatedNames(own, branch.children());
        return new ExecutionNode(
            branch.truncated(),
            execution.id(),
            !internal || agent == null ? null : agent.code(),
            agent == null ? null : agent.name(),
            execution.status().name(),
            internal ? execution.provider() : null,
            internal ? execution.model() : null,
            internal ? execution.reasoningEffort() : null,
            !internal || execution.reasoningEffortSource() == null
                ? null
                : execution.reasoningEffortSource().name(),
            execution.modelTier(),
            internal ? execution.inputTokens() : null,
            internal ? execution.cachedInputTokens() : null,
            internal ? execution.outputTokens() : null,
            internal ? execution.totalTokens() : null,
            internal ? execution.estimatedCostMicros() : null,
            execution.latencyMs(),
            internal ? execution.requestReceivedAt() : null,
            internal ? execution.submittedAt() : null,
            internal ? execution.firstDeltaAt() : null,
            execution.startedAt(),
            internal ? execution.finishedAt() : null,
            internal ? sources.getOrDefault(execution.id(), List.of()) : null,
            own.stream()
                .filter(event -> internal || event.eventType() != ExecutionEventType.PROVIDER_SWITCHED)
                .map(event -> ExecutionEventView.from(
                    event,
                    viewer,
                    event.hermesSessionId() == null
                        ? null
                        : usageStatuses
                            .getOrDefault(execution.id(), Map.of())
                            .get(event.hermesSessionId()),
                    event.eventType().isTool() ? toolNames.get(event.toolName()) : null,
                    delegatedNames.get(event.sequence())))
                .toList(),
            branch.children().stream()
                .map(child -> node(child, byExecution, usageStatuses, sources, toolNames, viewer))
                .toList(),
            execution.delegationKey() != null);
    }


    /**
     * {@code agent_delegate} 도구 사건의 순번마다 맡은 에이전트 이름이다(docs/features/assistant-hub/spec.md 의 DECISION30).
     *
     * <p>시작과 완료를 차례로 짝지은 호출마다, 그 호출이 시작한 뒤 끝나기 전에 시작한 맡긴 자식 실행 가운데 아직 짝짓지 않은 첫 실행을 고른다.
     * 거절된 호출은 자식이 없어 이름이 없다. 사건 시각과 실행 시작 시각은 다른 쪽이 적으므로 앞뒤로 {@link #DELEGATE_SKEW} 를 둔다.
     */
    private Map<Integer, String> delegatedNames(List<ExecutionEvent> own, List<Branch> children) {
        List<AgentExecution> delegated = children.stream()
            .map(Branch::execution)
            .filter(child -> child.delegationKey() != null && child.startedAt() != null)
            .sorted(Comparator.comparing(AgentExecution::startedAt).thenComparing(AgentExecution::id))
            .collect(Collectors.toCollection(ArrayList::new));
        if (delegated.isEmpty()) {
            return Map.of();
        }
        List<ExecutionEvent[]> calls = new ArrayList<>();
        for (ExecutionEvent event : own) {
            if (!isDelegateTool(event.toolName()) || event.occurredAt() == null) {
                continue;
            }
            if (event.eventType() == ExecutionEventType.TOOL_STARTED) {
                calls.add(new ExecutionEvent[] {event, null});
            } else if (event.eventType() == ExecutionEventType.TOOL_COMPLETED) {
                calls.stream().filter(call -> call[1] == null).findFirst().ifPresent(call -> call[1] = event);
            }
        }
        Map<Integer, String> names = new HashMap<>();
        for (ExecutionEvent[] call : calls) {
            Instant from = call[0].occurredAt().minus(DELEGATE_SKEW);
            Instant until = call[1] == null ? Instant.MAX : call[1].occurredAt().plus(DELEGATE_SKEW);
            Optional<AgentExecution> child = delegated.stream()
                .filter(candidate -> !candidate.startedAt().isBefore(from) && !candidate.startedAt().isAfter(until))
                .findFirst();
            if (child.isEmpty()) {
                continue;
            }
            delegated.remove(child.get());
            Optional<String> name = agents.findById(child.get().agentId()).map(Agent::name);
            if (name.isPresent()) {
                names.put(call[0].sequence(), name.get());
                if (call[1] != null) {
                    names.put(call[1].sequence(), name.get());
                }
            }
        }
        return names;
    }


    /** Control Plane 의 맡기기 도구다. MCP 도구는 {@code mcp__<서버>__agent_delegate} 로 온다. */
    private static boolean isDelegateTool(String toolName) {
        return toolName != null && (toolName.equals(DELEGATE_TOOL) || toolName.endsWith("__" + DELEGATE_TOOL));
    }


    private Map<Long, Map<String, String>> usageStatuses(
        Set<Long> executionIds, Map<Long, List<ExecutionEvent>> byExecution) {
        Map<Long, Map<String, String>> statuses = new LinkedHashMap<>();
        for (SubagentUsageJob job : jobs.findByExecutionIdIn(executionIds)) {
            String status = "EXPIRED".equals(job.status()) ? "UNCONFIRMED" : "WAITING";
            statuses.computeIfAbsent(job.executionId(), ignored -> new LinkedHashMap<>())
                .put(job.childSessionId(), status);
        }
        for (List<ExecutionEvent> executionEvents : byExecution.values()) {
            for (ExecutionEvent event : executionEvents) {
                if (event.eventType() == ExecutionEventType.SUBAGENT_COMPLETED && event.hermesSessionId() != null) {
                    statuses.computeIfAbsent(event.executionId(), ignored -> new LinkedHashMap<>())
                        .put(event.hermesSessionId(), "RECORDED");
                }
            }
        }
        return statuses;
    }


    private static boolean isTruncatedSomewhere(Branch branch) {
        return branch.truncated() || branch.children().stream().anyMatch(ExecutionTreeService::isTruncatedSomewhere);
    }


    /** 없는 실행과 남의 실행을 같은 응답으로 숨긴다. */
    private static void requireOwner(Long ownerUserId, AgentExecution execution) {
        if (!Objects.equals(execution.userId(), ownerUserId)) {
            throw notFound();
        }
    }


    private static ApiException notFound() {
        return new ApiException(ErrorCode.EXECUTION_NOT_FOUND, "no such execution");
    }


    /** 루트를 찾아 올라간 결과다. 상한에서 멈췄으면 잘랐다고 알린다. */
    private record Ascent(AgentExecution root, boolean truncated) {
    }

    /** 응답으로 옮기기 전의 트리다. 사건을 한 번에 읽으려고 구조를 먼저 정한다. */
    private record Branch(AgentExecution execution, boolean truncated, List<Branch> children) {
    }
}
