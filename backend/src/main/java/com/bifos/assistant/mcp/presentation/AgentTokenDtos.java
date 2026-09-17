package com.bifos.assistant.mcp.presentation;

import com.bifos.assistant.mcp.application.AgentTokenService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class AgentTokenDtos {
    private AgentTokenDtos() {}
    public record IssueRequest(@NotBlank String userEmail, @NotBlank @Size(max = 100) String label) {}
    public record IssuedTokenResponse(Long id, String userEmail, String label, Instant createdAt, String token) {
        static IssuedTokenResponse from(AgentTokenService.IssuedToken issued) { return new IssuedTokenResponse(issued.token().id(), issued.userEmail(), issued.token().label(), issued.token().createdAt(), issued.rawToken()); }
    }
    public record TokenResponse(Long id, String userEmail, String label, Instant createdAt, Instant lastUsedAt, Instant revokedAt) {
        static TokenResponse from(AgentTokenService.TokenWithUser value) { var token = value.token(); return new TokenResponse(token.id(), value.userEmail(), token.label(), token.createdAt(), token.lastUsedAt(), token.revokedAt()); }
    }
}
