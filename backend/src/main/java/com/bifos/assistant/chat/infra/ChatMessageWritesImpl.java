package com.bifos.assistant.chat.infra;

import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;

/**
 * JpaRepository 저장 메서드의 기존 트랜잭션에서 대화 존재 확인부터 메시지 저장까지 대화 줄을 잠근다.
 *
 * <p>외래 키가 없는 메시지 표에 빈 작업 대화 삭제 뒤 늦게 온 메시지가 남지 않게 한다. 공유 잠금은 같은 트랜잭션의
 * 대화 갱신 때 잠금 승격끼리 막힐 수 있어 쓰기 잠금을 쓴다. 이미 숨긴 대화에 실행 결과를 남기는 동작은 유지한다.
 */
@RequiredArgsConstructor
public class ChatMessageWritesImpl implements ChatMessageWrites<ChatMessage> {

    private final EntityManager entityManager;
    private final ConversationRepository conversations;
    private final ChatMessageContents contents;

    @Override
    public <S extends ChatMessage> S save(S message) {
        Conversation conversation = lockConversation(message.conversationId());
        return persistOrMerge(message, conversation);
    }

    @Override
    public <S extends ChatMessage> S saveAndFlush(S message) {
        S saved = save(message);
        entityManager.flush();
        return saved;
    }

    @Override
    public <S extends ChatMessage> List<S> saveAll(Iterable<S> messages) {
        List<S> batch = new ArrayList<>();
        messages.forEach(batch::add);
        // 서로 반대 순서로 받은 묶음도 대화 잠금은 번호 순서로 얻는다. 반환 순서는 입력과 같다.
        Map<Long, Conversation> locked = new HashMap<>();
        batch.stream()
                .map(ChatMessage::conversationId)
                .distinct()
                .sorted()
                .forEach(id -> locked.put(id, lockConversation(id)));
        List<S> saved = new ArrayList<>(batch.size());
        batch.forEach(message -> saved.add(persistOrMerge(message, locked.get(message.conversationId()))));
        return saved;
    }

    @Override
    public <S extends ChatMessage> List<S> saveAllAndFlush(Iterable<S> messages) {
        List<S> saved = saveAll(messages);
        entityManager.flush();
        return saved;
    }

    /** 본문을 지운 대화에는 남기지 않는다. 늦게 끝난 실행의 답이 지운 대화에 다시 평문으로 쌓이지 않게 한다. */
    private Conversation lockConversation(Long conversationId) {
        return conversations
                .findByIdForMessageWrite(conversationId)
                .filter(conversation -> conversation.purgedAt() == null)
                .orElseThrow(() -> new ApiException(ErrorCode.CONVERSATION_NOT_FOUND, "대화를 찾을 수 없습니다"));
    }

    /**
     * 새 메시지는 본문을 빼고 넣은 뒤 줄 번호로 AAD 를 만들어 같은 트랜잭션에서 암호문을 적는다(ADR-20261008 / data-encryption).
     * 암호화가 꺼져 있으면 평문으로 넣는다. 이미 있는 메시지는 본문을 바꾸지 않으므로 그대로 합친다.
     */
    private <S extends ChatMessage> S persistOrMerge(S message, Conversation conversation) {
        if (message.id() == null) {
            if (!contents.sealing() || message.contentKeyId() != null) {
                entityManager.persist(message);
                return message;
            }
            String plain = message.detachPlainForSealing();
            entityManager.persist(message);
            if (message.id() == null) {
                entityManager.flush();
            }
            contents.seal(message, conversation.userId(), plain);
            return message;
        }
        return entityManager.merge(message);
    }
}
