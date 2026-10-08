package com.bifos.assistant.chat.domain;

/**
 * 암호화해 저장한 메시지 본문을 푼다(ADR-20261008 / data-encryption).
 *
 * <p>메시지를 읽어 올 때 엔티티에 붙이고, 본문을 처음 꺼낼 때 한 번 부른다. key 가 없거나 암호문이 맞지 않아 풀지 못하면
 * {@link ChatMessage#UNREADABLE_CONTENT} 를 돌려준다. 데이터베이스에 닿지 못해 key 나 대화 주인을 읽지 못하면 그 예외가 그대로
 * 나간다. 일시적인 장애를 읽을 수 없는 메시지로 굳히지 않기 위해서다.
 */
@FunctionalInterface
public interface MessageContentOpener {

    String open(ChatMessage message);
}
