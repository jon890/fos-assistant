package com.bifos.assistant.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.user.domain.UserRole;
import org.junit.jupiter.api.Test;

/**
 * 요청자와 인증 주체의 불변식을 타입이 지키는지 본다(ADR-032).
 *
 * <p>{@link McpCaller} 는 요청자, origin 실행, 확인한 {@code _fos_ctx} 를 늘 갖고, {@link McpPrincipal} 은 profile 과
 * 토큰 해시를 늘 갖는다. 호출자마다 null 을 다시 보지 않아도 되도록 만들 때 막는다.
 */
class McpCallerTest {
    private final CurrentUser user = new CurrentUser(7L, "user@example.com", "사용자", 1L, UserRole.MEMBER);
    private final AgentExecution execution = mock(AgentExecution.class);
    private final McpCallContext context = new McpCallContext("fos-root", "fos-root", "call_1");

    @Test
    void 요청자와_origin_실행과_호출_맥락_중_하나라도_없으면_만들지_못한다() {
        assertThatThrownBy(() -> new McpCaller(null, execution, context)).as("요청자 없음")
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new McpCaller(user, null, context)).as("origin 실행 없음")
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new McpCaller(user, execution, null)).as("호출 맥락 없음")
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void 세_값이_있으면_실행_번호는_origin_실행의_번호다() {
        when(execution.id()).thenReturn(42L);

        assertThat(new McpCaller(user, execution, context).executionId()).isEqualTo(42L);
    }

    @Test
    void 인증_주체는_profile_과_토큰_해시_없이_만들지_못하고_로그_글에_해시를_싣지_않는다() {
        assertThatThrownBy(() -> new McpPrincipal(1L, null, "hash")).as("profile 없음")
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new McpPrincipal(1L, "p", null)).as("토큰 해시 없음")
                .isInstanceOf(NullPointerException.class);

        String tokenHash = "a".repeat(64);
        assertThat(new McpPrincipal(1L, "shared-group", tokenHash).toString())
                .contains("shared-group")
                .doesNotContain(tokenHash);
    }
}
