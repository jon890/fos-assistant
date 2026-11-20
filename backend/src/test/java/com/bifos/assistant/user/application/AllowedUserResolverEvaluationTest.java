package com.bifos.assistant.user.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** 평가 전용 사용자의 주소로 서명된 토큰이 와도 매 요청 인증이 막는다(docs/features/agent-quality/design.md 의 「숨기는 곳」). */
@BackendIntegrationTest
class AllowedUserResolverEvaluationTest {

    @Autowired
    AllowedUserResolver resolver;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    @Autowired
    AllowedPersonRepository people;

    @Autowired
    TransactionTemplate transactions;


    @BeforeEach
    void setUp() {
        agents.deleteAll();
        users.deleteAll();
        people.deleteAll();
    }


    @Test
    @DisplayName("평가 전용 사용자는 비어 있는 값을 받는다")
    void evaluationUserIsRejected() {
        AppUser saved = users.save(AppUser.of("evaluation@quality.invalid", "평가 사용자", 1L, UserRole.MEMBER));
        transactions.executeWithoutResult(status -> users.markEvaluation(saved.id(), Instant.now()));

        assertThat(resolver.resolveAllowed("evaluation@quality.invalid", "평가 사용자")).isEmpty();
        assertThat(resolver.resolveCurrentUser("evaluation@quality.invalid", "평가 사용자")).isEmpty();
    }


    @Test
    @DisplayName("보통 사용자는 그대로 통과한다")
    void ordinaryUserPasses() {
        users.save(AppUser.of("mom@example.com", "엄마", 1L, UserRole.MEMBER));

        assertThat(resolver.resolveAllowed("mom@example.com", "엄마")).map(AppUser::email).contains("mom@example.com");
    }
}
