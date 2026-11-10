package com.bifos.assistant.chat.conversation.infra;

import com.bifos.assistant.chat.conversation.domain.Conversation;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;

/**
 * 새 대화는 제목을 빼고 넣은 뒤 줄 번호로 AAD 를 만들어 같은 트랜잭션에서 암호문을 적는다(ADR-20261008 / data-encryption).
 * 암호화가 꺼져 있거나 제목이 비어 있으면 그대로 넣는다. 이미 있는 대화는 제목을 바꾸지 않으므로 그대로 합친다.
 */
@RequiredArgsConstructor
public class ConversationWritesImpl implements ConversationWrites<Conversation> {

    private final EntityManager entityManager;
    private final ConversationTitles titles;


    @Override
    public <S extends Conversation> S save(S conversation) {
        if (conversation.id() != null) {
            return entityManager.merge(conversation);
        }
        String title = conversation.storedTitle();
        if (!titles.sealing() || conversation.titleKeyId() != null || title == null || title.isEmpty()) {
            entityManager.persist(conversation);
            return conversation;
        }
        String plain = conversation.detachPlainTitleForSealing();
        entityManager.persist(conversation);
        if (conversation.id() == null) {
            entityManager.flush();
        }
        titles.seal(conversation, plain);
        return conversation;
    }


    @Override
    public <S extends Conversation> S saveAndFlush(S conversation) {
        S saved = save(conversation);
        entityManager.flush();
        return saved;
    }


    @Override
    public <S extends Conversation> List<S> saveAll(Iterable<S> conversations) {
        List<S> saved = new ArrayList<>();
        conversations.forEach(conversation -> saved.add(save(conversation)));
        return saved;
    }


    @Override
    public <S extends Conversation> List<S> saveAllAndFlush(Iterable<S> conversations) {
        List<S> saved = saveAll(conversations);
        entityManager.flush();
        return saved;
    }
}
