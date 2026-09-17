package com.bifos.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.mcp.application.AgentTokenService;
import com.bifos.assistant.mcp.domain.AgentToken;
import com.bifos.assistant.mcp.infra.AgentTokenRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.lang.reflect.Field;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** 장기 토큰 원문이 저장되지 않고 폐기해도 행이 남는지 확인한다. */
@SpringBootTest
@ActiveProfiles("test")
class AgentTokenServiceTest {
    @Autowired AgentTokenService tokens;
    @Autowired AgentTokenRepository tokenRepository;
    @Autowired AppUserRepository users;
    @BeforeEach void 준비한다() { tokenRepository.deleteAll(); users.deleteAll(); users.save(AppUser.of("admin@example.com", "관리자", 1L, UserRole.ADMIN)); }
    @Test void 발급한_원문은_응답에만_있고_저장된_행에는_없다() throws IllegalAccessException {
        var issued = tokens.issue("admin@example.com", "hermes");
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
    @Test void 폐기해도_행은_남고_폐기_시각이_채워진다() {
        var issued = tokens.issue("admin@example.com", "hermes");
        tokens.revoke(issued.token().id());
        assertThat(tokenRepository.findById(issued.token().id())).isPresent().get().extracting(AgentToken::revokedAt).isNotNull();
    }
    @Test void 인증하면_마지막_사용_시각을_갱신한다() {
        var issued = tokens.issue("admin@example.com", "hermes");
        assertThat(tokenRepository.findById(issued.token().id()).orElseThrow().lastUsedAt()).isNull();
        tokens.authenticate(issued.rawToken());
        assertThat(tokenRepository.findById(issued.token().id()).orElseThrow().lastUsedAt()).isNotNull();
    }
}
