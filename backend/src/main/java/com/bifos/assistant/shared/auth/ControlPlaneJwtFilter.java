package com.bifos.assistant.shared.auth;

import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.error.ErrorResponse;
import com.bifos.assistant.user.application.AllowedUserResolver;
import com.bifos.assistant.user.domain.AppUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.crypto.SecretKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * 웹 계층이 OAuth 로그인 뒤에 만들어 준 수명이 짧은 토큰을 받는다.
 *
 * <p>토큰은 누구인지만 담는다. 어느 Hermes profile 을 돌릴 수 있는지, 어느 memory 를 볼 수 있는지 같은
 * 권한 판정은 모두 뒤에서 데이터베이스 상태로 한다.
 *
 * <p>관리자가 허용 목록에서 끈 사용자의 토큰은 요청마다 여기서 거절한다(ADR-059). 허용 목록은 로그인할
 * 때만 확인하므로, 여기서 막지 않으면 세션이 남은 동안 모든 사용자 API 가 열려 있다. 인증 없이 지나가게
 * 두면 Spring Security 가 403 으로 답해 웹이 꺼진 것을 구분하지 못하므로, 401 과
 * {@code ACCESS_REVOKED} 를 이 필터가 직접 쓴다. 서명이 틀린 토큰은 전과 같이 인증 없이 지나간다.
 */
@Component
@Slf4j
public class ControlPlaneJwtFilter extends OncePerRequestFilter {
    private static final String BEARER = "Bearer ";
    private static final String SERVICE_API_PREFIX = "/api/v1/service/";

    /**
     * 이 필터가 해석하지 않는 경로다.
     *
     * <p>{@code /mcp} 와 하위 에이전트 session 등록 경로 {@code /internal/hermes/session-bindings/subagent} 와
     * 커넥터 도구 호출 판정 경로 {@code /internal/hermes/connector-policy} 는
     * 장기 토큰을 쓰는 다른 인증 경계다. {@code /api/v1/signin/allowed} 는 아직
     * 사용자가 없는 시점에 돌므로 여기를 지나면 안 된다. 이 필터는 토큰을 받으면 그 자리에서
     * {@code app_user} 를 만들고, 그러면 허용되지 않은 주소로도 사용자가 생긴다. 그 경로는 토큰을
     * 스스로 검사한다. {@code /api/v1/service/} 아래는 서비스 토큰을 쓰는 다른 인증 경계다(ADR-056).
     *
     * <p>꺼진 사용자를 401 로 거절하는 판정도 이 경로들에는 걸리지 않는다. 그 밖의 경로는
     * {@code permitAll} 이어도 이 필터가 막는다.
     */
    private static final Set<String> UNFILTERED_PATHS = Set.of(
            "/mcp",
            "/internal/hermes/session-bindings/subagent",
            "/internal/hermes/connector-policy",
            "/api/v1/signin/allowed");

    /** 토큰 하나를 판정한 결과다. */
    private enum TokenOutcome {
        /** 사용자를 인증으로 올렸다. */
        AUTHENTICATED,
        /** 서명이 틀렸거나 주소가 없어 인증 없이 지나간다. */
        ANONYMOUS,
        /** 관리자가 허용 목록에서 끈 사용자다. 401 로 거절한다. */
        REVOKED
    }

    private final SecretKey key;
    private final AllowedUserResolver users;
    private final ObjectMapper json;

    public ControlPlaneJwtFilter(AuthProperties properties, AllowedUserResolver users, ObjectMapper json) {
        this.key = Keys.hmacShaKeyFor(properties.jwtSecret().getBytes(StandardCharsets.UTF_8));
        this.users = users;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return UNFILTERED_PATHS.contains(uri) || uri.startsWith(SERVICE_API_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER)) {
            if (authenticate(header.substring(BEARER.length()).trim()) == TokenOutcome.REVOKED) {
                log.warn("rejected a revoked user");
                writeRevoked(response);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * 토큰이 가리키는 사용자를 인증으로 올린다.
     *
     * <p>서명이 틀렸거나 주소가 없는 토큰은 인증을 올리지 않고 {@code ANONYMOUS} 를 돌려줘, 요청이 인증
     * 없이 지나가게 한다.
     */
    private TokenOutcome authenticate(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            String email = claims.getSubject();
            String name = claims.get("name", String.class);
            if (email == null || email.isBlank()) {
                return TokenOutcome.ANONYMOUS;
            }
            Optional<AppUser> allowed = users.resolveAllowed(email, name == null || name.isBlank() ? email : name);
            if (allowed.isEmpty()) {
                return TokenOutcome.REVOKED;
            }
            AppUser user = allowed.get();
            CurrentUser principal =
                    new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
            var authentication = new UsernamePasswordAuthenticationToken(
                    principal,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            return TokenOutcome.AUTHENTICATED;
        } catch (JwtException ex) {
            log.warn("rejected a control plane token: {}", ex.getMessage());
            return TokenOutcome.ANONYMOUS;
        }
    }

    /**
     * 401 과 {@code ACCESS_REVOKED} 를 직접 쓴다.
     *
     * <p>{@code sendError} 를 쓰지 않는다. ERROR 디스패치가 돌아 본문이 바뀐다.
     */
    private void writeRevoked(HttpServletResponse response) throws IOException {
        response.setStatus(ErrorCode.ACCESS_REVOKED.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter()
                .write(json.writeValueAsString(
                        new ErrorResponse(ErrorCode.ACCESS_REVOKED.name(), "access was revoked")));
    }
}
