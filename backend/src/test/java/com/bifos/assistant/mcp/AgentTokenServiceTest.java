package com.bifos.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.mcp.application.AgentTokenService;
import com.bifos.assistant.mcp.application.McpPrincipal;
import com.bifos.assistant.mcp.domain.AgentToken;
import com.bifos.assistant.mcp.infra.AgentTokenRepository;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.lang.reflect.Field;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** 장기 토큰 원문이 저장되지 않고, profile 로만 발급되는지 확인한다. */
@SpringBootTest
@ActiveProfiles("test")
class AgentTokenServiceTest {
    @Autowired
    AgentTokenService tokens;

    @Autowired
    AgentTokenRepository tokenRepository;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        tokenRepository.deleteAll();
    }

    @Test
    @DisplayName("발급한 원문은 응답에만 있고 저장된 행에는 없다")
    void rawValueIsOnlyInResponseAndNotInStoredRow() throws IllegalAccessException {
        var issued = tokens.issue("hermes-profile", "hermes");
        AgentToken saved = tokenRepository.findById(issued.token().id()).orElseThrow();
        assertThat(issued.rawToken()).isNotBlank();
        assertThat(saved.tokenHash()).isEqualTo(AgentTokenService.hash(issued.rawToken()));
        assertThat(saved.tokenHash()).doesNotContain(issued.rawToken());
        for (Field field : AgentToken.class.getDeclaredFields()) {
            if (field.getType() == String.class) {
                field.setAccessible(true);
                assertThat((String) field.get(saved)).doesNotContain(issued.rawToken());
            }
        }
    }

    @Test
    @DisplayName("profile 로 발급한 토큰은 사용자를 갖지 않는다")
    void tokenIssuedForProfileHasNoUser() {
        var issued = tokens.issue("hermes-profile", "hermes");
        AgentToken saved = tokenRepository.findById(issued.token().id()).orElseThrow();
        assertThat(issued.profileName()).isEqualTo("hermes-profile");
        assertThat(saved.profileName()).isEqualTo("hermes-profile");
    }

    @Test
    @DisplayName("profile 이름 규칙을 어기면 발급하지 않는다")
    void doesNotIssueWhenProfileNameBreaksRule() {
        String tooLong = "a".repeat(65);
        for (String name : new String[] {"", "Upper", "-leading", "has space", "../escape", tooLong}) {
            assertValidationFailed(() -> tokens.issue(name, "hermes"), name);
        }
        assertValidationFailed(() -> tokens.issue(null, "hermes"), null);
        assertThat(tokens.issue("a".repeat(64), "hermes").profileName())
                .as("64자까지는 받는다")
                .hasSize(64);
    }

    @Test
    @DisplayName("폐기해도 행은 남고 폐기 시각이 채워진다")
    void revokeKeepsRowAndFillsRevokedTime() {
        var issued = tokens.issue("hermes-profile", "hermes");
        tokens.revoke(issued.token().id());
        assertThat(tokenRepository.findById(issued.token().id()))
                .isPresent()
                .get()
                .extracting(AgentToken::revokedAt)
                .isNotNull();
    }

    @Test
    @DisplayName("인증하면 마지막 사용 시각을 갱신하고 profile 을 증명한다")
    void authenticationUpdatesLastUsedTimeAndProvesProfile() {
        var issued = tokens.issue("hermes-profile", "hermes");
        assertThat(tokenRepository.findById(issued.token().id()).orElseThrow().lastUsedAt())
                .isNull();
        McpPrincipal principal = tokens.authenticate(issued.rawToken());
        assertThat(tokenRepository.findById(issued.token().id()).orElseThrow().lastUsedAt())
                .isNotNull();
        assertThat(principal.profileName()).isEqualTo("hermes-profile");
        assertThat(principal.tokenHash()).isEqualTo(AgentTokenService.hash(issued.rawToken()));
        assertThat(principal.toString()).doesNotContain(principal.tokenHash());
    }

    @Test
    @DisplayName("profile 이 빈 토큰은 인증하지 않고 사용 시각을 남기지 않는다")
    void doesNotAuthenticateBlankProfileTokenNorLeaveUsedTime() {
        String raw = "unbound-" + UUID.randomUUID();
        long id = McpCallSigner.insertUnboundToken(jdbc, raw, "unbound");
        assertThatThrownBy(() -> tokens.authenticate(raw))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.UNAUTHENTICATED));
        assertThat(tokenRepository.findById(id).orElseThrow().lastUsedAt()).isNull();
    }

    private static void assertValidationFailed(ThrowingCallable call, String input) {
        assertThatThrownBy(call)
                .as("입력 %s", input)
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }
}
