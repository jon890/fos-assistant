package com.bifos.assistant.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.orchestration.application.DelegationParentResolver;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** 토큰의 profile 과 서명한 뿌리 session 으로 도는 부모 실행을 정확히 하나만 고르는 것을 고정한다. */
@SpringBootTest
@ActiveProfiles("test")
class DelegationParentResolverTest {

    private static final List<String> MY_EMAILS =
            List.of("parent-resolver-dad@example.com", "parent-resolver-kid@example.com");
    private static final String PROFILE = "parent-resolver";
    private static final String OTHER_PROFILE = "parent-resolver-other";
    private static final Instant STARTED = Instant.parse("2026-09-29T00:00:00Z");

    @Autowired DelegationParentResolver resolver;
    @Autowired AgentExecutionRepository executions;
    @Autowired AppUserRepository users;
    @Autowired JdbcTemplate jdbc;

    private AppUser dad;
    private AppUser kid;
    private String root;

    /**
     * 같은 H2 를 다른 검사 클래스와 함께 쓰므로 이 검사의 사용자와 이 검사가 쓰는 profile 의 실행 줄만 지운다.
     *
     * <p>부모는 사용자로 거르지 않으므로 남은 {@code RUNNING} 줄이 「둘 이상」 으로 걸린다. 검사 때문에 운영
     * 레포지토리에 메서드를 더하지 않으려고 SQL 로 지운다.
     */
    @BeforeEach
    void 준비한다() {
        for (String profile : List.of(PROFILE, OTHER_PROFILE)) {
            jdbc.update("DELETE FROM agent_execution WHERE profile_name = ?", profile);
        }
        MY_EMAILS.forEach(email -> users.findByEmail(email).ifPresent(users::delete));
        dad = users.save(AppUser.of(MY_EMAILS.get(0), "아빠", 1L, UserRole.MEMBER));
        kid = users.save(AppUser.of(MY_EMAILS.get(1), "아이", 1L, UserRole.MEMBER));
        root = "fos-" + UUID.randomUUID();
    }

    @Test
    void 도는_부모가_하나면_그_실행을_돌려준다() {
        AgentExecution running = save(dad, PROFILE, root, ExecutionStatus.RUNNING);
        save(dad, PROFILE, root, ExecutionStatus.SUCCEEDED);
        save(dad, PROFILE, "fos-" + UUID.randomUUID(), ExecutionStatus.RUNNING);

        assertThat(resolver.resolve(PROFILE, root).id()).isEqualTo(running.id());
    }

    @Test
    void 끝난_실행만_있으면_실패한다() {
        save(dad, PROFILE, root, ExecutionStatus.SUCCEEDED);
        save(dad, PROFILE, root, ExecutionStatus.CANCELLED);

        assertRejected(PROFILE, root);
    }

    @Test
    void 다른_profile_의_도는_실행은_부모가_되지_못한다() {
        save(dad, OTHER_PROFILE, root, ExecutionStatus.RUNNING);

        assertRejected(PROFILE, root);
    }

    @Test
    void 같은_profile_에서_사용자가_다른_실행_둘은_뿌리가_다르면_각자_찾는다() {
        String kidRoot = "fos-" + UUID.randomUUID();
        AgentExecution dadRun = save(dad, PROFILE, root, ExecutionStatus.RUNNING);
        AgentExecution kidRun = save(kid, PROFILE, kidRoot, ExecutionStatus.RUNNING);

        AgentExecution forDad = resolver.resolve(PROFILE, root);
        AgentExecution forKid = resolver.resolve(PROFILE, kidRoot);

        assertThat(forDad.id()).isEqualTo(dadRun.id());
        assertThat(forDad.userId()).isEqualTo(dad.id());
        assertThat(forKid.id()).isEqualTo(kidRun.id());
        assertThat(forKid.userId()).isEqualTo(kid.id());
    }

    @Test
    void 같은_session_의_도는_줄이_둘이면_가장_최근을_고르지_않고_실패한다() {
        save(dad, PROFILE, root, ExecutionStatus.RUNNING);
        save(kid, PROFILE, root, ExecutionStatus.RUNNING);

        assertRejected(PROFILE, root);
    }

    @Test
    void 비어_있는_뿌리_session_이나_profile_은_session_이_없는_줄을_고르지_않는다() {
        save(dad, PROFILE, null, ExecutionStatus.RUNNING);

        assertRejected(PROFILE, null);
        assertRejected(PROFILE, "");
        assertRejected(null, root);
        assertRejected("", root);
    }

    private AgentExecution save(AppUser user, String profileName, String hermesSessionId, ExecutionStatus status) {
        return executions.save(AgentExecution.builder()
                .userId(user.id())
                .conversationId(1L)
                .profileName(profileName)
                .hermesSessionId(hermesSessionId)
                .costMode(CostMode.SUBSCRIPTION)
                .status(status)
                .startedAt(STARTED)
                .build());
    }

    private void assertRejected(String profileName, String rootSessionId) {
        assertThatThrownBy(() -> resolver.resolve(profileName, rootSessionId))
                .as("profile=%s root=%s", profileName, rootSessionId)
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.MCP_CALL_CONTEXT_INVALID));
    }
}
