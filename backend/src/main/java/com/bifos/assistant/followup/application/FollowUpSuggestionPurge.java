package com.bifos.assistant.followup.application;

import com.bifos.assistant.chat.purge.application.port.ConversationSuggestionPurge;
import com.bifos.assistant.followup.domain.FollowUp;
import com.bifos.assistant.followup.domain.type.FollowUpStatus;
import com.bifos.assistant.followup.infra.FollowUpRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 지운 대화에서 나온 제안을 지운다(ADR-20261101 / proactive-suggestion). 받아들이지 않았거나 거절한 제안 줄은 본문과 함께 지운다. 받아들여
 * 할 일이 된 줄은 사용자의 할 일이라 남기고 제안 본문만 비운다. 제안이 아닌 줄(사람이 더했거나 대화 중 제안한 할 일. 중복 열쇠가 없다)은 건드리지 않는다.
 */
@Component
@RequiredArgsConstructor
class FollowUpSuggestionPurge implements ConversationSuggestionPurge {

    private final FollowUpRepository followUps;


    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int eraseSuggestions(Long conversationId) {
        List<FollowUp> rows = followUps.findByConversationIdAndDedupeKeyIsNotNull(conversationId);
        for (FollowUp row : rows) {
            if (row.status() == FollowUpStatus.PROPOSED || row.status() == FollowUpStatus.REJECTED) {
                followUps.delete(row);
            } else {
                row.clearDetail();
            }
        }
        followUps.flush();
        return rows.size();
    }
}
