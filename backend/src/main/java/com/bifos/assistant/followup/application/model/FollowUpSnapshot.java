package com.bifos.assistant.followup.application.model;

import com.bifos.assistant.followup.domain.type.FollowUpStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * 서비스가 돌려주는 할 일 한 줄이다.
 *
 * @param conversationId 연결한 대화의 번호. 연결하지 않았으면 null
 * @param conversationPublicId 연결한 대화의 공개 식별자. 대화가 없거나 지워졌으면 null 이다. 눌러도 갈 곳이 없기 때문이다
 * @param proposed 에이전트가 제안한 줄인가
 */
public record FollowUpSnapshot(
        UUID publicId,
        Long conversationId,
        UUID conversationPublicId,
        String title,
        Instant dueAt,
        boolean waiting,
        FollowUpStatus status,
        boolean proposed,
        Instant createdAt,
        Instant updatedAt,
        Instant acceptedAt,
        Instant closedAt) {

    /** 제목을 빼고 번호와 상태만 낸다. 제목을 로그에 남기지 않는다. */
    @Override
    public String toString() {
        return "FollowUpSnapshot[publicId=" + publicId + ", status=" + status + "]";
    }
}
