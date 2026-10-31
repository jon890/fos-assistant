package com.bifos.assistant.chat.conversation.application.dto;

import com.bifos.assistant.feedback.domain.type.FeedbackEventType;

/** 「참고한 기억」 의 항목 단추다(ADR-20261015 / memory-maintenance). 기억 내용을 바꾸지 않고 판단 피드백 사건 하나만 남긴다. */
public enum MemoryUseReaction {
    /** 「관련 없음」. 이 답과 관련 없는 기억이 실렸다. */
    IRRELEVANT(FeedbackEventType.MARKED_IRRELEVANT),
    /** 「틀림」. 기억 내용이 틀렸다. 고치는 것은 사람이 기억 화면에서 한다. */
    WRONG(FeedbackEventType.MARKED_WRONG);


    private final FeedbackEventType eventType;


    MemoryUseReaction(FeedbackEventType eventType) {
        this.eventType = eventType;
    }


    public FeedbackEventType eventType() {
        return eventType;
    }
}
