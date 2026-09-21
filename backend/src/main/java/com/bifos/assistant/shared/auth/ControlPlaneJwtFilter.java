package com.bifos.assistant.shared.auth;

import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.UserProvisioningService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import io.jsonwebtoken.security.Keys;

/**
 * Accepts the short-lived token minted by the web tier after an OAuth sign-in.
 *
 * <p>The token carries only an identity. Every permission decision, including which Hermes profile
 * the caller may run and which memory they may see, is made later from database state.
 */
@Component
public class ControlPlaneJwtFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ControlPlaneJwtFilter.class);
    private static final String BEARER = "Bearer ";

    /**
     * 이 필터가 해석하지 않는 경로다.
     *
     * <p>{@code /mcp} 는 장기 토큰을 쓰는 다른 인증 경계다. {@code /api/v1/signin/allowed} 는 아직
     * 사용자가 없는 시점에 돌므로 여기를 지나면 안 된다. 이 필터는 토큰을 받으면 그 자리에서
     * {@code app_user} 를 만들고, 그러면 허용되지 않은 주소로도 사용자가 생긴다. 그 경로는 토큰을
     * 스스로 검사한다.
     */
    private static final Set<String> UNFILTERED_PATHS = Set.of("/mcp", "/api/v1/signin/allowed");

    private final SecretKey key;
    private final UserProvisioningService users;

    public ControlPlaneJwtFilter(AuthProperties properties, UserProvisioningService users) {
        this.key = Keys.hmacShaKeyFor(properties.jwtSecret().getBytes(StandardCharsets.UTF_8));
        this.users = users;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return UNFILTERED_PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER)) {
            authenticate(header.substring(BEARER.length()).trim());
        }
        chain.doFilter(request, response);
    }

    private void authenticate(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            String email = claims.getSubject();
            String name = claims.get("name", String.class);
            if (email == null || email.isBlank()) {
                return;
            }
            AppUser user = users.resolve(email, name == null || name.isBlank() ? email : name);
            CurrentUser principal =
                    new CurrentUser(user.id(), user.email(), user.displayName(), user.familyId(), user.role());
            var authentication =
                    new UsernamePasswordAuthenticationToken(
                            principal,
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (JwtException ex) {
            log.warn("rejected a control plane token: {}", ex.getMessage());
        }
    }
}
