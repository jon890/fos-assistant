package com.bifos.assistant.mcp.infra;

import com.bifos.assistant.mcp.application.AgentTokenService;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
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
    private static final String BEARER = "Bearer ";
    private final AgentTokenService tokens;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"/mcp".equals(request.getServletPath());
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        if (request.getHeader(HttpHeaders.ORIGIN) != null) { response.setStatus(HttpServletResponse.SC_FORBIDDEN); return; }
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER) || header.substring(BEARER.length()).trim().isEmpty()) { response.setStatus(HttpServletResponse.SC_UNAUTHORIZED); return; }
        try {
            CurrentUser user = tokens.authenticate(header.substring(BEARER.length()).trim());
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name()))));
            chain.doFilter(request, response);
        } catch (ApiException ex) { SecurityContextHolder.clearContext(); response.setStatus(HttpServletResponse.SC_UNAUTHORIZED); }
    }
}
