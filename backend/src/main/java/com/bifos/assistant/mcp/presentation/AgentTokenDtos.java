package com.bifos.assistant.mcp.presentation;

import com.bifos.assistant.mcp.application.IssuedToken;
import com.bifos.assistant.mcp.application.TokenWithUser;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class AgentTokenDtos {
    private AgentTokenDtos() {}
    /** 토큰은 profile 로만 발급한다. 사용자로 발급하는 길은 없다(ADR-032). */
    public record IssueRequest(@NotBlank @Size(max = 64) String profileName, @NotBlank @Size(max = 100) String label) {}
    /** profile 이 빈 옛 토큰을 묶을 profile 이다. */
    public record BindProfileRequest(@NotBlank @Size(max = 64) String profileName) {}
    public record IssuedTokenResponse(Long id, String profileName, String label, Instant createdAt, String token) {
        static IssuedTokenResponse from(IssuedToken issued) { return new IssuedTokenResponse(issued.token().id(), issued.profileName(), issued.token().label(), issued.token().createdAt(), issued.rawToken()); }
    }
    /** {@code userEmail} 은 profile 이 빈 옛 토큰만 채우고, 묶인 토큰은 null 이다. */
    public record TokenResponse(Long id, String profileName, String userEmail, String label, Instant createdAt, Instant lastUsedAt, Instant revokedAt) {
        static TokenResponse from(TokenWithUser value) { var token = value.token(); return new TokenResponse(token.id(), token.profileName(), value.userEmail(), token.label(), token.createdAt(), token.lastUsedAt(), token.revokedAt()); }
    }
}
