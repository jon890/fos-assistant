package com.bifos.assistant.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import com.bifos.assistant.agent.application.PeopleProperties;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.hermes.HermesModelClient;
import com.bifos.assistant.hermes.HermesProperties;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.user.application.UserProvisioningService;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 허용 목록에 있는 사람이 처음 들어올 때 무엇이 함께 생기는지 본다.
 *
 * <p>에이전트를 만들려고 시도하는 것은 {@code app_user} 를 새로 저장하는 그 순간뿐이다. 에이전트는 모델을
 * 갖지 않으므로 그때 Hermes 에 모델을 묻지 않는다.
 */
@BackendIntegrationTest
class FirstSignInTest {

    private static final String EMAIL = "aunt@example.com";
    private static final String NAME = "이모";
    private static final String PROFILE = "aunt";

    @Autowired
    UserProvisioningService provisioning;

    @Autowired
    AppUserRepository users;

    @Autowired
    AllowedPersonRepository people;

    @Autowired
    AgentRepository agents;

    @Autowired
    HermesProperties hermesProperties;

    @Autowired
    PeopleProperties peopleProperties;

    /** 답을 정해 두지 않는다. 첫 로그인이 이 대역을 한 번도 부르지 않는지 본다. */
    @Autowired
    HermesModelClient hermesModels;

    @BeforeEach
    void setUp() {
        agents.deleteAll();
        users.deleteAll();
        people.deleteAll();
        people.save(AllowedPerson.of(EMAIL, NAME, PROFILE, Instant.now()));
    }

    private String expectedApiBaseUrl() {
        return hermesProperties.profileBaseUrl(PROFILE);
    }

    @Test
    @DisplayName("허용 목록에 있는 사람의 첫 요청에 사용자와 에이전트가 함께 생긴다")
    void firstRequestOfAllowlistedPersonCreatesUserAndAgentTogether() {
        AppUser created = provisioning.resolve(EMAIL, NAME);

        assertThat(created.email()).isEqualTo(EMAIL);
        assertThat(agents.findByCode(PROFILE))
                .get()
                .extracting(Agent::name, Agent::hermesProfile, Agent::apiBaseUrl)
                .containsExactly(NAME, PROFILE, expectedApiBaseUrl());
    }

    @Test
    @DisplayName("만들어진 에이전트는 주인이 그 사람이고 자기만 본다")
    void createdAgentIsOwnedByThatPersonAndPrivate() {
        AppUser created = provisioning.resolve(EMAIL, NAME);

        assertThat(agents.findByCode(PROFILE))
                .get()
                .extracting(Agent::visibility, Agent::ownerUserId, Agent::enabled)
                .containsExactly(AgentVisibility.PRIVATE, created.id(), true);
    }

    @Test
    @DisplayName("만들어진 에이전트의 과금 설정은 설정의 기본값과 같다")
    void createdAgentBillingSettingEqualsConfigDefault() {
        provisioning.resolve(EMAIL, NAME);

        assertThat(agents.findByCode(PROFILE))
                .get()
                .extracting(Agent::costMode, Agent::credentialScope)
                .containsExactly(peopleProperties.defaultCostMode(), peopleProperties.defaultCredentialScope());
    }

    @Test
    @DisplayName("같은 사람의 두 번째 요청에는 에이전트가 늘지 않는다")
    void secondRequestOfSamePersonAddsNoAgent() {
        AppUser first = provisioning.resolve(EMAIL, NAME);

        AppUser again = provisioning.resolve(EMAIL, NAME);

        assertThat(again.id()).isEqualTo(first.id());
        assertThat(agents.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("허용 목록에 없는 주소는 사용자만 만들고 에이전트를 만들지 않는다")
    void addressNotInAllowlistCreatesOnlyUserAndNoAgent() {
        provisioning.resolve("stranger@example.com", "낯선 사람");

        assertThat(users.findByEmail("stranger@example.com")).isPresent();
        assertThat(agents.count()).isZero();
    }

    /**
     * Hermes 가 모델을 주지 않아도 에이전트가 만들어진다.
     *
     * <p>대역이 아무 답도 정해 두지 않았으므로 불렀다면 모델도 provider 도 받지 못한다. 대화가 모델을 고르지
     * 않으면 그 profile 의 기본값으로 돌기 때문에(ADR-030) 첫 로그인은 모델을 묻지 않는다.
     */
    @Test
    @DisplayName("Hermes 에 모델을 묻지 않고 에이전트를 만든다")
    void createsAgentWithoutAskingHermesForModel() {
        AppUser created = provisioning.resolve(EMAIL, NAME);

        assertThat(created.id()).isNotNull();
        assertThat(agents.findByCode(PROFILE))
                .as("Hermes 가 모델과 provider 를 주지 않아도 에이전트가 만들어져야 한다")
                .isPresent();
        verifyNoInteractions(hermesModels);
    }

    @Test
    @DisplayName("허용 목록에서 꺼진 사람은 에이전트를 만들지 않는다")
    void doesNotCreateAgentForPersonDisabledInAllowlist() {
        AllowedPerson left = people.findByEmailAndEnabledTrue(EMAIL).orElseThrow();
        left.disable();
        people.save(left);

        provisioning.resolve(EMAIL, NAME);

        assertThat(users.findByEmail(EMAIL)).isPresent();
        assertThat(agents.count()).isZero();
    }
}
