package com.bifos.assistant.chat.attachment.application.dto;

import com.bifos.assistant.chat.attachment.domain.ChatAttachment;

/**
 * 이번 메시지의 사진 밖에서 실행 입력에 함께 넣을 이미지 하나다. 답장으로 집은 사진과 그림이다(스펙 chat-reference REQ7).
 * 둘 가운데 하나만 찬다.
 *
 * @param attachment 집은 사진. 그 첨부의 줄인 사본을 쓴다
 * @param original 집은 그림의 원본 바이트. 디코딩 차례를 얻어 줄인다
 */
public record ExtraImage(ChatAttachment attachment, byte[] original) {

    public ExtraImage {
        if ((attachment == null) == (original == null)) {
            throw new IllegalArgumentException("an extra image is an attachment or original bytes");
        }
    }


    public static ExtraImage ofAttachment(ChatAttachment attachment) {
        return new ExtraImage(attachment, null);
    }


    public static ExtraImage ofBytes(byte[] original) {
        return new ExtraImage(null, original);
    }
}
