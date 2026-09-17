package com.bifos.assistant.mcp.application;

import com.bifos.assistant.mcp.domain.AgentToken;
import com.bifos.assistant.mcp.infra.AgentTokenRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
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
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AgentTokenService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final AgentTokenRepository tokens;
    private final AppUserRepository users;

    @Transactional
    public IssuedToken issue(String userEmail, String label) {
        AppUser user = users.findByEmail(userEmail).orElseThrow(() -> new ApiException(ErrorCode.MEMORY_NOT_FOUND, "no such user"));
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        AgentToken saved = tokens.save(AgentToken.issue(user.id(), hash(raw), label));
        return new IssuedToken(saved, user.email(), raw);
    }
    public List<TokenWithUser> list() {
        List<AgentToken> allTokens = tokens.findAll();
        Map<Long, AppUser> usersById = users.findAllById(
                        allTokens.stream().map(AgentToken::userId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(AppUser::id, Function.identity()));
        return allTokens.stream()
                .map(token -> {
                    AppUser user = usersById.get(token.userId());
                    return new TokenWithUser(token, user == null ? "" : user.email());
                })
                .toList();
    }
    @Transactional
    public void revoke(Long id) { tokens.findById(id).orElseThrow(() -> new ApiException(ErrorCode.MEMORY_NOT_FOUND, "no such token")).revoke(); }
    @Transactional
    public CurrentUser authenticate(String raw) {
        AgentToken token = tokens.findByTokenHash(hash(raw)).filter(value -> value.revokedAt() == null)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "invalid agent token"));
        AppUser user = users.findById(token.userId()).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "invalid agent token"));
        token.markUsed();
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.familyId(), user.role());
    }
    public static String hash(String raw) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 is unavailable", ex); }
    }
    public record IssuedToken(AgentToken token, String userEmail, String rawToken) {}
    public record TokenWithUser(AgentToken token, String userEmail) {}

}
