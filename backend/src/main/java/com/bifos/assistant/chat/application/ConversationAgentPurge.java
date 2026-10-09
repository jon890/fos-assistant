package com.bifos.assistant.chat.application;

import com.bifos.assistant.agent.application.AgentPurgeParticipant;
import com.bifos.assistant.chat.infra.ConversationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 지운 에이전트를 정리할 때 대화 쪽을 맡는다(ADR-20261009 / agent-purge).
 *
 * <p>사용자가 지웠지만 아직 정리되지 않은 대화가 있으면 미룬다. 대화 정리가 에이전트 행의 주소와 profile 로 Hermes session 을
 * 지우기 때문이다. 부르는 쪽의 정리 트랜잭션 안에서만 돈다.
 */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
class ConversationAgentPurge implements AgentPurgeParticipant {

    private final ConversationRepository conversations;

    @Override
    public boolean blocksPurge(Long agentId) {
        return conversations.existsByAgentIdAndDeletedAtIsNotNullAndPurgedAtIsNull(agentId);
    }

    @Override
    public void release(Long agentId) {
        // 대화 줄은 남긴다. FK 가 없고, 읽는 경로가 에이전트 행이 없어도 「지운 에이전트」 로 그린다.
    }
}
