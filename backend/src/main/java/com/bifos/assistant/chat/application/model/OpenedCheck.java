package com.bifos.assistant.chat.application.model;

import com.bifos.assistant.chat.domain.Conversation;

/**
 * 찾거나 만든 점검 대화다.
 *
 * @param created 이번에 새로 만들었으면 참이다. 시작이 거절되면 부르는 쪽이 이 대화를 지운다
 */
public record OpenedCheck(Conversation conversation, boolean created) {}
