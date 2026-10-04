package com.bifos.assistant.proactive.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.application.CheckConversations;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.proactive.application.model.CheckReadiness;
import com.bifos.assistant.proactive.application.model.CheckStatusView;
import com.bifos.assistant.proactive.infra.ProactiveCheckRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 먼저 살펴보기의 진입점이다(ADR-077). 진입 경로는 {@code docs/backend/proactive-check.md} 의 「진입점」 이 갖는다.
 *
 * <p>모든 경로가 먼저 요청자가 그 에이전트로 대화를 시작할 수 있는지 본다. 아니면 {@code AGENT_NOT_FOUND} 나
 * {@code AGENT_DISABLED} 이고 Hermes 를 부르지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ProactiveCheckService {

    private final AgentService agents;
    private final ProactiveCheckReadiness readiness;
    private final CheckConversations checkConversations;
    private final ProactiveCheckRepository checks;

    /**
     * 살펴보기를 할 수 있는지, 요청자의 점검 대화, 요청자의 마지막 살펴보기를 읽는다.
     *
     * <p>준비 판정이 Hermes 를 부른다. 실패하면 그 예외를 그대로 올린다. 같은 에이전트를 쓰는 다른 사용자의 점검 대화와
     * 살펴보기는 싣지 않는다.
     */
    public CheckStatusView status(CurrentUser user, String agentCode) {
        Agent agent = agents.requireStartable(user, agentCode);
        CheckReadiness checked = readiness.check(agent);
        return new CheckStatusView(
                checked,
                checkConversations
                        .find(user.id(), agent.id())
                        .map(Conversation::publicId)
                        .orElse(null),
                checks.findFirstByUserIdAndAgentIdOrderByIdDesc(user.id(), agent.id())
                        .orElse(null));
    }
}
