package com.bifos.assistant.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.hermes.HermesModelClient;
import com.bifos.assistant.hermes.HermesProperties;
import com.bifos.assistant.people.application.PeopleProperties;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.UserProvisioningService;
import com.bifos.assistant.user.infra.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 허용 목록에 있는 사람이 처음 들어올 때 무엇이 함께 생기는지 본다.
 *
 * <p>에이전트를 만들려고 시도하는 것은 {@code app_user} 를 새로 저장하는 그 순간뿐이다. 에이전트는 모델을
 * 갖지 않으므로 그때 Hermes 에 모델을 묻지 않는다.
 */
@SpringBootTest
@ActiveProfiles("test")
class FirstSignInTest {

    private static final String EMAIL = "aunt@example.com";
    private static final String NAME = "이모";
    private static final String PROFILE = "aunt";

    @Autowired UserProvisioningService provisioning;
    @Autowired AppUserRepository users;
    @Autowired AllowedPersonRepository people;
    @Autowired AgentRepository agents;
    @Autowired HermesProperties hermesProperties;
    @Autowired PeopleProperties peopleProperties;

    /** 답을 정해 두지 않는다. 첫 로그인이 이 대역을 한 번도 부르지 않는지 본다. */
    @MockitoBean HermesModelClient hermesModels;

    @BeforeEach
    void 준비한다() {
        agents.deleteAll();
        users.deleteAll();
        people.deleteAll();
        people.save(AllowedPerson.of(EMAIL, NAME, PROFILE));
    }

    private String expectedApiBaseUrl() {
        return hermesProperties.profileBaseUrl(PROFILE);
    }

    @Test
    void 허용_목록에_있는_사람의_첫_요청에_사용자와_에이전트가_함께_생긴다() {
        AppUser created = provisioning.resolve(EMAIL, NAME);

        assertThat(created.email()).isEqualTo(EMAIL);
        assertThat(agents.findByCode(PROFILE))
                .get()
                .extracting(Agent::name, Agent::hermesProfile, Agent::apiBaseUrl)
                .containsExactly(NAME, PROFILE, expectedApiBaseUrl());
    }

    @Test
    void 만들어진_에이전트는_주인이_그_사람이고_자기만_본다() {
        AppUser created = provisioning.resolve(EMAIL, NAME);

        assertThat(agents.findByCode(PROFILE))
                .get()
                .extracting(Agent::visibility, Agent::ownerUserId, Agent::enabled)
                .containsExactly(AgentVisibility.PRIVATE, created.id(), true);
    }

    @Test
    void 만들어진_에이전트의_과금_설정은_설정의_기본값과_같다() {
        provisioning.resolve(EMAIL, NAME);

        assertThat(agents.findByCode(PROFILE))
                .get()
                .extracting(Agent::costMode, Agent::credentialScope)
                .containsExactly(
                        peopleProperties.defaultCostMode(), peopleProperties.defaultCredentialScope());
    }

    @Test
    void 같은_사람의_두_번째_요청에는_에이전트가_늘지_않는다() {
        AppUser first = provisioning.resolve(EMAIL, NAME);

        AppUser again = provisioning.resolve(EMAIL, NAME);

        assertThat(again.id()).isEqualTo(first.id());
        assertThat(agents.count()).isEqualTo(1);
    }

    @Test
    void 허용_목록에_없는_주소는_사용자만_만들고_에이전트를_만들지_않는다() {
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
    void Hermes_에_모델을_묻지_않고_에이전트를_만든다() {
        AppUser created = provisioning.resolve(EMAIL, NAME);

        assertThat(created.id()).isNotNull();
        assertThat(agents.findByCode(PROFILE))
                .as("Hermes 가 모델과 provider 를 주지 않아도 에이전트가 만들어져야 한다")
                .isPresent();
        verifyNoInteractions(hermesModels);
    }

    @Test
    void 허용_목록에서_꺼진_사람은_에이전트를_만들지_않는다() {
        AllowedPerson left = people.findByEmailAndEnabledTrue(EMAIL).orElseThrow();
        left.disable();
        people.save(left);

        provisioning.resolve(EMAIL, NAME);

        assertThat(users.findByEmail(EMAIL)).isPresent();
        assertThat(agents.count()).isZero();
    }
}
