package com.bifos.assistant.chat.turn.application.dto;

import com.bifos.assistant.chat.conversation.domain.ChatMessage;

/**
 * steer 말풍선의 상태와 받은 순간 돈 자식의 종류다. 화면이 상태 글과 안내 줄을 그린다(ADR-20261116 / run-steer).
 *
 * @param state {@code DELIVERED}, {@code CARRIED_OVER}, {@code UNCONFIRMED}, {@code NOT_DELIVERED}
 * @param child {@code SUBAGENT}, {@code DELEGATION}. 없으면 null
 */
public record SteerView(String state, String child) {

    /** steer 말풍선이 아니면 null 이다. */
    public static SteerView of(ChatMessage message) {
        if (message.steerState() == null) {
            return null;
        }
        return new SteerView(
            message.steerState().name(), message.steerChild() == null ? null : message.steerChild().name());
    }
}
