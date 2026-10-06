package com.bifos.assistant.agent.application;

import com.bifos.assistant.agent.domain.Agent;

/**
 * 에이전트에 붙은 커넥터 연결을 모두 뗀다(ADR-083).
 *
 * <p>{@code connector} 가 {@code agent} 보다 위 패키지라 여기 port 를 두고 {@code connector} 가 구현한다. 읽기 port
 * {@link AgentConnectorBindings} 와 나눈다. 떼기는 대시보드와 승인 줄을 건드리는 무거운 일이라 읽기만 쓰는 쪽이 함께 끌어오지
 * 않게 한다.
 */
public interface AgentConnectorDetacher {

    /**
     * 그 에이전트의 바인딩을 모두 떼고 행을 지운다. 부르는 쪽의 트랜잭션 안에서 부른다.
     *
     * @throws com.bifos.assistant.shared.error.ApiException 떼지 못했을 때. 그 승인 실행이 아직 돌고 있으면
     *     {@code CONNECTOR_ACTION_EXECUTING}, 대시보드 호출이 실패하면 {@code CONNECTOR_OPERATION_FAILED} 다
     */
    void detachAll(Agent agent);
}
