package com.bifos.assistant.browser.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.browser.application.dto.AgentControlStatus;
import com.bifos.assistant.browser.domain.UserBrowser;
import com.bifos.assistant.browser.infra.UserBrowserRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.user.application.GroupSettingStore;
import com.bifos.assistant.user.domain.GroupSetting;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** 두 스위치의 판정과 저장을 실제 DB 에서 본다(docs/features/agent-browser/design.md 의 「PR1 스위치」). */
@BackendIntegrationTest
class BrowserAgentControlTest {

    private static final CurrentUser MEMBER = new CurrentUser(301L, "kid@example.com", "아이", 1L, UserRole.MEMBER);

    @Autowired
    UserBrowserRepository repository;

    @Autowired
    GroupSettingStore settings;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    private final UserBrowserService browsers = mock(UserBrowserService.class);
    private BrowserAgentControl control;


    @BeforeEach
    void setUp() {
        clean();
        when(browsers.enabled()).thenReturn(true);
        control = new BrowserAgentControl(browsers, repository, settings, Clock.systemUTC(), transactions);
    }


    @AfterEach
    void tearDown() {
        clean();
    }


    @Test
    @DisplayName("기능, 관리자, 브라우저, 사용자 스위치 순서로 판정한다")
    void decidesInOrder() {
        assertThat(control.status(MEMBER)).isEqualTo(AgentControlStatus.NO_BROWSER);
        repository.saveAndFlush(UserBrowser.create(MEMBER.id(), "p301", Instant.now()));
        assertThat(control.status(MEMBER)).isEqualTo(AgentControlStatus.USER_OFF);

        control.setUser(MEMBER, true);
        assertThat(control.status(MEMBER)).isEqualTo(AgentControlStatus.ALLOWED);

        control.setAdmin(MEMBER.groupId(), false);
        assertThat(control.status(MEMBER)).isEqualTo(AgentControlStatus.ADMIN_OFF);

        when(browsers.enabled()).thenReturn(false);
        assertThat(control.status(MEMBER)).isEqualTo(AgentControlStatus.FEATURE_OFF);
    }


    @Test
    @DisplayName("상태 전이가 오래된 엔티티를 저장해도 켜 둔 스위치가 되돌아가지 않는다")
    void entitySaveDoesNotResetSwitch() {
        UserBrowser saved = repository.saveAndFlush(UserBrowser.create(MEMBER.id(), "p301", Instant.now()));
        UserBrowser loaded = repository.findById(saved.id()).orElseThrow();
        control.setUser(MEMBER, true);

        loaded.beginStart(Instant.now());
        repository.saveAndFlush(loaded);

        assertThat(control.userEnabled(MEMBER.id())).isTrue();
    }


    @Test
    @DisplayName("관리자 줄을 읽지 못하면 끈 것으로 본다")
    void unreadableAdminSettingIsOff() {
        settings.write(MEMBER.groupId(), GroupSetting.BROWSER_AGENT_CONTROL, "{not json");
        assertThat(control.adminAllowed(MEMBER.groupId())).isFalse();

        settings.write(MEMBER.groupId(), GroupSetting.BROWSER_AGENT_CONTROL, "{\"version\":2,\"enabled\":true}");
        assertThat(control.adminAllowed(MEMBER.groupId())).isFalse();
    }


    private void clean() {
        jdbc.update("DELETE FROM user_browser");
        jdbc.update("DELETE FROM group_setting WHERE setting_key = 'BROWSER_AGENT_CONTROL'");
    }
}
