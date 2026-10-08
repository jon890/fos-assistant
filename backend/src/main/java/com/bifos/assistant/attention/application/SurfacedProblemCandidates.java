package com.bifos.assistant.attention.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.attention.application.model.AttentionCandidate;
import com.bifos.assistant.attention.application.model.AttentionConfidence;
import com.bifos.assistant.attention.application.model.AttentionProblem;
import com.bifos.assistant.attention.application.model.AttentionSourceRef;
import com.bifos.assistant.attention.domain.type.AttentionTrigger;
import com.bifos.assistant.attention.domain.type.CardKey;
import com.bifos.assistant.chat.application.OwnConversations;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.proactive.application.ProactiveLoopProperties;
import com.bifos.assistant.proactive.application.SurfacedProblems;
import com.bifos.assistant.proactive.application.model.SurfacedProblem;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.config.LiveProperties;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 매일 루프가 낸 먼저 다룰 문제를 나를 기다리는 카드의 후보로 낸다.
 *
 * <p>모델이 쓴 글이라 {@code NOW} 가 되지 못한다. 이 소스가 예외를 던지면 같은 카드의 승인 대기와 할 일까지 {@code UNAVAILABLE} 이 되므로,
 * 읽다 실패하면 로그만 남기고 빈 목록을 낸다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SurfacedProblemCandidates implements AttentionCandidates {

    private final SurfacedProblems surfacedProblems;
    private final OwnConversations conversations;
    private final AgentService agents;
    private final LiveProperties<ProactiveLoopProperties> properties;

    @Override
    public Set<CardKey> cards() {
        return Set.of(CardKey.NEEDS_ME);
    }

    @Override
    public List<AttentionCandidate> read(CurrentUser user, Instant now) {
        try {
            return candidates(user, now);
        } catch (RuntimeException ex) {
            log.warn(
                    "먼저 다룰 문제를 읽지 못했다 userId={} error={}",
                    user.id(),
                    ex.getClass().getSimpleName());
            return List.of();
        }
    }

    private List<AttentionCandidate> candidates(CurrentUser user, Instant now) {
        // 지운 점검 대화와 찾지 못한 에이전트를 거른 뒤에 상한을 건다. 정렬 순서는 openOf 가 낸 그대로다.
        List<SurfacedProblem> open = surfacedProblems.openOf(user.id(), now);
        if (open.isEmpty()) {
            return List.of();
        }
        Map<Long, Conversation> owned = conversations.activeOf(
                user, open.stream().map(SurfacedProblem::conversationId).toList());
        Map<Long, Agent> agentsById =
                agents.byIds(open.stream().map(SurfacedProblem::agentId).toList());
        return open.stream()
                .filter(problem ->
                        owned.containsKey(problem.conversationId()) && agentsById.containsKey(problem.agentId()))
                .limit(properties.current().surfaceMaxItems())
                .map(problem ->
                        candidate(problem, owned.get(problem.conversationId()), agentsById.get(problem.agentId())))
                .toList();
    }

    private static AttentionCandidate candidate(SurfacedProblem problem, Conversation conversation, Agent agent) {
        String itemKey = "autonomy_decision:" + problem.decisionId();
        return new AttentionCandidate(
                CardKey.NEEDS_ME,
                itemKey,
                AttentionCandidates.stateKey(
                        AttentionTrigger.PROBLEM_SURFACED, problem.decisionId().toString()),
                AttentionTrigger.PROBLEM_SURFACED,
                false,
                false,
                List.of(),
                AttentionConfidence.MODEL_INFERRED,
                problem.problem(),
                conversation.publicId(),
                agent.name(),
                problem.at(),
                List.of(new AttentionSourceRef("AUTONOMY_DECISION", itemKey, problem.at())),
                null,
                null,
                null,
                null,
                new AttentionProblem(problem.decisionId(), problem.level().name(), problem.action()));
    }
}
