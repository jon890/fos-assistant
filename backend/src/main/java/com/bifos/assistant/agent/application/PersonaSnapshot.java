package com.bifos.assistant.agent.application;

/**
 * 어느 시점의 페르소나 본문과 그 지문이다.
 *
 * <p>{@code editable} 은 그 본문의 성질이 아니라 그것을 물어본 사람의 권한이다. 같은 에이전트라도
 * 주인과 다른 사용자가 다른 값을 받는다.
 *
 * @param body 지금 본문. 파일이 없으면 빈 문자열
 * @param bodyHash 그 본문의 지문. 빈 본문에도 값이 있다
 * @param editable 물어본 사람이 이 본문을 고칠 수 있는가
 */
public record PersonaSnapshot(String body, String bodyHash, boolean editable) {
}
