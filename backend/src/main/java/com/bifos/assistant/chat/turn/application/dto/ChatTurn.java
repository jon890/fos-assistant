package com.bifos.assistant.chat.turn.application.dto;

import java.util.UUID;

/**
 * 대화 한 turn 의 결과다.
 *
 * @param conversationPublicId 사건과 응답에 싣는 대화의 공개 식별자
 * @param executionId 이 답을 만든 루트 실행
 * @param messageId 저장한 답 메시지의 번호. 스트림이 마지막에 이 번호를 보낸다
 */
public record ChatTurn(
    Long conversationId,
    UUID conversationPublicId,
    Long executionId,
    String assistantText,
    Long messageId,
    boolean cancelled) {
}
