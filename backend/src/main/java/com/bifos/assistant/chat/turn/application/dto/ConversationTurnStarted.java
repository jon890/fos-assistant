package com.bifos.assistant.chat.turn.application.dto;

/**
 * 대화의 turn 하나가 실행 줄을 만들고 Hermes 에 보내기 직전이다. turn 을 연 트랜잭션이 있으면 그 안에서 낸다.
 *
 * <p>화면 넘기기 카드처럼 「사람이 다음 차례를 시작하면 닫는」 것이 이 사건을 듣는다. 맡긴 일의 결과를 전하는 자동 turn 은
 * 사람이 한 차례가 아니므로 듣는 쪽이 {@code byUser} 로 가른다. {@code chat} 은 듣는 쪽을 모른다.
 *
 * @param conversationId 그 turn 의 대화 번호
 * @param byUser 사람이 보낸 turn 이다(새 질문, 대기 메시지 합침, 다시 생성). 결과 전달, 예약 작업, 먼저 살펴보기는 거짓이다
 */
public record ConversationTurnStarted(Long conversationId, boolean byUser) {
}
