package com.bifos.assistant.people.application;

import com.bifos.assistant.agent.application.PeopleProperties;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.hermes.HermesProperties;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.user.application.FirstSignInListener;
import com.bifos.assistant.user.domain.AppUser;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 허용 목록에 있는 사람이 처음 로그인할 때 그 사람의 에이전트를 만든다.
 *
 * <p>{@code user} 는 {@code people} 과 {@code agent} 보다 아래 패키지라 이 일을 직접 하지 못한다(ADR-068).
 * 그래서 {@code user} 가 둔 {@link FirstSignInListener} 를 여기서 구현한다. 트랜잭션을 따로 열지 않고
 * 부르는 쪽의 트랜잭션 안에서 돈다. 에이전트를 저장하지 못하면 사용자 저장도 함께 되돌려진다.
 */
@Service
@RequiredArgsConstructor
public class FirstAgentCreator implements FirstSignInListener {

    private final SignInPolicy signInPolicy;
    private final AgentRepository agents;
    private final HermesProperties hermesProperties;
    private final PeopleProperties peopleProperties;
    private final Clock clock;

    /** 허용 목록에서 그 사람을 찾으면 에이전트를 만들고, 찾지 못하면 아무것도 만들지 않는다. */
    @Override
    public void onUserCreated(AppUser created, String email) {
        signInPolicy.admit(email).ifPresent(person -> createFirstAgent(created, person));
    }

    /**
     * 그 사람의 profile 을 가리키는 에이전트를 하나 만든다.
     *
     * <p>Hermes 를 부르지 않는다. 에이전트는 모델을 갖지 않고, 대화가 모델을 고르지 않으면 그 profile 의
     * 기본값으로 돈다(ADR-030).
     */
    private void createFirstAgent(AppUser owner, AllowedPerson person) {
        agents.save(Agent.of(
                person.hermesProfile(),
                person.displayName(),
                person.hermesProfile(),
                hermesProperties.profileBaseUrl(person.hermesProfile()),
                peopleProperties.defaultCostMode(),
                peopleProperties.defaultCredentialScope(),
                AgentVisibility.PRIVATE,
                owner.id(),
                clock.instant()));
    }
}
