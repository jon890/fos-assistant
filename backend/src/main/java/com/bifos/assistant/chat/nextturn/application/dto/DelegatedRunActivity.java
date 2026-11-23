package com.bifos.assistant.chat.nextturn.application.dto;

import com.bifos.assistant.runtime.application.dto.RunEvent;

/**
 * 다른 에이전트에게 맡긴 실행의 도구 사건 하나다. 대화 화면의 도는 답에 맡은 에이전트의 도구 줄로 그린다(스펙 assistant-hub REQ3).
 *
 * <p>내는 쪽은 받는 쪽을 알지 않는다. 받는 쪽이 대화의 turn 이 바로 맡긴 위임인지 다시 읽어 정한다. 도구 내용은 스트림을 읽을 때 이미
 * 연결 도구 범위로 가렸다.
 *
 * @param conversationId 맡긴 실행의 대화
 * @param rootExecutionId 맡긴 실행이 속한 트리의 루트
 * @param executionId 맡긴 실행
 * @param event Hermes 사건 스트림의 도구 사건
 */
public record DelegatedRunActivity(Long conversationId, Long rootExecutionId, Long executionId, RunEvent event) {
}
