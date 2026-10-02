package com.bifos.assistant.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.people.application.PersonAccessService;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.shared.auth.UserAccessRevoked;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.type.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.test.context.ActiveProfiles;

/** 끄는 저장과 토큰 폐기가 한 트랜잭션이라, 폐기가 실패하면 끄기도 되돌아가는지 확인한다(ADR-056). */
@SpringBootTest
@ActiveProfiles("test")
@Import(PersonAccessRollbackTest.FailingRevoker.class)
class PersonAccessRollbackTest {

    private static final String EMAIL = "rollback-person@example.com";
    private static final AtomicBoolean FAIL = new AtomicBoolean();

    /** 폐기를 받는 쪽이 실패하는 상황을 만든다. 켠 검사 안에서만 던진다. */
    @TestConfiguration
    static class FailingRevoker {
        @Bean
        Object failingRevoker() {
            return new Object() {
                @EventListener
                void on(UserAccessRevoked event) {
                    if (FAIL.get()) {
                        throw new IllegalStateException("revoke failed");
                    }
                }
            };
        }
    }

    @Autowired
    PersonAccessService access;

    @Autowired
    AllowedPersonRepository people;

    @Autowired
    AppUserRepository users;

    @AfterEach
    void clean() {
        FAIL.set(false);
        people.findAll().stream().filter(p -> p.email().equals(EMAIL)).forEach(people::delete);
        users.findAllByNormalizedEmail(EMAIL).forEach(users::delete);
    }

    @Test
    @DisplayName("폐기가 실패하면 끄는 저장도 롤백된다")
    void disableRollsBackWhenRevokeFails() {
        AllowedPerson person = people.save(AllowedPerson.of(EMAIL, "x", "rollback-person", Instant.now()));
        users.save(AppUser.of(EMAIL, "x", 1L, UserRole.MEMBER, Instant.now()));
        FAIL.set(true);

        assertThatThrownBy(() -> access.setEnabled(person.id(), false)).isInstanceOf(IllegalStateException.class);

        assertThat(people.findById(person.id()).orElseThrow().enabled()).isTrue();
    }
}
