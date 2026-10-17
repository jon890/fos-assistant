package com.bifos.assistant.chat.turn.application.dto;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.attachment.domain.ChatAttachment;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import java.time.Instant;
import java.util.List;

/**
 * 이 turn 이 속한 대화와 에이전트다.
 * {@code attached} 는 판정을 통과해 이 메시지에 묶을 첨부이고 없으면 빈 목록이다.
 * {@code command} 는 이름을 확인한 스킬 커맨드이고 커맨드가 아니면 null 이다.
 * {@code created} 는 이 요청이 새로 만든 대화인지다.
 */
public record Routed(
    Conversation conversation,
    Agent agent,
    List<ChatAttachment> attached,
    SkillCommand command,
    Instant requestReceivedAt,
    boolean created) {
}
