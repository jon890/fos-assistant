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

    private final SecretKey key;
    private final UserProvisioningService users;

    public ControlPlaneJwtFilter(AuthProperties properties, UserProvisioningService users) {
        this.key = Keys.hmacShaKeyFor(properties.jwtSecret().getBytes(StandardCharsets.UTF_8));
        this.users = users;
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
                    new CurrentUser(user.id(), user.email(), user.displayName(), user.role());
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
