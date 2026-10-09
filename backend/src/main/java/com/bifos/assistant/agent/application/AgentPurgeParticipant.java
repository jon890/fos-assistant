package com.bifos.assistant.agent.application;

/**
 * 지운 에이전트를 정리할 때 그 에이전트를 가리키는 자기 표의 줄을 맡는다(ADR-20261009 / agent-purge).
 *
 * <p>{@code agent} 보다 위 패키지가 구현한다. {@code agent} 는 그 패키지를 import 하지 못하므로 여기 port 를 두고
 * {@link AgentPurgeWriter} 가 구현 모두를 한 트랜잭션 안에서 부른다.
 */
public interface AgentPurgeParticipant {

    /** 그 에이전트 행을 아직 지우면 안 되는가. 정리 트랜잭션 안에서 부른다. */
    boolean blocksPurge(Long agentId);

    /** 그 에이전트를 가리키는 자기 표의 줄을 지우거나 비운다. 정리 트랜잭션 안에서 부른다. */
    void release(Long agentId);
}
