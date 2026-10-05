package com.bifos.assistant.attention.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.attention.application.model.AttentionCandidate;
import com.bifos.assistant.attention.application.model.AttentionConfidence;
import com.bifos.assistant.attention.application.model.AttentionReport;
import com.bifos.assistant.attention.application.model.AttentionSourceRef;
import com.bifos.assistant.attention.domain.type.AttentionTrigger;
import com.bifos.assistant.attention.domain.type.CardKey;
import com.bifos.assistant.chat.application.OwnConversations;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.proactive.application.ProactiveReportSource;
import com.bifos.assistant.proactive.application.model.UnreadCheckReport;
import com.bifos.assistant.shared.auth.CurrentUser;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 열지 않은 다섯 칸 보고를 지금 화면의 다섯째 카드로 낸다. */
@Component
@RequiredArgsConstructor
public class ProactiveReportCandidates implements AttentionCandidates {

    private final ProactiveReportSource reports;
    private final OwnConversations conversations;
    private final AgentService agents;

    @Override
    public Set<CardKey> cards() {
        return Set.of(CardKey.REPORTS);
    }

    @Override
    public List<AttentionCandidate> read(CurrentUser user, Instant now) {
        List<UnreadCheckReport> unread = reports.unreadOf(user.id());
        if (unread.isEmpty()) {
            return List.of();
        }
        Map<Long, Conversation> owned =
                conversations.activeOf(user, unread.stream().map(UnreadCheckReport::conversationId).toList());
        Map<Long, Agent> agentsById = agents.byIds(unread.stream().map(UnreadCheckReport::agentId).toList());
        return unread.stream()
                .filter(check -> owned.containsKey(check.conversationId()))
                .map(check -> candidate(check, owned.get(check.conversationId()), agentsById.get(check.agentId())))
                .toList();
    }

    private static AttentionCandidate candidate(UnreadCheckReport check, Conversation conversation, Agent agent) {
        var report = check.report();
        String itemKey = "proactive_check:" + check.checkId();
        String agentName = agent == null ? null : agent.name();
        String title = agentName == null ? "새 보고" : agentName + "의 새 보고";
        AttentionReport view = new AttentionReport(
                check.checkId(),
                agent == null ? null : agent.code(),
                report.changed(),
                report.done(),
                report.evidence(),
                report.needsApproval(),
                report.next());
        return new AttentionCandidate(
                CardKey.REPORTS,
                itemKey,
                AttentionCandidates.stateKey(AttentionTrigger.PROACTIVE_REPORT_TRIGGER, check.checkId().toString()),
                AttentionTrigger.PROACTIVE_REPORT_TRIGGER,
                false,
                false,
                List.of(),
                AttentionConfidence.CONTROL_PLANE,
                title,
                conversation.publicId(),
                agentName,
                check.finishedAt() == null ? check.startedAt() : check.finishedAt(),
                List.of(new AttentionSourceRef("PROACTIVE_CHECK", itemKey, check.finishedAt())),
                null,
                null,
                null,
                view);
    }
}
