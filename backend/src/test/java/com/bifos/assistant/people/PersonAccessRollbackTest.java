package com.bifos.assistant.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.people.application.PersonAccessService;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.FailingAccessRevoker;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 끄는 저장과 토큰 폐기가 한 트랜잭션이라, 폐기가 실패하면 끄기도 되돌아가는지 확인한다(ADR-056). */
@BackendIntegrationTest
class PersonAccessRollbackTest {

    private static final String EMAIL = "rollback-person@example.com";

    /** 폐기를 받는 쪽이 실패하는 상황을 만든다. 켠 검사 안에서만 던진다. */
    @Autowired
    FailingAccessRevoker revoker;

    @Autowired
    PersonAccessService access;

    @Autowired
    AllowedPersonRepository people;

    @Autowired
    AppUserRepository users;

    @AfterEach
    void clean() {
        people.findAll().stream().filter(p -> p.email().equals(EMAIL)).forEach(people::delete);
        users.findAllByNormalizedEmail(EMAIL).forEach(users::delete);
    }

    @Test
    @DisplayName("폐기가 실패하면 끄는 저장도 롤백된다")
    void disableRollsBackWhenRevokeFails() {
        AllowedPerson person = people.save(AllowedPerson.of(EMAIL, "x", "rollback-person", Instant.now()));
        users.save(AppUser.of(EMAIL, "x", 1L, UserRole.MEMBER, Instant.now()));
        revoker.fail();

        assertThatThrownBy(() -> access.setEnabled(person.id(), false)).isInstanceOf(IllegalStateException.class);

        assertThat(people.findById(person.id()).orElseThrow().enabled()).isTrue();
    }
}
