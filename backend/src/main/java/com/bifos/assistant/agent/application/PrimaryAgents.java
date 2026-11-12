package com.bifos.assistant.agent.application;

import com.bifos.assistant.agent.application.port.ReservedProfileNames;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 기본 비서를 가린다(docs/features/agent-team/design.md 의 DECISION7, DECISION25).
 *
 * <p>칸을 두지 않는다. 기본 비서는 첫 로그인이 사용자의 기본 profile 을 가리키게 만든 에이전트이고, 만들기는 허용 목록이 쥔
 * profile 이름을 받지 않으므로 기본 profile 을 가리키는 에이전트는 그것뿐이다.
 */
@Service
@RequiredArgsConstructor
public class PrimaryAgents {

    private final AgentRepository agents;
    private final ReservedProfileNames reservedProfileNames;


    /** 요청자의 기본 비서다. 주인이 요청자이고 profile 이 요청자의 기본 profile 인 지우지 않은 에이전트다. 없으면 비어 있다. */
    public Optional<Agent> of(CurrentUser user) {
        return reservedProfileNames.profileOf(user.email())
            .flatMap(agents::findByHermesProfileAndDeletedAtIsNull)
            .filter(agent -> Objects.equals(agent.ownerUserId(), user.id()));
    }
}
