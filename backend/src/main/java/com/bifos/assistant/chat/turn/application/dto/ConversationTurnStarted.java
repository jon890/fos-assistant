package com.bifos.assistant.chat.turn.application.dto;

/**
 * 대화의 turn 하나가 실행 줄을 만들고 Hermes 에 보내기 직전이다. turn 을 연 트랜잭션이 있으면 그 안에서 낸다.
 *
 * <p>화면 넘기기 카드처럼 「다음 turn 이 시작되면 닫는」 것이 이 사건을 듣는다. {@code chat} 은 듣는 쪽을 모른다.
 *
 * @param conversationId 그 turn 의 대화 번호
 */
public record ConversationTurnStarted(Long conversationId) {
}
