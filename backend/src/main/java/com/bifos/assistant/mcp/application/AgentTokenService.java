package com.bifos.assistant.mcp.application;

import com.bifos.assistant.hermes.HermesProfileName;
import com.bifos.assistant.mcp.domain.AgentToken;
import com.bifos.assistant.mcp.infra.AgentTokenRepository;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * MCP 토큰의 발급, 목록, 폐기, 인증을 맡는다.
 *
 * <p>토큰은 profile 만 증명한다(ADR-032). profile 이 빈 토큰은 인증하지 않는다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AgentTokenService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final AgentTokenRepository tokens;

    /**
     * profile 에 묶인 새 토큰을 발급한다. 그 profile 에 에이전트가 있는지는 보지 않는다.
     *
     * <p>부르는 쪽 트랜잭션과 떼어 곧바로 커밋한다. profile 을 만드는 쪽은 행 잠금을 쥔 트랜잭션 안에서
     * 부르는데, 발급이 그 트랜잭션에 묶이면 커밋 전 몇 초 동안 Hermes 가 새 토큰으로 붙어도 인증이 실패한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IssuedToken issue(String profileName, String label) {
        requireProfileName(profileName);
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        AgentToken saved = tokens.save(AgentToken.issueFor(profileName, hash(raw), label));
        return new IssuedToken(saved, raw);
    }

    public List<AgentToken> list() {
        return tokens.findAll();
    }

    @Transactional
    public void revoke(Long id) {
        tokens.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.MEMORY_NOT_FOUND, "no such token"))
                .revoke();
    }

    /**
     * 그 profile 에 묶인 폐기 안 된 토큰을 모두 폐기한다. 없으면 아무것도 하지 않는다.
     *
     * <p>부르는 쪽 트랜잭션과 떼어 곧바로 커밋한다. profile 을 거두다 바깥 트랜잭션이 되돌려져도 폐기는
     * 남아야, 거두지 못한 profile 에 남은 연결이 Memory 와 결과물에 닿지 못한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeAllFor(String profileName) {
        tokens.findByProfileNameAndRevokedAtIsNull(profileName).forEach(AgentToken::revoke);
    }

    /**
     * 토큰 원문을 인증한다.
     *
     * <p>토큰이 있고, 폐기되지 않았고, profile 이 묶였을 때만 통과시키고 사용 시각을 남긴다. profile 이 빈 토큰은
     * 경고 로그를 남기고 거절한다.
     *
     * @throws ApiException {@link ErrorCode#UNAUTHENTICATED}. 없거나 폐기됐거나 profile 이 빈 토큰일 때
     */
    @Transactional
    public McpPrincipal authenticate(String raw) {
        String tokenHash = hash(raw);
        AgentToken token = tokens.findByTokenHash(tokenHash)
                .filter(value -> value.revokedAt() == null)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "invalid agent token"));
        if (token.profileName() == null) {
            log.warn("profile 이 묶이지 않은 옛 MCP 토큰을 거절했다 tokenId={}", token.id());
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "invalid agent token");
        }
        token.markUsed();
        return new McpPrincipal(token.id(), token.profileName(), tokenHash);
    }

    public static String hash(String raw) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static void requireProfileName(String profileName) {
        if (!HermesProfileName.isValid(profileName)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "profile name is invalid");
        }
    }
}
