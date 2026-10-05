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
import com.bifos.assistant.connector.application.ConnectorActionService;
import com.bifos.assistant.connector.application.model.PendingApproval;
import com.bifos.assistant.shared.auth.CurrentUser;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 답을 기다리는 승인 줄을 나를 기다리는 카드의 후보로 낸다.
 *
 * <p>대화가 없거나 지워진 줄은 뺀다. 승인 카드는 대화 안에만 있어 눌러도 갈 곳이 없다. 기한이 지난 줄도 뺀다.
 */
@Component
@RequiredArgsConstructor
public class ApprovalCandidates implements AttentionCandidates {

    /** 승인 기한이 이만큼 남으면 {@code EXPIRES_SOON} 을 붙인다. */
    static final Duration EXPIRES_SOON_WITHIN = Duration.ofHours(6);

    private final ConnectorActionService actions;
    private final OwnConversations conversations;
    private final AgentService agents;

    @Override
    public Set<CardKey> cards() {
        return Set.of(CardKey.NEEDS_ME);
    }

    @Override
    public List<AttentionCandidate> read(CurrentUser user, Instant now) {
        List<PendingApproval> pending = actions.pendingApprovalsOf(user).stream()
                .filter(approval -> approval.conversationId() != null)
                .filter(approval ->
                        approval.expiresAt() != null && approval.expiresAt().isAfter(now))
                .toList();
        if (pending.isEmpty()) {
            return List.of();
        }
        Map<Long, Conversation> owned = conversations.activeOf(
                user, pending.stream().map(PendingApproval::conversationId).toList());
        List<PendingApproval> reachable = pending.stream()
                .filter(approval -> owned.containsKey(approval.conversationId()))
                .toList();
        Map<Long, Agent> agentById =
                agents.byIds(reachable.stream().map(PendingApproval::agentId).toList());
        return reachable.stream()
                .map(approval -> candidate(approval, owned.get(approval.conversationId()), agentById, now))
                .toList();
    }

    private static AttentionCandidate candidate(
            PendingApproval approval, Conversation conversation, Map<Long, Agent> agentById, Instant now) {
        Agent agent = agentById.get(approval.agentId());
        String itemKey = "connector_action:" + approval.actionId();
        boolean expiresSoon = !approval.expiresAt().isAfter(now.plus(EXPIRES_SOON_WITHIN));
        return new AttentionCandidate(
                CardKey.NEEDS_ME,
                itemKey,
                AttentionCandidates.stateKey(AttentionTrigger.APPROVAL_PENDING, "PENDING"),
                AttentionTrigger.APPROVAL_PENDING,
                false,
                true,
                expiresSoon ? List.of(AttentionSignal.EXPIRES_SOON) : List.of(),
                AttentionConfidence.CONTROL_PLANE,
                approval.title(),
                conversation.publicId(),
                agent == null ? null : agent.name(),
                approval.createdAt(),
                List.of(new AttentionSourceRef("APPROVAL_REQUEST", itemKey, approval.createdAt())),
                null,
                approval.actionId(),
                null);
    }
}
