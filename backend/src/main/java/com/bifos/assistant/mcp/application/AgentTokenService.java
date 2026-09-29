package com.bifos.assistant.mcp.application;

import com.bifos.assistant.hermes.HermesProfileName;
import com.bifos.assistant.mcp.domain.AgentToken;
import com.bifos.assistant.mcp.infra.AgentTokenRepository;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * MCP 토큰의 발급, profile 묶기, 목록, 폐기, 인증을 맡는다.
 *
 * <p>토큰은 profile 만 증명한다(ADR-032). profile 이 빈 옛 토큰은 {@link McpProperties#legacyUserTokens()} 가
 * 참일 때만 인증한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AgentTokenService {
    private static final Logger log = LoggerFactory.getLogger(AgentTokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final AgentTokenRepository tokens;
    private final AppUserRepository users;
    private final McpProperties properties;

    /**
     * profile 에 묶인 새 토큰을 발급한다. 그 profile 에 에이전트가 있는지는 보지 않는다.
     *
     * <p>부르는 쪽 트랜잭션과 떼어 곧바로 커밋한다. profile 을 만드는 쪽은 행 잠금을 쥔 트랜잭션 안에서
     * 부르는데, 발급이 그 트랜잭션에 묶이면 커밋 전 몇 초 동안 Hermes 가 새 토큰으로 붙어도 인증이 실패한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IssuedToken issue(String profileName, String label) {
        requireProfileName(profileName);
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        AgentToken saved = tokens.save(AgentToken.issueFor(profileName, hash(raw), label));
        return new IssuedToken(saved, raw);
    }

    /** profile 이 빈 옛 토큰을 그 profile 에 묶는다. 이미 묶였거나 폐기된 토큰은 {@code VALIDATION_FAILED} 다. */
    @Transactional
    public AgentToken bindProfile(Long id, String profileName) {
        requireProfileName(profileName);
        AgentToken token = tokens.findById(id).orElseThrow(() -> new ApiException(ErrorCode.MEMORY_NOT_FOUND, "no such token"));
        token.bindProfile(profileName);
        return token;
    }

    /** 옛 토큰에만 그 토큰을 발급한 사용자의 메일을 붙인다. profile 이 묶인 토큰은 메일이 비어 있다. */
    public List<TokenWithUser> list() {
        List<AgentToken> allTokens = tokens.findAll();
        Map<Long, AppUser> usersById = users.findAllById(
                        allTokens.stream().filter(token -> token.profileName() == null)
                                .map(AgentToken::legacyUserId).filter(Objects::nonNull).distinct().toList())
                .stream()
                .collect(Collectors.toMap(AppUser::id, Function.identity()));
        return allTokens.stream()
                .map(token -> {
                    AppUser user = token.profileName() == null ? usersById.get(token.legacyUserId()) : null;
                    return new TokenWithUser(token, user == null ? null : user.email());
                })
                .toList();
    }

    @Transactional
    public void revoke(Long id) { tokens.findById(id).orElseThrow(() -> new ApiException(ErrorCode.MEMORY_NOT_FOUND, "no such token")).revoke(); }

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
     * <p>profile 이 묶인 토큰은 {@code user_id} 를 읽지 않는다. profile 이 빈 옛 토큰은 설정이 거짓이면 거절하고,
     * 참이면 그 사용자가 있는지 확인한 뒤 경고 로그를 남기고 통과시킨다.
     *
     * @throws ApiException {@link ErrorCode#UNAUTHENTICATED}. 없거나 폐기됐거나 받지 않는 옛 토큰일 때
     */
    @Transactional
    public McpPrincipal authenticate(String raw) {
        String tokenHash = hash(raw);
        AgentToken token = tokens.findByTokenHash(tokenHash).filter(value -> value.revokedAt() == null)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "invalid agent token"));
        Long legacyUserId = null;
        if (token.profileName() == null) {
            if (!properties.legacyUserTokens()) {
                log.warn("profile 이 묶이지 않은 옛 MCP 토큰을 거절했다 tokenId={}", token.id());
                throw new ApiException(ErrorCode.UNAUTHENTICATED, "invalid agent token");
            }
            legacyUserId = token.legacyUserId();
            if (legacyUserId == null || users.findById(legacyUserId).isEmpty()) {
                throw new ApiException(ErrorCode.UNAUTHENTICATED, "invalid agent token");
            }
            log.warn("profile 이 묶이지 않은 옛 MCP 토큰이 쓰였다 tokenId={}", token.id());
        }
        token.markUsed();
        return new McpPrincipal(token.id(), token.profileName(), legacyUserId, tokenHash);
    }

    public static String hash(String raw) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 is unavailable", ex); }
    }

    private static void requireProfileName(String profileName) {
        if (!HermesProfileName.isValid(profileName)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "profile name is invalid");
        }
    }
}
