package com.bifos.assistant.people.application;

import com.bifos.assistant.agent.application.AgentDefaultToolsets;
import com.bifos.assistant.agent.application.PeopleProperties;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.hermes.HermesProperties;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.shared.concurrent.BackgroundTasks;
import com.bifos.assistant.user.application.FirstSignInListener;
import com.bifos.assistant.user.domain.AppUser;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 허용 목록에 있는 사람이 처음 로그인할 때 그 사람의 에이전트를 만든다.
 *
 * <p>{@code user} 는 {@code people} 과 {@code agent} 보다 아래 패키지라 이 일을 직접 하지 못한다(ADR-068).
 * 그래서 {@code user} 가 둔 {@link FirstSignInListener} 를 여기서 구현한다. 트랜잭션을 따로 열지 않고
 * 부르는 쪽의 트랜잭션 안에서 돈다. 에이전트를 저장하지 못하면 사용자 저장도 함께 되돌려진다.
 *
 * <p>기본 도구는 그 트랜잭션이 커밋된 뒤에 백그라운드 작업으로 켠다. 실행 공간 주인 키({@code u<사용자 번호>})가 이때 처음
 * 생기므로 profile 을 만들 때가 아니라 여기서 켠다. 첫 요청이 Hermes 호출을 기다리지 않고, Hermes 가 실패해도 로그인은
 * 되돌리지 않는다(ADR-20261008-default-toolsets).
 */
@Service
@RequiredArgsConstructor
public class FirstAgentCreator implements FirstSignInListener {

    private final SignInPolicy signInPolicy;
    private final AgentRepository agents;
    private final HermesProperties hermesProperties;
    private final PeopleProperties peopleProperties;
    private final AgentDefaultToolsets defaultToolsets;
    private final BackgroundTasks backgroundTasks;
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
        Agent saved = agents.save(Agent.of(
                person.hermesProfile(),
                person.displayName(),
                person.hermesProfile(),
                hermesProperties.profileBaseUrl(person.hermesProfile()),
                peopleProperties.defaultCostMode(),
                peopleProperties.defaultCredentialScope(),
                AgentVisibility.PRIVATE,
                owner.id(),
                clock.instant()));
        afterCommit(() -> backgroundTasks.start(
                "default-toolsets-" + saved.code(),
                () -> defaultToolsets.apply(saved, peopleProperties.defaultToolsets())));
    }

    /** 트랜잭션이 없으면 곧바로 돈다. 되돌려진 로그인의 profile 에는 도구를 켜지 않는다. */
    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
