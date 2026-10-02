package com.bifos.assistant.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.hermes.HermesModelClient;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.user.application.AllowedUserResolver;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 허용 목록에서 꺼진 주소가 요청마다 막히는지 실제 데이터베이스에서 본다(ADR-059).
 *
 * <p>줄이 꺼져 있을 때만 막는다. 줄이 없는 주소는 막지 않는다.
 */
@SpringBootTest
@ActiveProfiles("test")
class RevokedUserTest {

    private static final String EMAIL = "aunt@example.com";
    private static final String NAME = "이모";
    private static final String PROFILE = "aunt";

    @Autowired
    AllowedUserResolver resolver;

    @Autowired
    AppUserRepository users;

    @Autowired
    AllowedPersonRepository people;

    @Autowired
    AgentRepository agents;

    /** 사용자를 만들 때 Hermes 를 부르지 않게 대역으로 둔다. */
    @MockitoBean
    HermesModelClient hermesModels;

    @BeforeEach
    void setUp() {
        agents.deleteAll();
        users.deleteAll();
        people.deleteAll();
    }

    private AllowedPerson saveDisabledPerson() {
        AllowedPerson person = AllowedPerson.of(EMAIL, NAME, PROFILE, Instant.now());
        person.disable();
        return people.save(person);
    }

    @Test
    @DisplayName("꺼진 주소는 비어 있는 값을 받고 사용자가 생기지 않는다")
    void returnsEmptyAndCreatesNoUserForDisabledAddress() {
        saveDisabledPerson();

        Optional<AppUser> resolved = resolver.resolveAllowed(EMAIL, NAME);

        assertThat(resolved).isEmpty();
        assertThat(users.findByEmail(EMAIL)).isEmpty();
        assertThat(users.count()).isZero();
    }

    @Test
    @DisplayName("꺼진 주소를 대문자를 섞어 넘겨도 비어 있는 값을 받는다")
    void returnsEmptyForDisabledAddressInMixedCase() {
        saveDisabledPerson();

        Optional<AppUser> resolved = resolver.resolveAllowed("Aunt@Example.COM", NAME);

        assertThat(resolved).isEmpty();
        assertThat(users.count()).isZero();
    }

    @Test
    @DisplayName("켜진 줄이 있는 주소는 사용자가 생긴다")
    void createsUserForEnabledAddress() {
        people.save(AllowedPerson.of(EMAIL, NAME, PROFILE, Instant.now()));

        Optional<AppUser> resolved = resolver.resolveAllowed(EMAIL, NAME);

        assertThat(resolved).map(AppUser::email).contains(EMAIL);
        assertThat(users.findByEmail(EMAIL)).isPresent();
    }

    @Test
    @DisplayName("허용 목록에 줄이 없는 주소는 막지 않고 사용자가 생긴다")
    void createsUserForAddressWithoutAllowlistRow() {
        Optional<AppUser> resolved = resolver.resolveAllowed("stranger@example.com", "낯선 사람");

        assertThat(resolved).map(AppUser::email).contains("stranger@example.com");
        assertThat(users.findByEmail("stranger@example.com")).isPresent();
    }

    @Test
    @DisplayName("이미 들어온 사용자의 줄을 끄면 막히고 다시 켜면 같은 사용자가 돌아온다")
    void blocksJoinedUserWhenDisabledAndReturnsSameUserWhenEnabledAgain() {
        AllowedPerson person = people.save(AllowedPerson.of(EMAIL, NAME, PROFILE, Instant.now()));
        AppUser joined = resolver.resolveAllowed(EMAIL, NAME).orElseThrow();

        person.disable();
        people.save(person);

        assertThat(resolver.resolveAllowed(EMAIL, NAME)).isEmpty();

        person.enable();
        people.save(person);

        assertThat(resolver.resolveAllowed(EMAIL, NAME)).map(AppUser::id).contains(joined.id());
        assertThat(users.count()).isEqualTo(1);
    }
}
