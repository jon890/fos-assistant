package com.bifos.assistant.people;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.people.application.SignInPolicy;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** 허용 목록이 누구를 들여보내고 누구를 막는지 확인한다. */
@SpringBootTest
@ActiveProfiles("test")
class SignInPolicyTest {

    @Autowired SignInPolicy policy;
    @Autowired AllowedPersonRepository people;

    @BeforeEach
    void 준비한다() {
        people.deleteAll();
    }

    @Test
    void 목록에_있고_켜진_주소는_profile_과_함께_통과한다() {
        people.save(AllowedPerson.of("mom@example.com", "엄마", "mom"));

        assertThat(policy.admit("mom@example.com"))
                .get()
                .extracting(AllowedPerson::displayName, AllowedPerson::hermesProfile)
                .containsExactly("엄마", "mom");
    }

    @Test
    void 목록에_없는_주소는_거절한다() {
        people.save(AllowedPerson.of("mom@example.com", "엄마", "mom"));

        assertThat(policy.admit("stranger@example.com")).isEmpty();
    }

    @Test
    void 목록에_있어도_꺼진_주소는_거절한다() {
        AllowedPerson left = people.save(AllowedPerson.of("mom@example.com", "엄마", "mom"));
        left.disable();
        people.save(left);

        assertThat(policy.admit("mom@example.com")).isEmpty();
    }

    @Test
    void 대문자가_섞인_주소도_같은_사람으로_찾는다() {
        people.save(AllowedPerson.of("mom@example.com", "엄마", "mom"));

        assertThat(policy.admit("Mom@Example.com"))
                .get()
                .extracting(AllowedPerson::hermesProfile)
                .isEqualTo("mom");
    }

    @Test
    void 대문자가_섞인_주소로_넣어도_중복_검사에_걸린다() {
        people.save(AllowedPerson.of("MOM@Example.com", "엄마", "mom"));

        assertThat(people.existsByEmail(AllowedPerson.normalizeEmail("mom@example.com"))).isTrue();
    }

    @Test
    void 빈_주소는_거절한다() {
        assertThat(policy.admit("")).isEmpty();
    }
}
