package com.bifos.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.mcp.application.AgentTokenService;
import com.bifos.assistant.mcp.application.McpPrincipal;
import com.bifos.assistant.mcp.domain.AgentToken;
import com.bifos.assistant.mcp.infra.AgentTokenRepository;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.lang.reflect.Field;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** 장기 토큰 원문이 저장되지 않고, profile 로만 발급되며, 옛 토큰만 한 번 묶이는지 확인한다. */
@SpringBootTest
@ActiveProfiles("test")
class AgentTokenServiceTest {
    @Autowired AgentTokenService tokens;
    @Autowired AgentTokenRepository tokenRepository;
    @Autowired AppUserRepository users;
    @Autowired JdbcTemplate jdbc;
    private AppUser admin;

    @BeforeEach void 준비한다() { tokenRepository.deleteAll(); users.deleteAll(); admin = users.save(AppUser.of("admin@example.com", "관리자", 1L, UserRole.ADMIN)); }

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
        assertThat(saved.legacyUserId()).isNull();
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
        assertThat(principal.legacyUserId()).isNull();
        assertThat(principal.tokenHash()).isEqualTo(AgentTokenService.hash(issued.rawToken()));
        assertThat(principal.toString()).doesNotContain(principal.tokenHash());
    }

    @Test void 옛_토큰은_한_번만_묶이고_묶인_뒤에는_사용자를_읽지_않는다() {
        String raw = "legacy-" + UUID.randomUUID();
        long id = McpCallSigner.insertLegacyToken(jdbc, admin.id(), raw, "legacy");

        AgentToken bound = tokens.bindProfile(id, "shared-group");

        assertThat(bound.profileName()).isEqualTo("shared-group");
        assertThat(tokenRepository.findById(id).orElseThrow().profileName()).isEqualTo("shared-group");
        assertThat(tokens.authenticate(raw).legacyUserId()).as("묶인 토큰은 사용자를 싣지 않는다").isNull();
        assertValidationFailed(() -> tokens.bindProfile(id, "other-profile"), "두 번째 묶기");
        assertThat(tokenRepository.findById(id).orElseThrow().profileName()).isEqualTo("shared-group");
    }

    @Test void 새로_발급한_토큰도_다른_profile_로_다시_묶지_못한다() {
        var issued = tokens.issue("hermes-profile", "hermes");
        assertValidationFailed(() -> tokens.bindProfile(issued.token().id(), "other-profile"), "발급한 토큰 묶기");
    }

    @Test void 폐기한_옛_토큰은_묶지_못한다() {
        long id = McpCallSigner.insertLegacyToken(jdbc, admin.id(), "legacy-" + UUID.randomUUID(), "legacy");
        tokens.revoke(id);
        assertValidationFailed(() -> tokens.bindProfile(id, "shared-group"), "폐기한 토큰 묶기");
        assertThat(tokenRepository.findById(id).orElseThrow().profileName()).isNull();
    }

    @Test void 묶을_profile_이름도_규칙을_따르고_없는_토큰은_폐기와_같은_오류다() {
        long id = McpCallSigner.insertLegacyToken(jdbc, admin.id(), "legacy-" + UUID.randomUUID(), "legacy");
        assertValidationFailed(() -> tokens.bindProfile(id, "Bad_Name"), "Bad_Name");
        assertThatThrownBy(() -> tokens.bindProfile(-1L, "shared-group"))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.MEMORY_NOT_FOUND));
    }

    @Test void 설정이_거짓이면_profile_이_빈_옛_토큰은_인증하지_않는다() {
        String raw = "legacy-" + UUID.randomUUID();
        long id = McpCallSigner.insertLegacyToken(jdbc, admin.id(), raw, "legacy");
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
