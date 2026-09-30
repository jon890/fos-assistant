package com.bifos.assistant.orchestration.application;

import com.bifos.assistant.usage.domain.AgentExecution;

/**
 * {@code agent_stop} 한 번의 결과다.
 *
 * @param execution 멈추기를 마친 뒤 다시 읽은 실행 줄
 * @param stopRequested 이번 호출이 이 프로세스의 중지 표시를 켰거나 Hermes 에 중지를 보냈으면 참이다. 이미 끝난 실행,
 *     run 번호가 없어 보낼 곳이 없는 끊긴 실행, 중지를 보내지 못한 끊긴 실행은 거짓이다
 */
public record DelegationStop(AgentExecution execution, boolean stopRequested) {
}
