package com.bifos.assistant.chat.conversation.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * turn 이 도는 동안 사용자가 보낸 메시지 하나다(ADR-048). {@link PendingMessages} 가 대화마다 모아 둔다.
 *
 * @param id 대화 안에서 겹치지 않는 번호. 화면이 취소할 때 이 번호로 가리킨다
 */
public record PendingMessage(Long id, String content, Instant createdAt) {

    public PendingMessage {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
