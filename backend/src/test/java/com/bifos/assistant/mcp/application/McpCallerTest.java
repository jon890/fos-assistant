package com.bifos.assistant.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.user.domain.UserRole;
import org.junit.jupiter.api.DisplayName;
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
    @DisplayName("요청자와 origin 실행과 호출 맥락 중 하나라도 없으면 만들지 못한다")
    void cannotBeBuiltWhenAnyOfRequesterOriginRunOrCallContextIsMissing() {
        assertThatThrownBy(() -> new McpCaller(null, execution, context))
                .as("요청자 없음")
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new McpCaller(user, null, context))
                .as("origin 실행 없음")
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new McpCaller(user, execution, null))
                .as("호출 맥락 없음")
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("세 값이 있으면 실행 번호는 origin 실행의 번호다")
    void runIdIsOriginRunIdWhenAllThreeValuesExist() {
        when(execution.id()).thenReturn(42L);

        assertThat(new McpCaller(user, execution, context).executionId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("인증 주체는 profile 과 토큰 해시 없이 만들지 못하고 로그 글에 해시를 싣지 않는다")
    void principalNeedsProfileAndTokenHashAndLogTextOmitsHash() {
        assertThatThrownBy(() -> new McpPrincipal(1L, null, "hash"))
                .as("profile 없음")
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new McpPrincipal(1L, "p", null))
                .as("토큰 해시 없음")
                .isInstanceOf(NullPointerException.class);

        String tokenHash = "a".repeat(64);
        assertThat(new McpPrincipal(1L, "shared-group", tokenHash).toString())
                .contains("shared-group")
                .doesNotContain(tokenHash);
    }
}
