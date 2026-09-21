package com.bifos.assistant.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentModelOption;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.infra.AgentModelOptionRepository;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.hermes.HermesModelClient;
import com.bifos.assistant.hermes.HermesProperties;
import com.bifos.assistant.hermes.dto.HermesModelOptions;
import com.bifos.assistant.people.application.PeopleProperties;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.UserProvisioningService;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 허용 목록에 있는 사람이 처음 들어올 때 무엇이 함께 생기는지 본다.
 *
 * <p>에이전트를 만들려고 시도하는 것은 {@code app_user} 를 새로 저장하는 그 순간뿐이다. 이 경로는
 * 매 요청 도는 자리라, 「없으면 다시 만든다」로 하면 모든 요청이 Hermes 호출 하나를 끌고 다닌다.
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
    @Autowired AgentModelOptionRepository modelOptions;
    @Autowired HermesProperties hermesProperties;
    @Autowired PeopleProperties peopleProperties;

    /** 실제 Hermes 를 부르지 않는다. 무엇을 돌려줄지와 읽지 못하는 경우를 여기서 정한다. */
    @MockitoBean HermesModelClient hermesModels;

    @BeforeEach
    void 준비한다() {
        modelOptions.deleteAll();
        agents.deleteAll();
        users.deleteAll();
        people.deleteAll();
        people.save(AllowedPerson.of(EMAIL, NAME, PROFILE));
        when(hermesModels.readOptions(anyString(), anyString()))
                .thenReturn(new HermesModelOptions("gpt-5.5", "openai-codex"));
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
                .extracting(Agent::name, Agent::hermesProfile, Agent::apiBaseUrl, Agent::provider, Agent::model)
                .containsExactly(NAME, PROFILE, expectedApiBaseUrl(), "openai-codex", "gpt-5.5");
    }

    /**
     * 모델을 읽은 시각도 함께 남는다.
     *
     * <p>방금 Hermes 에서 읽은 값으로 모델을 채워 놓고 읽은 시각만 비워 두면, 관리 화면이 그 에이전트를
     * 한 번도 읽지 않은 것으로 보인다.
     */
    @Test
    void 만들어진_에이전트는_모델을_읽은_시각을_갖는다() {
        provisioning.resolve(EMAIL, NAME);

        assertThat(agents.findByCode(PROFILE)).get().extracting(Agent::modelSyncedAt).isNotNull();
    }

    /** 목록이 비어 있으면 그 사람의 첫 대화가 쓸 모델을 찾지 못해 실패한다. */
    @Test
    void 만들어진_에이전트는_1순위_모델을_하나_갖는다() {
        provisioning.resolve(EMAIL, NAME);

        Agent agent = agents.findByCode(PROFILE).orElseThrow();
        List<AgentModelOption> options = modelOptions.findByAgentIdOrderByRankAsc(agent.id());
        assertThat(options)
                .singleElement()
                .extracting(AgentModelOption::rank, AgentModelOption::provider, AgentModelOption::model)
                .containsExactly(1, "openai-codex", "gpt-5.5");
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

    /** 여기서 거절하면 Hermes 가 답하지 않는 동안 그 사람이 아무것도 하지 못한다. */
    @Test
    void 모델을_읽지_못하면_사용자만_만들고_로그인은_막지_않는다() {
        when(hermesModels.readOptions(any(), any())).thenReturn(null);

        AppUser created = provisioning.resolve(EMAIL, NAME);

        assertThat(created.id()).isNotNull();
        assertThat(agents.count()).isZero();
    }

    /** 틀린 provider 로 만들어진 에이전트는 실행할 때마다 실패하고 그 원인이 화면에 드러나지 않는다. */
    @Test
    void provider_를_비워서_주면_기본값으로_메우지_않고_에이전트를_만들지_않는다() {
        when(hermesModels.readOptions(any(), any()))
                .thenReturn(new HermesModelOptions("gpt-5.5", null));

        AppUser created = provisioning.resolve(EMAIL, NAME);

        assertThat(created.id()).isNotNull();
        assertThat(agents.count()).isZero();
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
