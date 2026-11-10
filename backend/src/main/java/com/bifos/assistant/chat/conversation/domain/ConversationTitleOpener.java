package com.bifos.assistant.chat.conversation.domain;

/**
 * 암호화해 저장한 대화 제목을 푼다(ADR-20261008 / data-encryption).
 *
 * <p>대화를 읽어 올 때 엔티티에 붙이고, 제목을 처음 꺼낼 때 한 번 부른다. key 가 없거나 암호문이 맞지 않아 풀지 못하면
 * {@link Conversation#UNREADABLE_TITLE} 를 돌려준다.
 */
@FunctionalInterface
public interface ConversationTitleOpener {

    String open(Conversation conversation);
}
