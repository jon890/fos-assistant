package com.bifos.assistant.usage;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.usage.application.ToolDetailPolicy;
import com.bifos.assistant.user.domain.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 도구의 {@code detail} 을 누구에게 싣는지 본다. */
class ToolDetailPolicyTest {

    private static final CurrentUser ADMIN = new CurrentUser(1L, "dad@example.com", "dad", 1L, UserRole.ADMIN);
    private static final CurrentUser MEMBER = new CurrentUser(2L, "kid@example.com", "kid", 1L, UserRole.MEMBER);

    @Test
    void ADMIN_역할은_공개하지_않는_도구도_본다() {
        assertThat(ToolDetailPolicy.visibleTo(ADMIN, "terminal")).isTrue();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "terminal",
            "read_file",
            "mcp__fos_assistant__artifact_write",
            // 다른 서버가 붙인 같은 이름은 공개한 도구가 아니다.
            "mcp__other__web_search"})
    void MEMBER_역할은_공개한_도구가_아니면_보지_못한다(String toolName) {
        assertThat(ToolDetailPolicy.visibleTo(MEMBER, toolName))
                .as("MEMBER 역할에게 %s 의 detail 을 실으면 안 된다", toolName)
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"web_search", "vision_analyze"})
    void MEMBER_역할도_공개한_도구는_본다(String toolName) {
        assertThat(ToolDetailPolicy.visibleTo(MEMBER, toolName))
                .as("MEMBER 역할에게 %s 의 detail 을 실어야 한다", toolName)
                .isTrue();
    }
}
