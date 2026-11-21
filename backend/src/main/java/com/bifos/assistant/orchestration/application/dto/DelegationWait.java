package com.bifos.assistant.orchestration.application.dto;

import com.bifos.assistant.usage.execution.domain.AgentExecution;

/**
 * 대화 turn 의 {@code agent_delegate} 가 맡긴 실행을 기다린 결과다(ADR-20261121 / delegate-wait).
 *
 * @param execution 기다린 뒤 다시 읽은 실행 줄. 줄을 찾지 못했으면 null
 * @param deliverable 끝난 결과를 이 도구 호출이 부모에게 전해도 되는가. 끝났고 루트 turn 에 중지 표시가 서지 않았을 때만 참이다.
 * 거짓이면 결과는 지금처럼 다음 답이 전한다(ADR-040)
 * @param waited 기다림의 대상이었는가. 손자 위임, 먼저 살펴보기, 상한 0 이면 거짓이고 부르는 쪽은 지금 응답을 그대로 준다.
 * 같은 답에서 예산을 다 쓴 맡김은 기다리지 않아도 참이다
 */
public record DelegationWait(AgentExecution execution, boolean deliverable, boolean waited) {
}
