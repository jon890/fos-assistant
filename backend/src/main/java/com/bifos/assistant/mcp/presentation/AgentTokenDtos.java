package com.bifos.assistant.mcp.presentation;

import com.bifos.assistant.mcp.application.IssuedToken;
import com.bifos.assistant.mcp.domain.AgentToken;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class AgentTokenDtos {
    private AgentTokenDtos() {}
    /** 토큰은 profile 로만 발급한다. 사용자로 발급하는 길은 없다(ADR-032). */
    public record IssueRequest(@NotBlank @Size(max = 64) String profileName, @NotBlank @Size(max = 100) String label) {}
    public record IssuedTokenResponse(Long id, String profileName, String label, Instant createdAt, String token) {
        static IssuedTokenResponse from(IssuedToken issued) { return new IssuedTokenResponse(issued.token().id(), issued.profileName(), issued.token().label(), issued.token().createdAt(), issued.rawToken()); }
    }
    public record TokenResponse(Long id, String profileName, String label, Instant createdAt, Instant lastUsedAt, Instant revokedAt) {
        static TokenResponse from(AgentToken token) { return new TokenResponse(token.id(), token.profileName(), token.label(), token.createdAt(), token.lastUsedAt(), token.revokedAt()); }
    }
}
