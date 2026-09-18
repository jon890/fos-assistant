package com.bifos.assistant.chat.application;

/**
 * 대화 한 turn 의 결과다.
 *
 * @param executionId 이 답을 만든 실행. 흐름으로 돈 turn 에서는 그 나무의 뿌리다
 * @param messageId 저장한 답 메시지의 번호. 스트림이 마지막에 이 번호를 보낸다
 */
public record ChatTurn(Long conversationId, Long executionId, String assistantText, Long messageId) {
}
