package com.bifos.assistant.orchestration.application;

import com.bifos.assistant.orchestration.domain.ChildResult;
import com.bifos.assistant.usage.domain.AgentExecution;

/**
 * 돌린 결과다.
 *
 * @param execution 남긴 실행 줄. 부르는 쪽이 이것을 다음 단계의 부모로 쓴다
 * @param result 성공 여부와 답
 * @param sessionId Hermes 가 알려 준 session. 대화를 이어 가려면 부르는 쪽이 기억한다
 */
public record AgentRun(AgentExecution execution, ChildResult result, String sessionId) {}
