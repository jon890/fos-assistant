package com.bifos.assistant.proactive.application.model;

/**
 * 점검 대화에서 이미 알린 발견의 주제 키와 원문 주소다. 되풀이 판정이 쓴다. 주제 키가 빈 발견은 만들지 않는다.
 */
public record AnnouncedKey(String topicKey, String sourceUrl) {}
