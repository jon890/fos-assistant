package com.bifos.assistant.chat.conversation.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * turn 이 도는 동안 사용자가 보낸 메시지 하나다(ADR-048). {@link PendingMessages} 가 대화마다 모아 둔다.
 *
 * @param id 대화 안에서 겹치지 않는 번호. 화면이 취소할 때 이 번호로 가리킨다
 * @param messageId 이미 대화에 보인 steer 말풍선의 메시지 번호. 끼워 넣었다가 돌아오거나 되돌린 글이다. 다음 turn 에서 새 사용자
 * 메시지가 되지 않는다. 보통 글은 null 이다(ADR-20261116 / run-steer)
 * @param steering 도는 실행에 끼워 넣기를 기다리는 중이다. 그동안 보내지도 취소하지도 않는다
 */
public record PendingMessage(Long id, String content, Instant createdAt, Long messageId, boolean steering) {

    public PendingMessage {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(createdAt, "createdAt");
    }


    public PendingMessage(Long id, String content, Instant createdAt) {
        this(id, content, createdAt, null, false);
    }


    /** 말풍선이 이미 있는 글이다. */
    public boolean carried() {
        return messageId != null;
    }


    public PendingMessage withSteering(boolean value) {
        return new PendingMessage(id, content, createdAt, messageId, value);
    }
}
