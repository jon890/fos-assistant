package com.bifos.assistant.shared.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.user.application.AllowedUserResolver;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.type.UserRole;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

/**
 * 웹 계층 JWT 필터의 경계를 확인한다.
 *
 * <p>MCP 요청과 하위 에이전트 session 등록 요청은 해석하지 않고, 꺼진 사용자의 토큰은 401 로 거절한다.
 */
class ControlPlaneJwtFilterTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-test-secret";
    private static final String EMAIL = "aunt@example.com";
    private static final String NAME = "이모";

    private final ObjectMapper json = new ObjectMapper();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private ControlPlaneJwtFilter filter(AllowedUserResolver users) {
        return new ControlPlaneJwtFilter(new AuthProperties(SECRET), users, json);
    }

    private String token(String secret) {
        return Jwts.builder()
                .subject(EMAIL)
                .claim("name", NAME)
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    private MockHttpServletRequest requestWith(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/agents");
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    @Test
    @DisplayName("꺼진 사용자의 토큰은 401 과 ACCESS_REVOKED 로 답하고 체인을 잇지 않는다")
    void rejectsRevokedUserWith401AndStopsChain() throws Exception {
        AllowedUserResolver users = mock(AllowedUserResolver.class);
        when(users.resolveAllowed(EMAIL, NAME)).thenReturn(Optional.empty());
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter(users).doFilter(requestWith(token(SECRET)), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getCharacterEncoding()).isEqualToIgnoringCase("UTF-8");
        assertThat(json.readTree(response.getContentAsString()).path("code").asString())
                .isEqualTo("ACCESS_REVOKED");
        assertThat(json.readTree(response.getContentAsString()).path("message").asString())
                .isEqualTo("access was revoked");
        assertThat(json.readTree(response.getContentAsString()).has("missingToolsets"))
                .as("비어 있는 missingToolsets 는 본문에서 빠져야 한다")
                .isFalse();
        assertThat(chain.getRequest()).as("꺼진 사용자의 요청이 체인으로 넘어갔다").isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("꺼지지 않은 사용자의 토큰은 체인을 잇고 CurrentUser 를 인증으로 올린다")
    void continuesChainAndAuthenticatesAllowedUser() throws Exception {
        AllowedUserResolver users = mock(AllowedUserResolver.class);
        when(users.resolveAllowed(EMAIL, NAME))
                .thenReturn(Optional.of(AppUser.of(EMAIL, NAME, 1L, UserRole.MEMBER, Instant.now())));
        MockHttpServletRequest request = requestWith(token(SECRET));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter(users).doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                .isInstanceOfSatisfying(CurrentUser.class, principal -> {
                    assertThat(principal.email()).isEqualTo(EMAIL);
                    assertThat(principal.role()).isEqualTo(UserRole.MEMBER);
                });
    }

    @Test
    @DisplayName("서명이 틀린 토큰은 사용자를 찾지 않고 인증 없이 체인을 잇는다")
    void continuesChainWithoutAuthenticationForBadSignature() throws Exception {
        AllowedUserResolver users = mock(AllowedUserResolver.class);
        MockHttpServletRequest request = requestWith(token("other-secret-other-secret-other-secret-other-secret"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter(users).doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(users, never()).resolveAllowed(any(), any());
    }

    @Test
    @DisplayName("subject 가 없는 토큰은 사용자를 찾지 않고 인증 없이 체인을 잇는다")
    void continuesChainWithoutAuthenticationForTokenWithoutSubject() throws Exception {
        AllowedUserResolver users = mock(AllowedUserResolver.class);
        String withoutSubject = Jwts.builder()
                .claim("name", NAME)
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
        MockHttpServletRequest request = requestWith(withoutSubject);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter(users).doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(users, never()).resolveAllowed(any(), any());
    }

    @Test
    @DisplayName("Authorization 헤더가 없으면 사용자를 찾지 않고 체인을 잇는다")
    void continuesChainWithoutAuthorizationHeader() throws Exception {
        AllowedUserResolver users = mock(AllowedUserResolver.class);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/agents");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter(users).doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(200);
        verify(users, never()).resolveAllowed(any(), any());
    }

    @Test
    @DisplayName("mcp 요청은 Control Plane JWT 필터를 건너뛴다")
    void mcpRequestSkipsControlPlaneJwtFilter() {
        ControlPlaneJwtFilter filter = filter(mock(AllowedUserResolver.class));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");

        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    @DisplayName("하위 에이전트 session 등록 요청은 Control Plane JWT 필터를 건너뛴다")
    void subagentSessionRegistrationRequestSkipsControlPlaneJwtFilter() {
        ControlPlaneJwtFilter filter = filter(mock(AllowedUserResolver.class));
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/internal/hermes/session-bindings/subagent");

        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    @DisplayName("서비스 토큰 경로는 건너뛰고 서비스 토큰 관리 경로는 웹 JWT 로 해석한다")
    void serviceApiSkipsButServiceTokenManagementDoesNot() {
        ControlPlaneJwtFilter filter = filter(mock(AllowedUserResolver.class));

        assertThat(filter.shouldNotFilter(
                        new MockHttpServletRequest("GET", "/api/v1/service/memory-documents/identity/x")))
                .isTrue();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/v1/service-tokens")))
                .isFalse();
    }
}
