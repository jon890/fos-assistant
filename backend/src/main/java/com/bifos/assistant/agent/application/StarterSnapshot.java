package com.bifos.assistant.agent.application;

import java.util.List;

/**
 * 어느 에이전트의 한 줄 소개와 추천 질문이다.
 *
 * <p>{@code editable} 은 그 값의 성질이 아니라 그것을 물어본 사람의 권한이다. 같은 에이전트라도 주인과
 * 다른 사용자가 다른 값을 받는다.
 *
 * @param tagline 한 줄 소개. 비어 있으면 {@code null}
 * @param starterPrompts 추천 질문. 보이는 차례대로다. 없으면 빈 목록
 * @param editable 물어본 사람이 이것을 고칠 수 있는가
 */
public record StarterSnapshot(String tagline, List<String> starterPrompts, boolean editable) {
}
