package com.bifos.assistant.shared.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.bifos.assistant.user.domain.UserProvisioningService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** MCP 요청과 하위 에이전트 session 등록 요청은 웹 계층 JWT 필터가 해석하지 않는다는 경계를 확인한다. */
class ControlPlaneJwtFilterTest {

    @Test
    @DisplayName("mcp 요청은 Control Plane JWT 필터를 건너뛴다")
    void mcpRequestSkipsControlPlaneJwtFilter() {
        ControlPlaneJwtFilter filter = new ControlPlaneJwtFilter(
                new AuthProperties("test-secret-test-secret-test-secret-test-secret"),
                mock(UserProvisioningService.class));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");

        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    @DisplayName("하위 에이전트 session 등록 요청은 Control Plane JWT 필터를 건너뛴다")
    void subagentSessionRegistrationRequestSkipsControlPlaneJwtFilter() {
        ControlPlaneJwtFilter filter = new ControlPlaneJwtFilter(
                new AuthProperties("test-secret-test-secret-test-secret-test-secret"),
                mock(UserProvisioningService.class));
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/internal/hermes/session-bindings/subagent");

        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    @DisplayName("서비스 토큰 경로는 건너뛰고 서비스 토큰 관리 경로는 웹 JWT 로 해석한다")
    void serviceApiSkipsButServiceTokenManagementDoesNot() {
        ControlPlaneJwtFilter filter = new ControlPlaneJwtFilter(
                new AuthProperties("test-secret-test-secret-test-secret-test-secret"),
                mock(UserProvisioningService.class));

        assertThat(filter.shouldNotFilter(
                        new MockHttpServletRequest("GET", "/api/v1/service/memory-documents/identity/x")))
                .isTrue();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/v1/service-tokens")))
                .isFalse();
    }
}
