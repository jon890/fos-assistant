package com.bifos.assistant.people;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.people.application.SignInPolicy;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 허용 목록이 누구를 들여보내고 누구를 막는지 확인한다. */
@BackendIntegrationTest
class SignInPolicyTest {

    @Autowired
    SignInPolicy policy;

    @Autowired
    AllowedPersonRepository people;

    @BeforeEach
    void setUp() {
        people.deleteAll();
    }

    @Test
    @DisplayName("목록에 있고 켜진 주소는 profile 과 함께 통과한다")
    void passesListedEnabledAddressWithProfile() {
        people.save(AllowedPerson.of("mom@example.com", "엄마", "mom", Instant.now()));

        assertThat(policy.admit("mom@example.com"))
                .get()
                .extracting(AllowedPerson::displayName, AllowedPerson::hermesProfile)
                .containsExactly("엄마", "mom");
    }

    @Test
    @DisplayName("목록에 없는 주소는 거절한다")
    void rejectsAddressNotInList() {
        people.save(AllowedPerson.of("mom@example.com", "엄마", "mom", Instant.now()));

        assertThat(policy.admit("stranger@example.com")).isEmpty();
    }

    @Test
    @DisplayName("목록에 있어도 꺼진 주소는 거절한다")
    void rejectsListedButDisabledAddress() {
        AllowedPerson left = people.save(AllowedPerson.of("mom@example.com", "엄마", "mom", Instant.now()));
        left.disable();
        people.save(left);

        assertThat(policy.admit("mom@example.com")).isEmpty();
    }

    @Test
    @DisplayName("대문자가 섞인 주소도 같은 사람으로 찾는다")
    void findsSamePersonForAddressWithUppercase() {
        people.save(AllowedPerson.of("mom@example.com", "엄마", "mom", Instant.now()));

        assertThat(policy.admit("Mom@Example.com"))
                .get()
                .extracting(AllowedPerson::hermesProfile)
                .isEqualTo("mom");
    }

    @Test
    @DisplayName("대문자가 섞인 주소로 넣어도 중복 검사에 걸린다")
    void uppercaseAddressStillHitsDuplicateCheck() {
        people.save(AllowedPerson.of("MOM@Example.com", "엄마", "mom", Instant.now()));

        assertThat(people.existsByEmail(AllowedPerson.normalizeEmail("mom@example.com")))
                .isTrue();
    }

    @Test
    @DisplayName("빈 주소는 거절한다")
    void rejectsBlankAddress() {
        assertThat(policy.admit("")).isEmpty();
    }
}
