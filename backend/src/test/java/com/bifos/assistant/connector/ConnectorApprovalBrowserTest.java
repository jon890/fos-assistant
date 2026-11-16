package com.bifos.assistant.connector;

import com.bifos.assistant.browser.application.BrowserHumanHold;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.browser.application.UserBrowserService;
import com.bifos.assistant.connector.action.application.ConnectorApprovalBrowser;
import com.bifos.assistant.connector.action.domain.ConnectorAction;
import com.bifos.assistant.connector.action.domain.type.ActionStatus;
import com.bifos.assistant.connector.action.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.connection.application.ConnectorCatalogCache;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** 승인 전에 브라우저를 켜는 판정과 실패 코드 바꾸기를 본다. 뜻은 {@code docs/features/browser-cowork/spec.md} 의 REQ2 다. */
class ConnectorApprovalBrowserTest {
    private static final Instant NOW = Instant.parse("2026-11-16T03:00:00Z");
    private static final UUID ACTION = UUID.randomUUID();
    private static final CurrentUser USER = new CurrentUser(7L, "owner@example.com", "주인", 1L, UserRole.MEMBER);

    private final ConnectorActionRepository actions = mock(ConnectorActionRepository.class);
    private final ConnectorCatalogCache catalog = mock(ConnectorCatalogCache.class);
    private final UserBrowserService browsers = mock(UserBrowserService.class);
    private final BrowserHumanHold holds = mock(BrowserHumanHold.class);
    private final ConnectorApprovalBrowser approvalBrowser = new ConnectorApprovalBrowser(actions, catalog, browsers, holds, Clock.fixed(NOW, ZoneOffset.UTC));


    @BeforeEach
    void setUp() {
        when(catalog.find("naver-shopping")).thenReturn(Optional.of(manifest("naver-shopping", true)));
        when(catalog.find("gmail")).thenReturn(Optional.of(manifest("gmail", false)));
    }


    @Test
    @DisplayName("브라우저 플러그인의 기다리는 줄이면 켜고 활동을 기록한다")
    void startsBrowserAndRecordsActivity() {
        row(7L, "naver-shopping", ActionStatus.PENDING, NOW.plus(Duration.ofMinutes(5)));

        approvalBrowser.require(USER, ACTION);

        verify(browsers).ensureRunning(7L);
        verify(browsers).touch(7L);
    }


    @ParameterizedTest
    @EnumSource(value = ErrorCode.class,
        names = {"BROWSER_CAPACITY", "BROWSER_START_FAILED", "BROWSER_BUSY", "BROWSER_STOP_FAILED", "BROWSER_NOT_FOUND"})
    @DisplayName("다시 승인하면 풀릴 수 있는 켜기 실패는 CONNECTOR_BROWSER_UNAVAILABLE 로 바꾼다")
    void mapsRetryableFailures(ErrorCode code) {
        row(7L, "naver-shopping", ActionStatus.PENDING, NOW.plus(Duration.ofMinutes(5)));
        when(browsers.ensureRunning(7L)).thenThrow(new ApiException(code, "failed"));

        assertCode(ErrorCode.CONNECTOR_BROWSER_UNAVAILABLE);
        verify(browsers, never()).touch(anyLong());
    }


    @Test
    @DisplayName("사람이 브라우저를 쥐고 있으면 켜지 않고 CONNECTOR_BROWSER_HELD 로 거절한다. 브라우저를 쓰지 않는 플러그인은 그대로 지난다")
    void refusesWhileHeldByPerson() {
        when(holds.held(7L)).thenReturn(true);
        row(7L, "naver-shopping", ActionStatus.PENDING, NOW.plus(Duration.ofMinutes(5)));

        assertCode(ErrorCode.CONNECTOR_BROWSER_HELD);
        verify(browsers, never()).ensureRunning(anyLong());

        row(7L, "gmail", ActionStatus.PENDING, NOW.plus(Duration.ofMinutes(5)));
        assertThatCode(() -> approvalBrowser.require(USER, ACTION)).doesNotThrowAnyException();
    }


    @Test
    @DisplayName("ApiException 이 아닌 켜기 실패도 CONNECTOR_BROWSER_UNAVAILABLE 로 바꾼다")
    void mapsUnexpectedFailures() {
        row(7L, "naver-shopping", ActionStatus.PENDING, NOW.plus(Duration.ofMinutes(5)));
        when(browsers.ensureRunning(7L)).thenThrow(new IllegalStateException("profile directory"));

        assertCode(ErrorCode.CONNECTOR_BROWSER_UNAVAILABLE);
    }


    @Test
    @DisplayName("기능이 꺼졌으면 BROWSER_DISABLED 를 그대로 둔다")
    void keepsDisabled() {
        row(7L, "naver-shopping", ActionStatus.PENDING, NOW.plus(Duration.ofMinutes(5)));
        when(browsers.ensureRunning(7L)).thenThrow(new ApiException(ErrorCode.BROWSER_DISABLED, "off"));

        assertCode(ErrorCode.BROWSER_DISABLED);
    }


    @Test
    @DisplayName("브라우저를 쓰지 않는 플러그인, 남의 줄, 끝난 줄, 시간이 지난 줄, 없는 줄은 켜지 않는다")
    void skipsRowsThatAreNotWaitingBrowserApprovals() {
        for (Runnable setup : List.<Runnable>of(
            () -> row(7L, "gmail", ActionStatus.PENDING, NOW.plus(Duration.ofMinutes(5))),
            () -> row(8L, "naver-shopping", ActionStatus.PENDING, NOW.plus(Duration.ofMinutes(5))),
            () -> row(7L, "naver-shopping", ActionStatus.SUCCEEDED, NOW.plus(Duration.ofMinutes(5))),
            () -> row(7L, "naver-shopping", ActionStatus.PENDING, NOW.minus(Duration.ofMinutes(1))),
            () -> when(actions.findByPublicId(ACTION)).thenReturn(Optional.empty()))) {
            setup.run();
            assertThatCode(() -> approvalBrowser.require(USER, ACTION)).doesNotThrowAnyException();
        }
        verify(browsers, never()).ensureRunning(anyLong());
    }


    private void assertCode(ErrorCode code) {
        assertThatThrownBy(() -> approvalBrowser.require(USER, ACTION))
            .isInstanceOf(ApiException.class)
            .extracting(ex -> ((ApiException)ex).code())
            .isEqualTo(code);
    }


    private void row(Long userId, String connectorId, ActionStatus status, Instant expiresAt) {
        ConnectorAction action = mock(ConnectorAction.class);
        when(action.userId()).thenReturn(userId);
        when(action.connectorId()).thenReturn(connectorId);
        when(action.status()).thenReturn(status);
        when(action.expiresAt()).thenReturn(expiresAt);
        when(actions.findByPublicId(ACTION)).thenReturn(Optional.of(action));
    }


    private static ConnectorManifest manifest(String id, boolean ownerBrowser) {
        ConnectorManifest base = new ConnectorManifest(id, id, "", List.of(), "check", id, 2, List.of());
        return new ConnectorManifest(base.id(), base.title(), base.description(), base.fields(), base.verifyTool(),
            base.mcpServer(), base.schema(), base.tools(), base.skills(), base.appearance(), base.singleBinding(),
            ownerBrowser, base.ownerBrowserLoginUrl(), base.executionGuard(), base.executionGuardManifestSha256(),
            base.snapshot(), base.unknownErrors(), base.defaultToolPolicy(), base.eventWatch());
    }
}
