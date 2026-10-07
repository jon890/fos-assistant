package com.bifos.assistant.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.people.application.HermesProfileProvisioner;
import com.bifos.assistant.people.application.PersonRegistrar;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 사람 하나를 더하는 순서와 실패했을 때 되돌리는 것을 본다.
 *
 * <p>Hermes 쪽은 대역으로 바꿔 끼우고 우리 표는 실제 저장소를 쓴다. 「행이 남지 않았다」를 단언하려면
 * 실제로 저장되고 실제로 지워지는 것을 봐야 하기 때문이다.
 */
@BackendIntegrationTest
class PersonRegistrarTest {

    private static final String EMAIL = "aunt@example.com";
    private static final String NAME = "이모";
    private static final String PROFILE = "aunt";

    @Autowired
    PersonRegistrar registrar;

    @Autowired
    AllowedPersonRepository people;

    @Autowired
    AgentRepository agents;

    /** 실제 Hermes 대시보드를 부르지 않는다. 언제 불렸는지와 실패했을 때를 여기서 정한다. */
    @Autowired
    HermesProfileProvisioner profiles;

    @BeforeEach
    void setUp() {
        doNothing().when(profiles).provision(any());
        doNothing().when(profiles).deprovision(any());
        people.deleteAll();
        agents.deleteAll();
    }

    @Test
    @DisplayName("새 사람을 더하면 행이 생기고 profile 이 만들어진다")
    void addingNewPersonCreatesRowAndProfile() {
        AllowedPerson added = registrar.register(EMAIL, NAME, PROFILE);

        verify(profiles).provision(PROFILE);
        assertThat(added.enabled()).isTrue();
        assertThat(people.findByEmailAndEnabledTrue(EMAIL))
                .get()
                .extracting(AllowedPerson::displayName, AllowedPerson::hermesProfile)
                .containsExactly(NAME, PROFILE);
    }

    @Test
    @DisplayName("대문자가 섞인 주소도 소문자로 맞춰 넣는다")
    void lowercasesAddressesWithUppercase() {
        registrar.register("Aunt@Example.com", NAME, PROFILE);

        assertThat(people.existsByEmail(EMAIL)).isTrue();
    }

    /** 반만 더해진 사람이 남으면 같은 주소로 다시 더할 수 없고 그 사람은 대화하지도 못한다. */
    @Test
    @DisplayName("profile 만들기가 실패하면 행이 남지 않는다")
    void leavesNoRowWhenProfileCreationFails() {
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
    @DisplayName("이미 쓰는 이메일이면 거절하고 Hermes 를 부르지 않는다")
    void rejectsAlreadyUsedEmailWithoutCallingHermes() {
        people.save(AllowedPerson.of(EMAIL, NAME, "already-taken", Instant.now()));

        assertThatThrownBy(() -> registrar.register(EMAIL, NAME, PROFILE))
                .isInstanceOf(ApiException.class)
                .extracting(thrown -> ((ApiException) thrown).code())
                .isEqualTo(ErrorCode.PERSON_EMAIL_TAKEN);
        verify(profiles, never()).provision(any());
    }

    @Test
    @DisplayName("허용 목록이 이미 쓰는 profile 이름이면 거절하고 Hermes 를 부르지 않는다")
    void rejectsProfileNameUsedByAllowlistWithoutCallingHermes() {
        people.save(AllowedPerson.of("uncle@example.com", "삼촌", PROFILE, Instant.now()));

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
    @DisplayName("에이전트가 이미 쓰는 profile 이름이면 거절한다")
    void rejectsProfileNameAlreadyUsedByAgent() {
        agents.save(Agent.of(
                "uncle",
                "삼촌 비서",
                PROFILE,
                "https://hermes-listener.example.com/p/" + PROFILE,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.GROUP,
                null,
                Instant.now()));

        assertThatThrownBy(() -> registrar.register(EMAIL, NAME, PROFILE))
                .isInstanceOf(ApiException.class)
                .extracting(thrown -> ((ApiException) thrown).code())
                .isEqualTo(ErrorCode.PERSON_PROFILE_TAKEN);
        verify(profiles, never()).provision(any());
    }
}
