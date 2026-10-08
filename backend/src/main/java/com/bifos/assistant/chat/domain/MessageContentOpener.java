package com.bifos.assistant.chat.domain;

/**
 * 암호화해 저장한 메시지 본문을 푼다(ADR-20261008 / data-encryption).
 *
 * <p>메시지를 읽어 올 때 엔티티에 붙이고, 본문을 처음 꺼낼 때 한 번 부른다. 풀지 못하면
 * {@link ChatMessage#UNREADABLE_CONTENT} 를 돌려준다. 예외를 던지지 않는다.
 */
@FunctionalInterface
public interface MessageContentOpener {

    String open(ChatMessage message);
}
