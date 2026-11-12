package com.bifos.assistant.chat.turn.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.application.dto.AgentLabel;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.conversation.domain.ChatMessage;
import com.bifos.assistant.chat.conversation.domain.type.MessageRole;
import com.bifos.assistant.chat.delivery.application.ResultDeliveryRecorder;
import com.bifos.assistant.chat.turn.application.dto.DelegationLine;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 대화 안에 보이는 맡긴 줄과 결과 도착 줄을 읽는다(docs/features/agent-team/design.md 의 「PR2 협업 보이기」).
 *
 * <p>맡긴 줄로 보일 위임은 대화의 turn(루트 실행)이 바로 맡긴 실행이다. 맡은 에이전트가 다시 맡긴 손자는 실행 트리에서 본다.
 * 대화 하나를 열 때 질의가 답 수만큼 늘지 않게 목록으로 한 번에 읽는다.
 */
@Component
@RequiredArgsConstructor
public class DelegationLines {
    private final AgentExecutionRepository executions;
    private final ResultDeliveryRecorder deliveries;
    private final AgentService agents;


    /**
     * 답 줄의 실행 번호마다 그 실행이 바로 맡긴 위임이다. 실행 번호순이다. 맡긴 것이 없는 답은 빠진다.
     */
    public Map<Long, List<DelegationLine>> byAnswer(List<ChatMessage> history) {
        List<Long> answerExecutions = history.stream()
            .filter(message -> message.role() == MessageRole.ASSISTANT)
            .map(ChatMessage::executionId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (answerExecutions.isEmpty()) {
            return Map.of();
        }
        List<AgentExecution> children = executions
            .findByParentExecutionIdInAndDelegationKeyIsNotNullOrderByIdAsc(answerExecutions);
        Map<Long, Agent> labels = agentsOf(children);
        return children.stream()
            .collect(Collectors.groupingBy(
                AgentExecution::parentExecutionId,
                LinkedHashMap::new,
                Collectors.mapping(child -> lineOf(child, labels), Collectors.toList())));
    }


    /**
     * 위임 결과 알림 줄의 메시지 번호마다 그 줄이 전한 결과다. 묶음의 항목 순서다.
     *
     * <p>그 사용자와 그 대화의 실행만 싣는다. 지워졌거나 남의 실행 번호는 뺀다.
     *
     * @param conversationId 주인인 것을 이미 확인한 대화의 번호
     */
    public Map<Long, List<DelegationLine>> byResultNotice(CurrentUser user, Long conversationId) {
        Map<Long, List<Long>> byNotice = deliveries.delegationResultsByNotice(conversationId);
        if (byNotice.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = byNotice.values().stream().flatMap(Collection::stream).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, AgentExecution> found = executions.findAllById(ids).stream()
            .filter(execution -> user.id().equals(execution.userId())
                && conversationId.equals(execution.conversationId()))
            .collect(Collectors.toMap(AgentExecution::id, Function.identity()));
        Map<Long, Agent> labels = agentsOf(found.values());
        Map<Long, List<DelegationLine>> lines = new HashMap<>();
        byNotice.forEach((noticeId, executionIds) -> {
            List<DelegationLine> results = executionIds.stream()
                .map(found::get)
                .filter(Objects::nonNull)
                .map(execution -> lineOf(execution, labels))
                .toList();
            if (!results.isEmpty()) {
                lines.put(noticeId, results);
            }
        });
        return lines;
    }


    /**
     * 실행 하나의 맡긴 줄이다. 대화의 turn 이 바로 맡긴 위임이 아니면(위임이 아닌 실행, 손자 위임, 대화가 없는 실행) 비어 있다.
     */
    public Optional<DelegationLine> directDelegation(Long executionId) {
        return executions.findById(executionId)
            .filter(DelegationLines::isDirectDelegation)
            .map(execution -> lineOf(execution, agentsOf(List.of(execution))));
    }


    /** 위임 실행이고 부모가 그 트리의 루트다. 곧 사용자 대화의 turn 이 바로 맡겼다. */
    static boolean isDirectDelegation(AgentExecution execution) {
        return execution.delegationKey() != null
            && execution.conversationId() != null
            && execution.parentExecutionId() != null
            && execution.parentExecutionId().equals(execution.rootExecutionId());
    }


    /** 에이전트 번호가 null 인 실행도 {@code get} 으로 찾으므로, null 키를 받는 {@link AgentService#byIds} 의 맵을 쓴다. */
    private Map<Long, Agent> agentsOf(Collection<AgentExecution> found) {
        return agents.byIds(found.stream().map(AgentExecution::agentId).toList());
    }


    private static DelegationLine lineOf(AgentExecution execution, Map<Long, Agent> labels) {
        return new DelegationLine(
            execution.id(),
            execution.parentExecutionId(),
            execution.status(),
            AgentLabel.of(labels.get(execution.agentId())));
    }
}
