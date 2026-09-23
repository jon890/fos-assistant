package com.bifos.assistant.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.people.application.HermesProfileProvisioner;
import com.bifos.assistant.people.application.PersonRegistrar;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 사람 하나를 더하는 순서와 실패했을 때 되돌리는 것을 본다.
 *
 * <p>Hermes 쪽은 대역으로 바꿔 끼우고 우리 표는 실제 저장소를 쓴다. 「행이 남지 않았다」를 단언하려면
 * 실제로 저장되고 실제로 지워지는 것을 봐야 하기 때문이다.
 */
@SpringBootTest
@ActiveProfiles("test")
class PersonRegistrarTest {

    private static final String EMAIL = "aunt@example.com";
    private static final String NAME = "이모";
    private static final String PROFILE = "aunt";

    @Autowired PersonRegistrar registrar;
    @Autowired AllowedPersonRepository people;
    @Autowired AgentRepository agents;

    /** 실제 Hermes 대시보드를 부르지 않는다. 언제 불렸는지와 실패했을 때를 여기서 정한다. */
    @MockitoBean HermesProfileProvisioner profiles;

    @BeforeEach
    void 준비한다() {
        people.deleteAll();
        agents.deleteAll();
    }

    @Test
    void 새_사람을_더하면_행이_생기고_profile_이_만들어진다() {
        AllowedPerson added = registrar.register(EMAIL, NAME, PROFILE);

        verify(profiles).provision(PROFILE);
        assertThat(added.isEnabled()).isTrue();
        assertThat(people.findByEmailAndEnabledTrue(EMAIL))
                .get()
                .extracting(AllowedPerson::displayName, AllowedPerson::hermesProfile)
                .containsExactly(NAME, PROFILE);
    }

    @Test
    void 대문자가_섞인_주소도_소문자로_맞춰_넣는다() {
        registrar.register("Aunt@Example.com", NAME, PROFILE);

        assertThat(people.existsByEmail(EMAIL)).isTrue();
    }

    /** 반만 더해진 사람이 남으면 같은 주소로 다시 더할 수 없고 그 사람은 대화하지도 못한다. */
    @Test
    void profile_만들기가_실패하면_행이_남지_않는다() {
        doThrow(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "dashboard is down"))
                .when(profiles)
                .provision(PROFILE);

        assertThatThrownBy(() -> registrar.register(EMAIL, NAME, PROFILE))
                .isInstanceOf(ApiException.class)
                .extracting(thrown -> ((ApiException) thrown).code())
                .isEqualTo(ErrorCode.HERMES_UNAVAILABLE);
        assertThat(people.existsByEmail(EMAIL)).isFalse();
    }

    @Test
    void 이미_쓰는_이메일이면_거절하고_Hermes_를_부르지_않는다() {
        people.save(AllowedPerson.of(EMAIL, NAME, "already-taken"));

        assertThatThrownBy(() -> registrar.register(EMAIL, NAME, PROFILE))
                .isInstanceOf(ApiException.class)
                .extracting(thrown -> ((ApiException) thrown).code())
                .isEqualTo(ErrorCode.PERSON_EMAIL_TAKEN);
        verify(profiles, never()).provision(any());
    }

    @Test
    void 허용_목록이_이미_쓰는_profile_이름이면_거절하고_Hermes_를_부르지_않는다() {
        people.save(AllowedPerson.of("uncle@example.com", "삼촌", PROFILE));

        assertThatThrownBy(() -> registrar.register(EMAIL, NAME, PROFILE))
                .isInstanceOf(ApiException.class)
                .extracting(thrown -> ((ApiException) thrown).code())
                .isEqualTo(ErrorCode.PERSON_PROFILE_TAKEN);
        verify(profiles, never()).provision(any());
    }

    /**
     * 허용 목록에 없어도 에이전트가 그 profile 을 쓰고 있으면 거절한다.
     *
     * <p>한쪽만 보면 다른 쪽에서 쓰던 이름으로 profile 을 만들게 되고, 두 사람이 같은 profile 로 돌아
     * 격리가 깨진다.
     */
    @Test
    void 에이전트가_이미_쓰는_profile_이름이면_거절한다() {
        agents.save(Agent.of(
                "uncle",
                "삼촌 비서",
                PROFILE,
                "https://hermes-listener.example.com/p/" + PROFILE,
                "openai-codex",
                "gpt-5.6-sol",
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.FAMILY,
                null));

        assertThatThrownBy(() -> registrar.register(EMAIL, NAME, PROFILE))
                .isInstanceOf(ApiException.class)
                .extracting(thrown -> ((ApiException) thrown).code())
                .isEqualTo(ErrorCode.PERSON_PROFILE_TAKEN);
        verify(profiles, never()).provision(any());
    }
}
