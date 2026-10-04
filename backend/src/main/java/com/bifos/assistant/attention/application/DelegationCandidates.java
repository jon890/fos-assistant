package com.bifos.assistant.attention.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.attention.application.model.AttentionCandidate;
import com.bifos.assistant.attention.application.model.AttentionConfidence;
import com.bifos.assistant.attention.application.model.AttentionExecutionRef;
import com.bifos.assistant.attention.application.model.AttentionSignal;
import com.bifos.assistant.attention.application.model.AttentionSourceRef;
import com.bifos.assistant.attention.domain.type.AttentionTrigger;
import com.bifos.assistant.attention.domain.type.CardKey;
import com.bifos.assistant.chat.application.OwnConversations;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.usage.application.AttentionExecutionQuery;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 요청자의 위임 실행을 맡긴 일 카드의 후보로 낸다. 도는 것과 최근에 끝난 것이다.
 *
 * <p>대화가 없거나 지워진 실행은 뺀다. 도는 위임은 오래 돌면 {@code NOW} 가 된다.
 */
@Component
@RequiredArgsConstructor
public class DelegationCandidates implements AttentionCandidates {

    private final AttentionExecutionQuery executions;
    private final OwnConversations conversations;
    private final AgentService agents;
    private final AttentionProperties properties;

    @Override
    public Set<CardKey> cards() {
        return Set.of(CardKey.DELEGATED);
    }

    @Override
    public List<AttentionCandidate> read(CurrentUser user, Instant now) {
        List<AgentExecution> delegations =
                executions.delegations(user.id(), now.minus(properties.delegatedWindow())).stream()
                        .filter(execution -> execution.conversationId() != null)
                        .toList();
        if (delegations.isEmpty()) {
            return List.of();
        }
        Map<Long, Conversation> owned = conversations.activeOf(
                user, delegations.stream().map(AgentExecution::conversationId).toList());
        List<AgentExecution> reachable = delegations.stream()
                .filter(execution -> owned.containsKey(execution.conversationId()))
                .toList();
        Map<Long, Agent> agentById = agents.byIds(reachable.stream()
                .map(AgentExecution::agentId)
                .filter(Objects::nonNull)
                .toList());
        return reachable.stream()
                .map(execution -> candidate(execution, owned.get(execution.conversationId()), agentById, now))
                .toList();
    }

    private AttentionCandidate candidate(
            AgentExecution execution, Conversation conversation, Map<Long, Agent> agentById, Instant now) {
        boolean running = execution.status() == ExecutionStatus.RUNNING;
        AttentionTrigger trigger = running ? AttentionTrigger.DELEGATION_RUNNING : AttentionTrigger.DELEGATION_FINISHED;
        boolean longRunning = running
                && execution.startedAt().plus(properties.longRunningAfter()).isBefore(now);
        Agent agent = execution.agentId() == null ? null : agentById.get(execution.agentId());
        String itemKey = "execution:" + execution.id();
        return new AttentionCandidate(
                CardKey.DELEGATED,
                itemKey,
                AttentionCandidates.stateKey(trigger, execution.status().name()),
                trigger,
                false,
                longRunning,
                longRunning ? List.of(AttentionSignal.LONG_RUNNING) : List.of(),
                AttentionConfidence.CONTROL_PLANE,
                conversation.title(),
                conversation.publicId(),
                agent == null ? null : agent.name(),
                running ? execution.startedAt() : execution.finishedAt(),
                List.of(new AttentionSourceRef("EXECUTION_STATE", itemKey, now)),
                new AttentionExecutionRef(execution.id(), execution.status().name()),
                null,
                null);
    }
}
