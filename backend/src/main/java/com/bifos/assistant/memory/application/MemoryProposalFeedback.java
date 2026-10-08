package com.bifos.assistant.memory.application;

import com.bifos.assistant.feedback.application.model.FeedbackEntry;
import com.bifos.assistant.feedback.domain.type.FeedbackActor;
import com.bifos.assistant.feedback.domain.type.FeedbackEventType;
import com.bifos.assistant.feedback.domain.type.FeedbackSubjectType;
import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.shared.auth.CurrentUser;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** Memory 제안의 판단 피드백 사건을 만든다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class MemoryProposalFeedback {

    /**
     * 제안의 판단 피드백 사건이다. 열쇠는 지금 화면의 {@code itemKey} 와 같고, 판은 판 번호다. 제목과 본문은 담지 않는다. 대화는 제안한
     * 실행에서 기록기가 채운다.
     */
    static FeedbackEntry of(CurrentUser user, Memory memory, FeedbackEventType type, FeedbackActor actor, Instant now) {
        return FeedbackEntry.of(user.id(), FeedbackSubjectType.MEMORY, memory.id(), type, actor, now)
                .originExecution(memory.proposedByExecutionId())
                .version(Integer.toString(memory.revision()));
    }
}
