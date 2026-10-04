package com.bifos.assistant.attention.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.attention.application.model.AttentionCandidate;
import com.bifos.assistant.attention.application.model.AttentionConfidence;
import com.bifos.assistant.attention.application.model.AttentionSignal;
import com.bifos.assistant.attention.application.model.AttentionSourceRef;
import com.bifos.assistant.attention.domain.type.AttentionTrigger;
import com.bifos.assistant.attention.domain.type.CardKey;
import com.bifos.assistant.chat.application.OwnConversations;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.usage.application.AttentionExecutionQuery;
import com.bifos.assistant.usage.domain.AgentExecution;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 사용자가 보낸 대화 turn 의 실패를 실패 카드의 후보로 낸다.
 *
 * <p>같은 대화에서 그 뒤 성공한 turn 이 있으면 조회가 이미 뺀다. 대화마다 가장 최근 실패 하나만 낸다. 자동 turn 의 실패는 결과
 * 전달 실패가 맡으므로 뺀다. 예약 작업의 turn 은 지시를 사용자 글로 저장하므로 사용자가 보낸 turn 에 든다.
 */
@Component
@RequiredArgsConstructor
public class FailedTurnCandidates implements AttentionCandidates {

    private final AttentionExecutionQuery executions;
    private final OwnConversations conversations;
    private final AgentService agents;
    private final AttentionProperties properties;

    @Override
    public Set<CardKey> cards() {
        return Set.of(CardKey.FAILURES);
    }

    @Override
    public List<AttentionCandidate> read(CurrentUser user, Instant now) {
        List<AgentExecution> failed =
                executions.unresolvedFailedTurns(user.id(), now.minus(properties.failureWindow()));
        if (failed.isEmpty()) {
            return List.of();
        }
        Map<Long, Conversation> owned = conversations.activeOf(
                user, failed.stream().map(AgentExecution::conversationId).toList());
        Set<Long> seen = new HashSet<>();
        List<AgentExecution> latest = new ArrayList<>();
        for (AgentExecution execution : failed) {
            Long conversationId = execution.conversationId();
            if (!owned.containsKey(conversationId) || seen.contains(conversationId)) {
                continue;
            }
            if (conversations.startedByUser(conversationId, execution.startedAt())) {
                seen.add(conversationId);
                latest.add(execution);
            }
        }
        Map<Long, Agent> agentById = agents.byIds(latest.stream()
                .map(execution -> owned.get(execution.conversationId()).agentId())
                .toList());
        return latest.stream()
                .map(execution -> candidate(execution, owned.get(execution.conversationId()), agentById, now))
                .toList();
    }

    private static AttentionCandidate candidate(
            AgentExecution execution, Conversation conversation, Map<Long, Agent> agentById, Instant now) {
        Agent agent = agentById.get(conversation.agentId());
        return new AttentionCandidate(
                CardKey.FAILURES,
                "conversation:" + conversation.publicId(),
                AttentionCandidates.stateKey(AttentionTrigger.EXECUTION_FAILED, String.valueOf(execution.id())),
                AttentionTrigger.EXECUTION_FAILED,
                false,
                true,
                List.of(AttentionSignal.NOT_RETRIED),
                AttentionConfidence.CONTROL_PLANE,
                conversation.title(),
                conversation.publicId(),
                agent == null ? null : agent.name(),
                execution.finishedAt(),
                List.of(new AttentionSourceRef("EXECUTION_STATE", "execution:" + execution.id(), now)),
                null,
                null,
                null);
    }
}
