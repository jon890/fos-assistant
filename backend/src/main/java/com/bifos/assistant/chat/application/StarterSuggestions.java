package com.bifos.assistant.chat.application;

import java.util.List;

/**
 * 한 사용자가 한 에이전트에서 받은 추천 질문이다.
 *
 * @param prompts 추천 질문. 보이는 차례대로다. {@link StarterStatus#READY} 가 아니면 빈 목록
 * @param status 지금 추천의 상태
 */
public record StarterSuggestions(List<String> prompts, StarterStatus status) {}
