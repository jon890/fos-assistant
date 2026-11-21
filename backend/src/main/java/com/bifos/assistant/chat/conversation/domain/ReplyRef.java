package com.bifos.assistant.chat.conversation.domain;

import java.util.Objects;

/**
 * 사용자가 집어 답장한 것이다(스펙 chat-reference REQ1). 메시지 하나를 가리키고, 그 메시지의 사진이나 그림 한 장을 집었으면
 * 그 번호가 하나 찬다. 둘 다 비면 메시지 전체를 집은 것이다.
 *
 * @param messageId 집은 메시지 줄
 * @param attachmentId 사진 한 장을 집었으면 그 첨부. 아니면 null
 * @param artifactId 그림 한 장을 집었으면 그 그림 행. 아니면 null
 */
public record ReplyRef(Long messageId, Long attachmentId, Long artifactId) {

    public ReplyRef {
        Objects.requireNonNull(messageId, "messageId");
        if (attachmentId != null && artifactId != null) {
            throw new IllegalArgumentException("a reply picks one image at most");
        }
    }


    /** 사진이나 그림 한 장이 아니라 메시지 전체를 집었다. */
    public boolean wholeMessage() {
        return attachmentId == null && artifactId == null;
    }
}
