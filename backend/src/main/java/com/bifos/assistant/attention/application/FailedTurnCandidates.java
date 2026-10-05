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
import com.bifos.assistant.chat.application.model.FailedDelivery;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.usage.application.AttentionExecutionQuery;
import com.bifos.assistant.usage.domain.AgentExecution;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 실패한 대화 turn 과 {@code FAILED} 로 남은 결과 전달을 실패 카드의 후보로 낸다. 대화마다 항목 하나다.
 *
 * <p>turn 은 같은 대화에서 그 뒤 성공한 turn 이 있으면 조회가 이미 뺀다. 대화마다 가장 최근 실패 하나만 낸다. 자동 turn 의
 * 실패는 turn 후보에서 빼고 결과 전달 실패가 맡는다. 예약 작업의 turn 은 지시를 사용자 글로 저장하므로 사용자가 보낸 turn 에
 * 든다. 같은 대화에 둘이 함께 있으면 한 항목으로 합치고 {@code trigger} 는 더 최근에 생긴 쪽이다. 결과 전달 묶음은 읽기만
 * 한다.
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
        Instant since = now.minus(properties.failureWindow());
        List<AgentExecution> failed = executions.unresolvedFailedTurns(user.id(), since);
        List<FailedDelivery> failedDeliveries = conversations.failedDeliveriesOf(user, since);
        Set<Long> conversationIds = new LinkedHashSet<>();
        failed.forEach(execution -> conversationIds.add(execution.conversationId()));
        failedDeliveries.forEach(delivery -> conversationIds.add(delivery.conversationId()));
        if (conversationIds.isEmpty()) {
            return List.of();
        }
        Map<Long, Conversation> owned = conversations.activeOf(user, conversationIds);
        Map<Long, AgentExecution> latestTurn = new LinkedHashMap<>();
        for (AgentExecution execution : failed) {
            Long conversationId = execution.conversationId();
            if (owned.containsKey(conversationId)
                    && !latestTurn.containsKey(conversationId)
                    && conversations.startedByUser(conversationId, execution.startedAt())) {
                latestTurn.put(conversationId, execution);
            }
        }
        Map<Long, FailedDelivery> deliveryByConversation = new LinkedHashMap<>();
        for (FailedDelivery delivery : failedDeliveries) {
            if (owned.containsKey(delivery.conversationId())) {
                deliveryByConversation.put(delivery.conversationId(), delivery);
            }
        }
        Set<Long> reported = new LinkedHashSet<>(latestTurn.keySet());
        reported.addAll(deliveryByConversation.keySet());
        Map<Long, Agent> agentById = agents.byIds(reported.stream()
                .map(conversationId -> owned.get(conversationId).agentId())
                .toList());
        return reported.stream()
                .map(conversationId -> candidate(
                        latestTurn.get(conversationId),
                        deliveryByConversation.get(conversationId),
                        owned.get(conversationId),
                        agentById,
                        now))
                .toList();
    }

    /** 실행과 결과 전달 가운데 하나 이상이 있다. 둘 다 있으면 합친다. */
    private static AttentionCandidate candidate(
            AgentExecution execution,
            FailedDelivery delivery,
            Conversation conversation,
            Map<Long, Agent> agentById,
            Instant now) {
        boolean deliveryIsLatest =
                execution == null || (delivery != null && delivery.updatedAt().isAfter(execution.finishedAt()));
        AttentionTrigger trigger =
                deliveryIsLatest ? AttentionTrigger.DELIVERY_FAILED : AttentionTrigger.EXECUTION_FAILED;
        // 지문의 앞 글자는 실행이 있으면 실행 쪽으로 고정한다. 더 최근인 쪽이 바뀌어도 숨긴 항목이 되살아나지 않는다.
        AttentionTrigger stateTrigger =
                execution != null ? AttentionTrigger.EXECUTION_FAILED : AttentionTrigger.DELIVERY_FAILED;
        List<AttentionSignal> signals = new ArrayList<>();
        List<AttentionSourceRef> sources = new ArrayList<>();
        StringBuilder material = new StringBuilder();
        if (execution != null) {
            signals.add(AttentionSignal.NOT_RETRIED);
            sources.add(new AttentionSourceRef("EXECUTION_STATE", "execution:" + execution.id(), now));
            material.append(execution.id());
        }
        if (delivery != null) {
            signals.add(AttentionSignal.DELIVERY_NOT_DONE);
            sources.add(new AttentionSourceRef(
                    "RESULT_DELIVERY", "result_delivery:" + delivery.deliveryId(), delivery.updatedAt()));
            if (execution != null) {
                material.append("|DELIVERY_FAILED|");
            }
            material.append(delivery.deliveryId()).append('|').append(delivery.attemptCount());
        }
        Instant at = deliveryIsLatest ? delivery.updatedAt() : execution.finishedAt();
        Agent agent = agentById.get(conversation.agentId());
        return new AttentionCandidate(
                CardKey.FAILURES,
                "conversation:" + conversation.publicId(),
                AttentionCandidates.stateKey(stateTrigger, material.toString()),
                trigger,
                false,
                true,
                signals,
                AttentionConfidence.CONTROL_PLANE,
                conversation.title(),
                conversation.publicId(),
                agent == null ? null : agent.name(),
                at,
                sources,
                null,
                null,
                null);
    }
}
