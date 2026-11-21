package com.bifos.assistant.hermes.application.dto;

import java.time.Instant;
import java.util.Locale;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 대시보드 OAuth 경로가 주고받는 값이다. 새로 받은 access token 외에 서비스 토큰은 오지 않는다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ConnectorOAuth {

    public enum Mode {
        INSTALL,
        AUTHORIZE;


        public String word() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum VaultAccess {
        CURRENT,
        PENDING;


        public String word() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum Outcome {
        OK,
        GRANT_REVOKED,
        APP_REJECTED,
        SCOPE_DENIED,
        UNAVAILABLE,
        MISSING
    }

    public record Authorization(
        String connectorId, Mode mode, String clientId, String appSlug, String redirectUri, String state, String codeChallenge) {
        @Override
        public String toString() {
            return "Authorization[값 생략]";
        }
    }

    public record Credentials(String clientId, String clientSecret) {
        @Override
        public String toString() {
            return "Credentials[값 생략]";
        }
    }

    public record Exchange(
        String vault, String connectorId, String nonce, Credentials credentials,
        String code, String codeVerifier, String redirectUri, String tokenUrl) {
        @Override
        public String toString() {
            return "Exchange[값 생략]";
        }
    }

    public record Token(Outcome outcome, String accessToken, Instant expiresAt) {
        @Override
        public String toString() {
            return "Token[outcome=" + outcome + "]";
        }
    }
}
