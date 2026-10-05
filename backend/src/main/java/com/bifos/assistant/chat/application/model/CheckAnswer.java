package com.bifos.assistant.chat.application.model;

/**
 * 먼저 살펴보기 turn 이 성공했을 때 대화에 남길 글이다.
 *
 * @param text 남길 글
 * @param notice 참이면 {@code SYSTEM} 알림 줄로, 거짓이면 실행 번호가 붙은 {@code ASSISTANT} 답으로 남긴다
 * @param omit 참이면 대화 메시지와 알림을 남기지 않는다
 */
public record CheckAnswer(String text, boolean notice, boolean omit) {}
