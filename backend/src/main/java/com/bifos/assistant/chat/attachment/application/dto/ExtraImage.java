package com.bifos.assistant.chat.attachment.application.dto;

import com.bifos.assistant.chat.attachment.domain.ChatAttachment;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 이번 메시지의 사진 밖에서 실행 입력에 함께 넣을 이미지 하나다. 답장으로 집은 사진과 그림이다(스펙 chat-reference REQ7).
 * 둘 가운데 하나만 찬다.
 *
 * <p>그림 원본은 줄일 차례가 올 때 한 장씩 읽는다. 그림이 여럿인 답을 집어도 원본을 한꺼번에 메모리에 올리지 않는다.
 *
 * @param attachment 집은 사진. 그 첨부의 줄인 사본을 쓴다
 * @param original 집은 그림의 원본을 읽는 함수. 파일이 없으면 빈 값이다
 */
public record ExtraImage(ChatAttachment attachment, Supplier<Optional<byte[]>> original) {

    public ExtraImage {
        if ((attachment == null) == (original == null)) {
            throw new IllegalArgumentException("an extra image is an attachment or original bytes");
        }
    }


    public static ExtraImage ofAttachment(ChatAttachment attachment) {
        return new ExtraImage(attachment, null);
    }


    public static ExtraImage ofOriginal(Supplier<Optional<byte[]>> original) {
        return new ExtraImage(null, original);
    }


    public static ExtraImage ofBytes(byte[] original) {
        return ofOriginal(() -> Optional.of(original));
    }
}
