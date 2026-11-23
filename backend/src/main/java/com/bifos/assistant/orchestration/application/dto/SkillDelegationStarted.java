package com.bifos.assistant.orchestration.application.dto;

import com.bifos.assistant.chat.conversation.domain.Conversation;

/**
 * 스킬 맡기기를 시작한 결과다. 실행 줄이 생긴 뒤에 돌려준다.
 *
 * @param conversation 질문을 남긴 대화
 * @param executionId 맡긴 실행 번호
 */
public record SkillDelegationStarted(Conversation conversation, Long executionId) {
}
