package com.bifos.assistant.chat.reply.application.dto;

import java.util.Objects;

/**
 * 보내기 요청이 답장으로 집은 것이다. 아직 검사하지 않은 값이다(스펙 chat-reference REQ1).
 *
 * @param messageId 집은 메시지 줄
 * @param attachmentId 사진 한 장을 집었으면 그 첨부. 아니면 null
 * @param imagePath 그림 한 장을 집었으면 이력의 그림 경로. 아니면 null
 */
public record ReplyPick(Long messageId, Long attachmentId, String imagePath) {

    public ReplyPick {
        Objects.requireNonNull(messageId, "messageId");
    }
}
