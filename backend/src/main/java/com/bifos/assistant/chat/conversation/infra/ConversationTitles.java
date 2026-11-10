package com.bifos.assistant.chat.conversation.infra;

import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.domain.ConversationTitleOpener;
import com.bifos.assistant.crypto.domain.SealedText;
import com.bifos.assistant.crypto.domain.TextCipher;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 대화 제목을 저장할 때 암호화하고 읽을 때 푼다(ADR-20261008 / data-encryption).
 *
 * <p>AAD 는 {@code conversation:<대화 번호>:title:user:<대화 주인>} 이다. 데이터베이스에서 암호문을 다른 줄로 옮기거나 대화의
 * 주인을 바꾸면 풀리지 않고 {@link Conversation#UNREADABLE_TITLE} 이 보인다. 대화 줄이 번호와 주인을 모두 갖고 있어 메시지처럼 주인을
 * 따로 읽지 않는다.
 *
 * <p>빈 제목은 감출 내용이 없어 평문 빈 글로 두고 key 번호를 비운다. 제목을 채우는 질의가 빈 글인지로 판정한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ConversationTitles implements ConversationTitleOpener {

    private final TextCipher cipher;


    /** 새 제목을 암호화해 저장하는가. 거짓이면 평문으로 저장한다 */
    public boolean sealing() {
        return cipher.enabled();
    }


    /**
     * 이미 줄 번호가 있는 대화의 제목을 암호화한다. 빈 제목은 암호화하지 않는다.
     *
     * @return 암호화가 꺼져 있거나 제목이 비어 있으면 빈 값이다. 부르는 쪽은 평문으로 저장한다
     */
    public Optional<SealedText> seal(Long conversationId, Long ownerUserId, String plain) {
        if (plain == null || plain.isEmpty()) {
            return Optional.empty();
        }
        return cipher.seal(ownerUserId, aad(conversationId, ownerUserId), plain);
    }


    /** 처음 저장할 대화의 제목을 암호화해 적는다. 줄 번호가 있어야 하므로 {@code persist} 뒤에 부른다. */
    public void seal(Conversation conversation, String plain) {
        SealedText sealed = seal(conversation.id(), conversation.userId(), plain)
            .orElseThrow(() -> new IllegalStateException("title encryption is not enabled"));
        conversation.sealTitle(sealed.content(), sealed.keyId());
    }


    @Override
    public String open(Conversation conversation) {
        return cipher.open(
            conversation.titleKeyId(),
            conversation.userId(),
            aad(conversation.id(), conversation.userId()),
            conversation.storedTitle())
            .orElseGet(() -> {
                log.warn("대화 제목을 풀지 못했다 conversationId={}", conversation.id());
                return Conversation.UNREADABLE_TITLE;
            });
    }


    private static String aad(Long conversationId, Long ownerUserId) {
        return "conversation:" + conversationId + ":title:user:" + ownerUserId;
    }
}
