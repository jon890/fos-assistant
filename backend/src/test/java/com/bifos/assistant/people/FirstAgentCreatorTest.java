package com.bifos.assistant.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.user.application.UserProvisioningService;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 첫 로그인에 에이전트를 저장하지 못하면 사용자 저장도 함께 되돌려지는지 본다.
 *
 * <p>테스트 클래스에 트랜잭션을 두지 않는다. 두면 {@code resolve} 의 트랜잭션이 테스트의 트랜잭션에 합류해
 * 되돌려진 것을 볼 수 없다.
 */
@BackendIntegrationTest
class FirstAgentCreatorTest {

    private static final String EMAIL = "uncle@example.com";
    private static final String NAME = "삼촌";
    private static final String PROFILE = "uncle";

    @Autowired
    UserProvisioningService provisioning;

    @Autowired
    AppUserRepository users;

    @Autowired
    AllowedPersonRepository people;

    /** 에이전트 저장이 실패하는 경우를 만들려면 저장소가 던지게 할 수 있어야 한다. */
    @Autowired
    AgentRepository agents;

    @BeforeEach
    void setUp() {
        agents.deleteAll();
        users.deleteAll();
        people.deleteAll();
        people.save(AllowedPerson.of(EMAIL, NAME, PROFILE, Instant.now()));
    }

    @Test
    @DisplayName("에이전트 저장이 실패하면 그 예외를 그대로 던지고 사용자도 남기지 않는다")
    void rethrowsAndLeavesNoUserWhenAgentSaveFails() {
        DataIntegrityViolationException failure = new DataIntegrityViolationException("에이전트 저장 실패");
        doThrow(failure).when(agents).save(any(Agent.class));

        assertThatThrownBy(() -> provisioning.resolve(EMAIL, NAME)).isSameAs(failure);

        assertThat(users.findByEmail(EMAIL))
                .as("에이전트를 저장하지 못했으면 같은 트랜잭션의 사용자 저장도 되돌려져야 한다")
                .isEmpty();
        assertThat(agents.count()).as("에이전트 줄도 남지 않아야 한다").isZero();
    }
}
