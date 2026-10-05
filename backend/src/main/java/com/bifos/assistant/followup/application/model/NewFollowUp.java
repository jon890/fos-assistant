package com.bifos.assistant.followup.application.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 사람이 직접 더하는 할 일이다.
 *
 * @param dueAt 기한. 없으면 null
 * @param conversationId 연결할 대화의 공개 식별자. 고르지 않았으면 null
 */
public record NewFollowUp(String title, Instant dueAt, boolean waiting, UUID conversationId) {

    /** 제목을 빼고 낸다. 제목을 로그에 남기지 않는다. */
    @Override
    public String toString() {
        return "NewFollowUp[dueAt=" + dueAt + ", waiting=" + waiting + ", conversationId=" + conversationId + "]";
    }
}
