package com.bifos.assistant.chat.application.model;

/**
 * 자동 turn 으로 전할 결과 하나다.
 *
 * @param key 그 결과를 낸 쪽이 전했다고 적을 때 쓰는 이름
 * @param notice 대화에 남기는 알림 줄의 글
 * @param input Hermes 입력에 넣을 단락. 본문은 여기에만 싣고 알림 줄에는 싣지 않는다
 */
public record AutoTurnResult(String key, String notice, String input) {}
