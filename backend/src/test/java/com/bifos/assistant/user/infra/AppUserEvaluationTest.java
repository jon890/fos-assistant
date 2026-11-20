package com.bifos.assistant.user.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.user.domain.AppUser;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** 평가 전용 사용자 표시는 전용 UPDATE 로만 바뀌고 엔티티 저장이 덮지 않는다. */
@BackendIntegrationTest
class AppUserEvaluationTest {

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    @Autowired
    TransactionTemplate transactions;


    /** 전용 UPDATE 는 부르는 쪽의 트랜잭션 안에서 돈다. */
    private int mark(Long id, Instant at) {
        return transactions.execute(status -> users.markEvaluation(id, at));
    }


    @BeforeEach
    void setUp() {
        agents.deleteAll();
        users.deleteAll();
    }


    @Test
    @DisplayName("표시한 사용자만 평가 전용으로 읽히고 그룹에서 찾힌다")
    void markEvaluationSetsOnlyThatUser() {
        AppUser evaluation = users.save(AppUser.of("evaluation@quality.invalid", "평가 사용자", 1L, UserRole.MEMBER));
        AppUser other = users.save(AppUser.of("mom@example.com", "엄마", 1L, UserRole.MEMBER));

        int changed = mark(evaluation.id(), Instant.now());

        assertThat(changed).isEqualTo(1);
        assertThat(users.findById(evaluation.id()).orElseThrow().evaluation()).isTrue();
        assertThat(users.findById(other.id()).orElseThrow().evaluation()).isFalse();
        assertThat(users.findFirstByGroupIdAndEvaluationTrueOrderByIdAsc(1L)).map(AppUser::id).contains(evaluation.id());
    }


    @Test
    @DisplayName("먼저 읽어 둔 사용자를 저장해도 평가 표시가 덮이지 않는다")
    void staleEntitySaveKeepsFlag() {
        AppUser saved = users.save(AppUser.of("evaluation@quality.invalid", "평가 사용자", 1L, UserRole.MEMBER));
        AppUser stale = users.findById(saved.id()).orElseThrow();

        mark(saved.id(), Instant.now());
        stale.rename("다른 이름");
        users.save(stale);

        assertThat(users.findById(saved.id()).orElseThrow().evaluation()).isTrue();
    }


    @Test
    @DisplayName("이미 표시한 사용자는 다시 바꾸지 않는다")
    void markEvaluationIsIdempotent() {
        AppUser saved = users.save(AppUser.of("evaluation@quality.invalid", "평가 사용자", 1L, UserRole.MEMBER));
        mark(saved.id(), Instant.now());

        assertThat(mark(saved.id(), Instant.now())).isZero();
    }
}
