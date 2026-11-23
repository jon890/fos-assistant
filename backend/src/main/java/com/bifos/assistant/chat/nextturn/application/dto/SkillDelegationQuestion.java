package com.bifos.assistant.chat.nextturn.application.dto;

import com.bifos.assistant.chat.conversation.domain.Conversation;

/**
 * 스킬 맡기기의 질문을 저장한 결과다(docs/features/assistant-hub/spec.md 의 REQ10).
 *
 * @param conversation 질문을 저장한 대화
 * @param messageId 저장한 질문 메시지 번호
 * @param createdConversation 이 질문을 위해 새로 만든 대화다. 맡기기를 시작하지 못하면 대화도 지운다
 */
public record SkillDelegationQuestion(Conversation conversation, Long messageId, boolean createdConversation) {
}
