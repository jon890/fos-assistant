package com.bifos.assistant.chat.conversation.application;

import com.bifos.assistant.chat.conversation.domain.ChatMessage;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ChatMessageContents;
import com.bifos.assistant.chat.conversation.infra.ChatMessageRepository;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.chat.conversation.infra.ConversationTitles;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이미 있는 메시지 한 줄이나 대화 제목 한 줄을 암호화하거나 평문으로 되돌린다(ADR-20261008 / data-encryption).
 *
 * <p>줄마다 새 트랜잭션에서 대화 줄을 쓰기 잠금으로 잡고(새 메시지 저장과 같은 순서) 대상 줄을 다시 읽어 조건을 다시 본다. 읽은 뒤 그
 * 사이에 사용자가 새로 쓴 줄이나 다른 차례가 먼저 고친 줄은 건너뛴다. 암호화한 줄은 같은 트랜잭션에서 풀어 봐서 평문과 다르면 되돌린다.
 * AAD 와 칸 규칙은 {@link ChatMessageContents} 와 {@link ConversationTitles} 가 정한 그대로 쓴다.
 *
 * <p>고쳤으면 참이고, 그 사이 바뀌었거나 풀지 못해 건너뛰었으면 거짓이다.
 */
@Component
@RequiredArgsConstructor
public class ChatPlaintextSealer {

    private final ConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final ChatMessageContents contents;
    private final ConversationTitles titles;


    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean sealMessage(Long messageId) {
        Conversation conversation = lockConversationOf(messageId);
        if (conversation == null) {
            return false;
        }
        ChatMessage message = messages.findByIdForUpdate(messageId).orElse(null);
        if (message == null || message.contentKeyId() != null || message.storedContent().isEmpty()) {
            return false;
        }
        String plain = message.storedContent();
        contents.seal(message, conversation.userId(), plain);
        messages.flush();
        if (!contents.opensTo(message, conversation.userId(), plain)) {
            throw new IllegalStateException("the sealed message does not open to the original");
        }
        return true;
    }


    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean unsealMessage(Long messageId) {
        Conversation conversation = lockConversationOf(messageId);
        if (conversation == null) {
            return false;
        }
        ChatMessage message = messages.findByIdForUpdate(messageId).orElse(null);
        if (message == null || message.contentKeyId() == null || !contents.unseal(message, conversation.userId())) {
            return false;
        }
        messages.flush();
        return true;
    }


    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean sealTitle(Long conversationId) {
        Conversation conversation = conversations.findByIdForMessageWrite(conversationId).orElse(null);
        if (conversation == null || conversation.titleKeyId() != null || conversation.storedTitle().isEmpty()) {
            return false;
        }
        String plain = conversation.storedTitle();
        titles.seal(conversation, plain);
        conversations.flush();
        if (!titles.opensTo(conversation, plain)) {
            throw new IllegalStateException("the sealed title does not open to the original");
        }
        return true;
    }


    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean unsealTitle(Long conversationId) {
        Conversation conversation = conversations.findByIdForMessageWrite(conversationId).orElse(null);
        if (conversation == null || conversation.titleKeyId() == null || !titles.unseal(conversation)) {
            return false;
        }
        conversations.flush();
        return true;
    }


    /**
     * 메시지가 속한 대화 줄을 쓰기 잠금으로 잡는다. 대화가 없거나 본문을 이미 지운 대화면 비어 있다.
     *
     * <p>지웠지만 아직 정리하지 않은 대화의 메시지는 대상이다. 정리 작업이 그 대화 줄을 먼저 잡으면 이 잠금이 기다리고, 정리가 끝난
     * 뒤에는 메시지가 없어 건너뛴다.
     */
    private Conversation lockConversationOf(Long messageId) {
        Long conversationId = messages.findConversationId(messageId).orElse(null);
        if (conversationId == null) {
            return null;
        }
        return conversations.findByIdForMessageWrite(conversationId)
            .filter(conversation -> conversation.purgedAt() == null)
            .orElse(null);
    }
}
