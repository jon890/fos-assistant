package com.bifos.assistant.followup.presentation;

import com.bifos.assistant.followup.application.model.FollowUpSnapshot;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 할 일 경로의 요청과 응답 모양이다. 계약은 {@code docs/backend/follow-up.md} 의 「API」 가 갖는다.
 *
 * <p>시각과 UUID 는 글로 받아 컨트롤러가 읽는다. 본문의 Jackson 변환 오류는 400 이 아니라 500 으로 끝나기 때문이다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class FollowUpDtos {

    /**
     * @param dueAt 시간대가 붙은 ISO-8601 시각. 없으면 기한이 없다
     * @param waiting null 이면 거짓이다
     * @param conversationId 연결할 대화의 공개 식별자. 없으면 연결하지 않는다
     */
    public record CreateFollowUpRequest(String title, String dueAt, Boolean waiting, String conversationId) {}

    /**
     * @param conversationId 연결한 대화의 공개 식별자. 연결하지 않았거나 대화를 지웠으면 null
     * @param proposed 에이전트가 제안했는가
     */
    public record FollowUpView(
            UUID id,
            String title,
            String status,
            Instant dueAt,
            boolean waiting,
            UUID conversationId,
            boolean proposed,
            Instant createdAt,
            Instant acceptedAt,
            Instant closedAt) {

        public static FollowUpView from(FollowUpSnapshot snapshot) {
            return new FollowUpView(
                    snapshot.publicId(),
                    snapshot.title(),
                    snapshot.status().name(),
                    snapshot.dueAt(),
                    snapshot.waiting(),
                    snapshot.conversationPublicId(),
                    snapshot.proposed(),
                    snapshot.createdAt(),
                    snapshot.acceptedAt(),
                    snapshot.closedAt());
        }
    }
}
