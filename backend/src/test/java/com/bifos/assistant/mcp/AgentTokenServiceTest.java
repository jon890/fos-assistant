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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** 장기 토큰 원문이 저장되지 않고, profile 로만 발급되는지 확인한다. */
@SpringBootTest
@ActiveProfiles("test")
class AgentTokenServiceTest {
    @Autowired AgentTokenService tokens;
    @Autowired AgentTokenRepository tokenRepository;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void 준비한다() { tokenRepository.deleteAll(); }

    @Test void 발급한_원문은_응답에만_있고_저장된_행에는_없다() throws IllegalAccessException {
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

    @Test void profile_로_발급한_토큰은_사용자를_갖지_않는다() {
        var issued = tokens.issue("hermes-profile", "hermes");
        AgentToken saved = tokenRepository.findById(issued.token().id()).orElseThrow();
        assertThat(issued.profileName()).isEqualTo("hermes-profile");
        assertThat(saved.profileName()).isEqualTo("hermes-profile");
    }

    @Test void profile_이름_규칙을_어기면_발급하지_않는다() {
        String tooLong = "a".repeat(65);
        for (String name : new String[] {"", "Upper", "-leading", "has space", "../escape", tooLong}) {
            assertValidationFailed(() -> tokens.issue(name, "hermes"), name);
        }
        assertValidationFailed(() -> tokens.issue(null, "hermes"), null);
        assertThat(tokens.issue("a".repeat(64), "hermes").profileName()).as("64자까지는 받는다").hasSize(64);
    }

    @Test void 폐기해도_행은_남고_폐기_시각이_채워진다() {
        var issued = tokens.issue("hermes-profile", "hermes");
        tokens.revoke(issued.token().id());
        assertThat(tokenRepository.findById(issued.token().id())).isPresent().get().extracting(AgentToken::revokedAt).isNotNull();
    }

    @Test void 인증하면_마지막_사용_시각을_갱신하고_profile_을_증명한다() {
        var issued = tokens.issue("hermes-profile", "hermes");
        assertThat(tokenRepository.findById(issued.token().id()).orElseThrow().lastUsedAt()).isNull();
        McpPrincipal principal = tokens.authenticate(issued.rawToken());
        assertThat(tokenRepository.findById(issued.token().id()).orElseThrow().lastUsedAt()).isNotNull();
        assertThat(principal.profileName()).isEqualTo("hermes-profile");
        assertThat(principal.tokenHash()).isEqualTo(AgentTokenService.hash(issued.rawToken()));
        assertThat(principal.toString()).doesNotContain(principal.tokenHash());
    }

    @Test void profile_이_빈_토큰은_인증하지_않고_사용_시각을_남기지_않는다() {
        String raw = "unbound-" + UUID.randomUUID();
        long id = McpCallSigner.insertUnboundToken(jdbc, raw, "unbound");
        assertThatThrownBy(() -> tokens.authenticate(raw))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.UNAUTHENTICATED));
        assertThat(tokenRepository.findById(id).orElseThrow().lastUsedAt()).isNull();
    }

    private static void assertValidationFailed(ThrowingCallable call, String input) {
        assertThatThrownBy(call)
                .as("입력 %s", input)
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }
}
