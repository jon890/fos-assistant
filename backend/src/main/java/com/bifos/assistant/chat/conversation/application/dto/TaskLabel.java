package com.bifos.assistant.chat.conversation.application.dto;

import java.util.UUID;

/**
 * 대화 목록이 보이는 예약 작업의 이름이다.
 *
 * @param taskId 작업의 공개 식별자
 */
public record TaskLabel(UUID taskId, String title) {
}
