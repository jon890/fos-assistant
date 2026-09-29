package com.bifos.assistant.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.orchestration.application.DelegationParentResolver;
import com.bifos.assistant.shared.auth.CurrentUser;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;

/** 서명한 뿌리 session 으로 도는 부모 실행을 정확히 하나만 고르는 것을 고정한다. */
@SpringBootTest
@ActiveProfiles("test")
class DelegationParentResolverTest {

    private static final List<String> MY_EMAILS =
            List.of("parent-resolver-dad@example.com", "parent-resolver-kid@example.com");
    private static final String ROOT_SESSION = "fos-00000000-0000-4000-8000-00000000a001";
    private static final Instant STARTED = Instant.parse("2026-09-29T00:00:00Z");

    @Autowired DelegationParentResolver resolver;
    @Autowired AgentExecutionRepository executions;
    @Autowired AppUserRepository users;

    private CurrentUser dad;
    private CurrentUser kid;

    /** 같은 H2 를 다른 검사 클래스와 함께 쓰므로 이 검사의 사용자와 그 실행 줄만 지운다. */
    @BeforeEach
    void 준비한다() {
        MY_EMAILS.forEach(email -> users.findByEmail(email).ifPresent(user -> {
            executions.deleteAll(executions.findByUserIdOrderByIdDesc(user.id(), Pageable.unpaged()));
            users.delete(user);
        }));
        dad = current(users.save(AppUser.of(MY_EMAILS.get(0), "아빠", 1L, UserRole.MEMBER)));
        kid = current(users.save(AppUser.of(MY_EMAILS.get(1), "아이", 1L, UserRole.MEMBER)));
    }

    @Test
    void 도는_부모가_하나면_그_실행을_돌려준다() {
        AgentExecution running = save(dad, ROOT_SESSION, ExecutionStatus.RUNNING);
        save(dad, ROOT_SESSION, ExecutionStatus.SUCCEEDED);
        save(dad, "fos-00000000-0000-4000-8000-00000000a002", ExecutionStatus.RUNNING);

        assertThat(resolver.resolve(dad, ROOT_SESSION).id()).isEqualTo(running.id());
    }

    @Test
    void 끝난_실행만_있으면_실패한다() {
        save(dad, ROOT_SESSION, ExecutionStatus.SUCCEEDED);
        save(dad, ROOT_SESSION, ExecutionStatus.CANCELLED);

        assertRejected(dad, ROOT_SESSION);
    }

    @Test
    void 남의_사용자의_도는_실행은_부모가_되지_못한다() {
        save(kid, ROOT_SESSION, ExecutionStatus.RUNNING);

        assertRejected(dad, ROOT_SESSION);
    }

    @Test
    void 같은_session_의_도는_줄이_둘이면_가장_최근을_고르지_않고_실패한다() {
        save(dad, ROOT_SESSION, ExecutionStatus.RUNNING);
        save(dad, ROOT_SESSION, ExecutionStatus.RUNNING);

        assertRejected(dad, ROOT_SESSION);
    }

    @Test
    void 비어_있는_뿌리_session_은_session_이_없는_줄을_고르지_않는다() {
        save(dad, null, ExecutionStatus.RUNNING);

        assertRejected(dad, null);
        assertRejected(dad, "");
    }

    private AgentExecution save(CurrentUser user, String hermesSessionId, ExecutionStatus status) {
        return executions.save(AgentExecution.builder()
                .userId(user.id())
                .conversationId(1L)
                .profileName("parent-resolver")
                .hermesSessionId(hermesSessionId)
                .costMode(CostMode.SUBSCRIPTION)
                .status(status)
                .startedAt(STARTED)
                .build());
    }

    private void assertRejected(CurrentUser user, String rootSessionId) {
        assertThatThrownBy(() -> resolver.resolve(user, rootSessionId))
                .as("user=%s root=%s", user.email(), rootSessionId)
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.MCP_CALL_CONTEXT_INVALID));
    }

    private static CurrentUser current(AppUser user) {
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }
}
