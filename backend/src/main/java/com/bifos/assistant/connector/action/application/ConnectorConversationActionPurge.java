package com.bifos.assistant.connector.action.application;

import com.bifos.assistant.chat.attachment.application.ChatContentMutationCoordinator;
import com.bifos.assistant.chat.application.ConversationActionPurge;
import com.bifos.assistant.connector.action.domain.type.ActionStatus;
import com.bifos.assistant.connector.connection.domain.type.ToolRisk;
import com.bifos.assistant.connector.action.infra.ConnectorActionExecutionRepository;
import com.bifos.assistant.connector.action.infra.ConnectorActionRepository;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 감사 기록을 남기고 실행·복구·하루 상한 계산에 더 쓰지 않는 승인 본문만 지운다. */
@Component
@RequiredArgsConstructor
class ConnectorConversationActionPurge implements ConversationActionPurge {
    private static final Set<ActionStatus> OPEN = Set.of(ActionStatus.PENDING, ActionStatus.EXECUTING, ActionStatus.UNKNOWN);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final ConnectorActionRepository actions;
    private final ConnectorActionExecutionRepository contents;
    private final ChatContentMutationCoordinator mutations;


    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int eraseBodies(Long conversationId, Instant now) {
        mutations.requireParticipant(conversationId);
        List<Long> ids = actions.findErasableBodyIds(conversationId, OPEN, ToolRisk.FINANCIAL, ActionStatus.SUCCEEDED, dayStart(now));
        if (ids.isEmpty()) {
            return 0;
        }
        int erased = actions.eraseBodiesOf(ids, now);
        contents.eraseBodiesOf(ids);
        return erased;
    }


    @Override
    @Transactional(readOnly = true)
    public List<Long> findPurgedCandidates(List<Long> skipped, int limit, Instant now) {
        return actions.findPurgedConversationsWithBodies(skipped, OPEN, ToolRisk.FINANCIAL, ActionStatus.SUCCEEDED, dayStart(now),
            PageRequest.of(0, limit));
    }


    private static Instant dayStart(Instant now) {
        return now.atZone(KST).toLocalDate().atStartOfDay(KST).toInstant();
    }
}
