package com.bifos.assistant.mcp.infra;

import com.bifos.assistant.mcp.application.AgentTokenService;
import com.bifos.assistant.mcp.application.McpPrincipal;
import com.bifos.assistant.shared.error.ApiException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class AgentTokenAuthenticationFilter extends OncePerRequestFilter {
    /**
     * 인증한 토큰의 SHA-256 소문자 16진수를 담는 요청 속성 이름이다.
     *
     * <p>{@code _fos_ctx} 서명의 key 가 이 값이다(ADR-031). 토큰 원문은 요청 어디에도 두지 않는다.
     */
    public static final String TOKEN_HASH_ATTRIBUTE = AgentTokenAuthenticationFilter.class.getName() + ".TOKEN_HASH";
    /**
     * profile 토큰으로 인증하는 경로다. {@code /mcp} 와 하위 에이전트 session 등록 경로(ADR-037)와 커넥터 도구 호출
     * 판정 경로(ADR-047)가 같은 토큰을 쓴다.
     * 사용자 JWT 필터 {@code ControlPlaneJwtFilter} 는 같은 경로를 건너뛴다. 경로를 더하면 두 곳을 함께 고친다.
     */
    private static final Set<String> AGENT_TOKEN_PATHS =
            Set.of("/mcp", "/internal/hermes/session-bindings/subagent", "/internal/hermes/connector-policy");

    private static final String BEARER = "Bearer ";
    private static final String MCP_AUTHORITY = "ROLE_MCP";
    private final AgentTokenService tokens;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !AGENT_TOKEN_PATHS.contains(request.getServletPath());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getHeader(HttpHeaders.ORIGIN) != null) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null
                || !header.startsWith(BEARER)
                || header.substring(BEARER.length()).trim().isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        try {
            String raw = header.substring(BEARER.length()).trim();
            McpPrincipal principal = tokens.authenticate(raw);
            request.setAttribute(TOKEN_HASH_ATTRIBUTE, principal.tokenHash());
            // 토큰은 사용자가 아니라 profile 을 증명한다(ADR-032). 사용자의 역할을 권한으로 싣지 않는다.
            SecurityContextHolder.getContext()
                    .setAuthentication(new UsernamePasswordAuthenticationToken(
                            principal, null, List.of(new SimpleGrantedAuthority(MCP_AUTHORITY))));
            chain.doFilter(request, response);
        } catch (ApiException ex) {
            SecurityContextHolder.clearContext();
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        }
    }
}
