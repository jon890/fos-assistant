package com.bifos.assistant.usage.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionEvent;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실행 하나를 그 사건과 자식 실행까지 묶어 낸다.
 *
 * <p>어느 실행 번호를 주든 그 실행이 속한 나무의 뿌리부터 낸다. 화면이 자식 실행에서 들어와도 전체를
 * 보게 하기 위해서다.
 *
 * <p>나무를 데이터베이스에서 재귀로 만들지 않는다. {@code root_execution_id} 로 한 번에 읽어 메모리에서
 * 잇는다. 한 실행의 자식 수가 많아질 일이 없고, 재귀 질의는 읽기 어렵다.
 */
@Service
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

    private static final Logger log = LoggerFactory.getLogger(ExecutionTreeService.class);

    private final AgentExecutionRepository executions;
    private final ExecutionEventRepository events;
    private final AgentService agents;

    /**
     * 질의를 여럿 내므로 한 트랜잭션으로 묶는다.
     *
     * <p>뿌리를 찾고, 자손을 읽고, 사건을 읽고, 노드마다 에이전트를 읽는다. 따로 돌면 그 사이에 바뀐
     * 자료를 섞어 읽는다.
     */
    @Transactional(readOnly = true)
    public ExecutionTree of(CurrentUser user, Long executionId) {
        AgentExecution asked = executions.findById(executionId).orElseThrow(ExecutionTreeService::notFound);
        requireOwner(user, asked);

        Ascent ascent = climbToRoot(asked);
        AgentExecution root = ascent.root();
        // 자식이 남의 것일 수 없지만, 뿌리를 찾아 올라간 뒤에도 주인을 확인한다.
        requireOwner(user, root);

        List<AgentExecution> descendants = executions.findByRootExecutionId(root.id());
        Map<Long, List<AgentExecution>> byParent = byParent(descendants);
        Set<Long> used = new LinkedHashSet<>();
        Set<Long> cut = new LinkedHashSet<>();
        Branch rootBranch = branch(root, byParent, used, cut, 1);
        warnAboutUnreachable(root, descendants, used, cut);

        ExecutionNode rootNode = node(rootBranch, eventsOf(used));
        return new ExecutionTree(rootNode, ascent.truncated() || isTruncatedSomewhere(rootBranch));
    }

    /**
     * 뿌리를 찾는다.
     *
     * <p>{@code root_execution_id} 가 적혀 있으면 그것을 그대로 쓴다. 비어 있으면
     * {@code parent_execution_id} 를 따라 올라간다. 올라간 횟수가 상한을 넘으면 마지막으로 닿은 실행을
     * 뿌리로 삼고 잘랐다고 알린다. 잘린 것이 노드의 아래가 아니라 위쪽이라, 이때는 나무의 값만 참으로
     * 둔다.
     */
    private Ascent climbToRoot(AgentExecution asked) {
        if (asked.rootExecutionId() != null) {
            return new Ascent(
                    executions.findById(asked.rootExecutionId()).orElse(asked), false);
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

    /** 뿌리에 매달린 실행을 부모 번호로 묶는다. */
    private static Map<Long, List<AgentExecution>> byParent(List<AgentExecution> descendants) {
        return descendants.stream()
                .filter(execution -> execution.parentExecutionId() != null)
                .collect(
                        Collectors.groupingBy(
                                AgentExecution::parentExecutionId,
                                LinkedHashMap::new,
                                Collectors.toList()));
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
        List<AgentExecution> waiting =
                byParent.getOrDefault(execution.id(), List.of()).stream()
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
    private static void markCut(
            AgentExecution execution, Map<Long, List<AgentExecution>> byParent, Set<Long> cut) {
        if (!cut.add(execution.id())) {
            return;
        }
        byParent.getOrDefault(execution.id(), List.of())
                .forEach(child -> markCut(child, byParent, cut));
    }

    /**
     * 뿌리에서 닿지 않는 실행을 남긴다.
     *
     * <p>{@code root_execution_id} 로 읽어 왔지만 부모 사슬이 뿌리까지 닿지 않는 줄이다. 데이터가 어긋난
     * 것이고, 그것 때문에 응답이 끝나지 않으면 안 되므로 나무에 넣지 않는다.
     *
     * <p>상한 깊이에서 우리가 일부러 자른 가지는 여기서 뺀다. 원인이 달라서다. 그쪽은 노드의
     * {@code truncated} 가 화면에 알린다.
     */
    private static void warnAboutUnreachable(
            AgentExecution root, List<AgentExecution> descendants, Set<Long> used, Set<Long> cut) {
        List<Long> dropped =
                descendants.stream()
                        .map(AgentExecution::id)
                        .filter(id -> !used.contains(id) && !cut.contains(id))
                        .toList();
        if (!dropped.isEmpty()) {
            log.warn("뿌리에 닿지 않아 나무에서 뺀 실행이 있다 rootExecutionId={} executionIds={}",
                    root.id(), dropped);
        }
    }

    /** 나무에 담긴 실행들의 사건을 한 번에 읽어 실행 번호로 묶는다. */
    private Map<Long, List<ExecutionEvent>> eventsOf(Set<Long> executionIds) {
        if (executionIds.isEmpty()) {
            return Map.of();
        }
        return events.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(executionIds).stream()
                .collect(
                        Collectors.groupingBy(
                                ExecutionEvent::executionId, LinkedHashMap::new, Collectors.toList()));
    }

    private ExecutionNode node(Branch branch, Map<Long, List<ExecutionEvent>> byExecution) {
        AgentExecution execution = branch.execution();
        Agent agent = execution.agentId() == null ? null : agents.requireById(execution.agentId());
        return new ExecutionNode(
                branch.truncated(),
                execution.id(),
                agent == null ? null : agent.code(),
                agent == null ? null : agent.name(),
                execution.status().name(),
                execution.model(),
                execution.inputTokens(),
                execution.outputTokens(),
                execution.estimatedCostMicros(),
                execution.latencyMs(),
                execution.startedAt(),
                byExecution.getOrDefault(execution.id(), List.of()).stream()
                        .map(ExecutionEventView::from)
                        .toList(),
                branch.children().stream().map(child -> node(child, byExecution)).toList());
    }

    private static boolean isTruncatedSomewhere(Branch branch) {
        return branch.truncated()
                || branch.children().stream().anyMatch(ExecutionTreeService::isTruncatedSomewhere);
    }

    /** 없는 실행과 남의 실행을 같은 응답으로 숨긴다. */
    private static void requireOwner(CurrentUser user, AgentExecution execution) {
        if (!Objects.equals(execution.userId(), user.id())) {
            throw notFound();
        }
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.EXECUTION_NOT_FOUND, "no such execution");
    }

    /** 뿌리를 찾아 올라간 결과다. 상한에서 멈췄으면 잘랐다고 알린다. */
    private record Ascent(AgentExecution root, boolean truncated) {
    }

    /** 응답으로 옮기기 전의 나무다. 사건을 한 번에 읽으려고 구조를 먼저 정한다. */
    private record Branch(AgentExecution execution, boolean truncated, List<Branch> children) {
    }
}
