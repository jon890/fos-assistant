package com.bifos.assistant.chat.conversation.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.application.PrimaryAgents;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 사람이 여는 새 대화의 에이전트를 정한다. 새 대화는 늘 요청자의 기본 비서와 시작한다(비서 중심 대화 스펙 REQ4, DECISION3).
 *
 * <p>요청 본문의 에이전트 코드는 받지 않는다. 비었으면 기본 비서이고, 기본 비서와 다른 코드면 옛 화면이나 `?agent=` 가
 * 고른 것이므로 거절한다. 예약 작업이 결과를 남길 대화처럼 Control Plane 이 여는 대화는 이 판정을 쓰지 않는다.
 */
@Component
@RequiredArgsConstructor
public class ConversationEntry {

    private final PrimaryAgents primaryAgents;
    private final AgentService agents;


    /**
     * 새 대화의 에이전트다.
     *
     * @throws ApiException {@code AGENT_NOT_FOUND}(기본 비서가 없다), {@code VALIDATION_FAILED}(다른 에이전트를 골랐다),
     * {@code AGENT_DISABLED}(기본 비서가 꺼졌다)
     */
    public Agent startAgent(CurrentUser user, String agentCode) {
        Agent primary = primaryAgents.of(user)
            .orElseThrow(() -> new ApiException(ErrorCode.AGENT_NOT_FOUND, "no primary assistant"));
        if (agentCode != null && !agentCode.isBlank() && !agentCode.equals(primary.code())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "a new conversation starts with the primary assistant");
        }
        return agents.requireStartable(user, primary.code());
    }
}
