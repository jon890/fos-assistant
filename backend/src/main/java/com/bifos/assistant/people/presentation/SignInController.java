package com.bifos.assistant.people.presentation;

import com.bifos.assistant.people.application.SignInPolicy;
import com.bifos.assistant.people.presentation.PeopleDtos.SignInCheckRequest;
import com.bifos.assistant.people.presentation.PeopleDtos.SignInCheckView;
import com.bifos.assistant.shared.auth.AuthProperties;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 웹 계층이 로그인을 받아들이기 전에 묻는 자리다.
 *
 * <p>이 경로는 사용자를 만들지 않는다. 판정만 한다. 다른 경로는 토큰을 받으면 그 자리에서
 * {@code app_user} 를 만드는데, 허용되지 않은 주소가 그 경로를 타면 사용자가 생긴다.
 *
 * <p>그래서 이 경로는 {@code ControlPlaneJwtFilter} 를 건너뛰고 Spring Security 에서도 열려 있다.
 * 열린 자리라 상태 코드를 스스로 내야 한다. 아래 검사가 그 일을 한다.
 */
@RestController
@RequestMapping("/api/v1/signin")
public class SignInController {

    private static final String BEARER = "Bearer ";

    /** 이 경로만 받는 토큰의 쓰임새다. 대화용 토큰으로 이 경로를 부를 수 없다. */
    private static final String SIGN_IN_PURPOSE = "signin";

    private final SignInPolicy policy;
    private final SecretKey key;

    public SignInController(SignInPolicy policy, AuthProperties properties) {
        this.policy = policy;
        this.key = Keys.hmacShaKeyFor(properties.jwtSecret().getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping("/allowed")
    public SignInCheckView allowed(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody SignInCheckRequest request) {
        requireSignInToken(authorization);
        return policy.admit(request.email()).map(SignInCheckView::of).orElseGet(SignInCheckView::rejected);
    }

    /**
     * 웹 계층이 서명한 로그인 판정용 토큰인지 본다.
     *
     * <p>이 토큰은 신원을 담지 않는다. 누구를 묻는지는 요청 본문이 적고, 토큰은 물을 자격만 증명한다.
     */
    private void requireSignInToken(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER)) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "a sign-in check token is required");
        }
        String token = authorization.substring(BEARER.length()).trim();
        Claims claims;
        try {
            claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        } catch (JwtException | IllegalArgumentException ex) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "this token is not usable here");
        }
        if (!SIGN_IN_PURPOSE.equals(claims.get("purpose", String.class))) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "this token is not usable here");
        }
    }
}
