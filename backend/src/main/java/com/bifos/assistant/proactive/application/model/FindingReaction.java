package com.bifos.assistant.proactive.application.model;

import com.bifos.assistant.feedback.domain.type.FeedbackEventType;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;

/**
 * 살펴보기의 「새로 알릴 것」 발견에 사용자가 누른 단추다(ADR-20261008 / check-finding-reaction). 저장하지 않고, 판단 피드백 기록의 같은
 * 이름 사건으로 남긴다.
 */
public enum FindingReaction {
    /** 「받아들임」. 반응만 남기고 할 일과 Memory 를 만들지 않는다. */
    ACCEPTED(FeedbackEventType.ACCEPTED),
    /** 「나중에」. */
    POSTPONED(FeedbackEventType.POSTPONED),
    /** 「관심 없음」. */
    DISMISSED(FeedbackEventType.DISMISSED);

    private final FeedbackEventType eventType;

    FindingReaction(FeedbackEventType eventType) {
        this.eventType = eventType;
    }

    /** 이 반응으로 남기는 사건 종류다. */
    public FeedbackEventType eventType() {
        return eventType;
    }

    /** 사건 종류에 맞는 반응이다. 반응이 아닌 사건이면 null 이다. */
    public static FindingReaction of(FeedbackEventType type) {
        for (FindingReaction reaction : values()) {
            if (reaction.eventType == type) {
                return reaction;
            }
        }
        return null;
    }

    /** 요청 본문의 값을 읽는다. 비었거나 모르는 값이면 {@code VALIDATION_FAILED} 다. */
    public static FindingReaction parse(String value) {
        if (value != null) {
            for (FindingReaction reaction : values()) {
                if (reaction.name().equals(value)) {
                    return reaction;
                }
            }
        }
        throw new ApiException(ErrorCode.VALIDATION_FAILED, "unknown reaction");
    }
}
