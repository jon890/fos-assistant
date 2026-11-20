package com.bifos.assistant.people;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.people.application.SignInPolicy;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** 허용 목록이 누구를 들여보내고 누구를 막는지 확인한다. */
@BackendIntegrationTest
class SignInPolicyTest {

    @Autowired
    SignInPolicy policy;

    @Autowired
    AllowedPersonRepository people;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    @Autowired
    TransactionTemplate transactions;


    @BeforeEach
    void setUp() {
        people.deleteAll();
        agents.deleteAll();
        users.deleteAll();
    }


    @Test
    @DisplayName("목록에 있고 켜진 주소는 profile 과 함께 통과한다")
    void passesListedEnabledAddressWithProfile() {
        people.save(AllowedPerson.of("mom@example.com", "엄마", "mom"));

        assertThat(policy.admit("mom@example.com"))
            .get()
            .extracting(AllowedPerson::displayName, AllowedPerson::hermesProfile)
            .containsExactly("엄마", "mom");
    }


    @Test
    @DisplayName("목록에 없는 주소는 거절한다")
    void rejectsAddressNotInList() {
        people.save(AllowedPerson.of("mom@example.com", "엄마", "mom"));

        assertThat(policy.admit("stranger@example.com")).isEmpty();
    }


    @Test
    @DisplayName("목록에 있어도 꺼진 주소는 거절한다")
    void rejectsListedButDisabledAddress() {
        AllowedPerson left = people.save(AllowedPerson.of("mom@example.com", "엄마", "mom"));
        left.disable();
        people.save(left);

        assertThat(policy.admit("mom@example.com")).isEmpty();
    }


    @Test
    @DisplayName("대문자가 섞인 주소도 같은 사람으로 찾는다")
    void findsSamePersonForAddressWithUppercase() {
        people.save(AllowedPerson.of("mom@example.com", "엄마", "mom"));

        assertThat(policy.admit("Mom@Example.com"))
            .get()
            .extracting(AllowedPerson::hermesProfile)
            .isEqualTo("mom");
    }


    @Test
    @DisplayName("대문자가 섞인 주소로 넣어도 중복 검사에 걸린다")
    void uppercaseAddressStillHitsDuplicateCheck() {
        people.save(AllowedPerson.of("MOM@Example.com", "엄마", "mom"));

        assertThat(people.existsByEmail(AllowedPerson.normalizeEmail("mom@example.com")))
            .isTrue();
    }


    @Test
    @DisplayName("빈 주소는 거절한다")
    void rejectsBlankAddress() {
        assertThat(policy.admit("")).isEmpty();
    }


    @Test
    @DisplayName("평가 전용 사용자 주소는 로그인 판정과 완료 기록에서 막지만 꺼진 주소로 보지 않는다")
    void evaluationAddressCannotSignInButIsNotRevoked() {
        String email = "evaluation@quality.invalid";
        people.save(AllowedPerson.of(email, "평가 사용자", "eval-check"));
        AppUser saved = users.save(AppUser.of(email, "평가 사용자", 1L, UserRole.MEMBER));
        transactions.executeWithoutResult(status -> users.markEvaluation(saved.id(), Instant.now()));

        assertThat(policy.admit(email)).isEmpty();
        assertThat(policy.recordCompletion(email)).isFalse();
        // 예약 작업 발화가 이 판정으로 주인을 확인하므로 평가 사용자를 막지 않는다.
        assertThat(policy.revoked(email)).isFalse();
    }
}
