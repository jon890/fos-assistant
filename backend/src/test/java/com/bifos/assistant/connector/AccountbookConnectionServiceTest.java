package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.connector.application.AccountbookConnectionService;
import com.bifos.assistant.connector.application.AccountbookTokenVerifier;
import com.bifos.assistant.connector.application.ConnectionSnapshot;
import com.bifos.assistant.connector.application.ConnectorOperationFailure;
import com.bifos.assistant.connector.domain.ConnectionStatus;
import com.bifos.assistant.connector.infra.AccountbookConnectionRepository;
import com.bifos.assistant.hermes.HermesConnectorClient;
import com.bifos.assistant.hermes.HermesDashboardClient;
import com.bifos.assistant.hermes.HermesToolsetClient;
import com.bifos.assistant.mcp.infra.AgentTokenRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class AccountbookConnectionServiceTest {
    private static final String TOKEN = "fab_" + "a".repeat(43);

    @Autowired
    AccountbookConnectionService service;

    @Autowired
    AccountbookConnectionRepository connections;

    @Autowired
    AgentRepository agents;

    @Autowired
    AgentTokenRepository tokens;

    @Autowired
    AppUserRepository users;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    AccountbookTokenVerifier verifier;

    @MockitoBean
    HermesConnectorClient connector;

    @MockitoBean
    HermesToolsetClient toolsets;

    @MockitoBean
    HermesDashboardClient dashboard;

    @BeforeEach
    void setUp() {
        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        connections.deleteAll();
        agents.deleteAll();
        tokens.deleteAll();
        users.deleteAll();
    }

    @Test
    @DisplayName("외부 등록이 실패해도 새 트랜잭션에서 PENDING 비활성 상태가 남는다")
    void keepsPendingDisabledStateInNewTransactionWhenRegistrationFails() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        when(connector.putEnv(anyString(), eq("ACCOUNTBOOK_API_TOKEN"), anyString()))
                .thenReturn(true);
        doThrow(new IllegalStateException("dashboard unavailable"))
                .when(connector)
                .putEnv(anyString(), eq("ACCOUNTBOOK_API_BASE_URL"), anyString());

        assertThatThrownBy(() -> service.register(user, TOKEN, null))
                .isInstanceOf(ConnectorOperationFailure.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.CONNECTOR_OPERATION_FAILED);

        var stored = connections.findById(user.id()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ConnectionStatus.PENDING);
        assertThat(stored.isDesiredEnabled()).isFalse();
        assertThat(stored.isRestartRequired()).isTrue();
        assertThat(stored.getTokenPrefix()).isNull();
        assertThat(jdbc.queryForObject(
                        "SELECT enabled FROM agent WHERE id = (SELECT agent_id FROM accountbook_connection WHERE user_id = ?)",
                        Boolean.class,
                        user.id()))
                .isFalse();
    }

    @Test
    @DisplayName("profile 생성 실패는 연결 실패로 바꾸지 않고 행을 되돌린다")
    void rollsBackRowWithoutConvertingProfileCreationFailure() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        doThrow(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "profile create failed"))
                .when(dashboard)
                .createProfile(anyString());

        assertThatThrownBy(() -> service.register(user, TOKEN, null))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.HERMES_UNAVAILABLE));

        assertThat(connections.findById(user.id())).isEmpty();
        assertThat(agents.count()).isZero();
    }

    @Test
    @DisplayName("두 사용자는 자기 바인딩으로만 연결을 읽는다")
    void eachUserReadsConnectionOnlyThroughOwnBinding() {
        CurrentUser first = user(UserRole.MEMBER, 1L);
        CurrentUser second = user(UserRole.MEMBER, 1L);

        UUID sameFamily = UUID.randomUUID();
        ConnectionSnapshot firstResult = service.register(first, TOKEN, sameFamily.toString());
        ConnectionSnapshot secondResult = service.register(second, "fab_" + "b".repeat(43), sameFamily.toString());

        assertThat(service.read(first).agentCode()).isEqualTo(firstResult.agentCode());
        assertThat(service.read(first).agentCode()).isNotEqualTo(secondResult.agentCode());
        assertThat(firstResult.familyUuid())
                .isEqualTo(secondResult.familyUuid())
                .isEqualTo(sameFamily);
        assertThat(firstResult.tokenPrefix()).isNotEqualTo(secondResult.tokenPrefix());
        org.mockito.Mockito.verify(verifier).verify(TOKEN, sameFamily);
        org.mockito.Mockito.verify(verifier).verify("fab_" + "b".repeat(43), sameFamily);
    }

    @Test
    @DisplayName("재시작 대기 연결을 확인해도 READY가 되지 않는다")
    void verifyingRestartPendingConnectionDoesNotBecomeReady() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        when(connector.putEnv(anyString(), anyString(), anyString())).thenReturn(true);
        service.register(user, TOKEN, null);
        when(connector.readConnector(anyString()))
                .thenReturn(new HermesConnectorClient.ConnectorState("p", true, true, false));

        ConnectionSnapshot checked = service.check(user);

        assertThat(checked.status()).isEqualTo(ConnectionStatus.PENDING);
        assertThat(checked.restartRequired()).isTrue();
    }

    @Test
    @DisplayName("해제 중 외부 실패는 비활성 PENDING 상태를 남긴다")
    void leavesDisabledPendingStateWhenDisconnectFailsExternally() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        service.register(user, TOKEN, null);
        reset(connector);
        when(connector.deleteEnv(anyString(), eq("ACCOUNTBOOK_API_TOKEN"))).thenReturn(true);
        doThrow(new IllegalStateException("delete failed"))
                .when(connector)
                .deleteEnv(anyString(), eq("ACCOUNTBOOK_FAMILY_UUID"));

        assertThatThrownBy(() -> service.disconnect(user)).isInstanceOf(ConnectorOperationFailure.class);

        var stored = connections.findById(user.id()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ConnectionStatus.PENDING);
        assertThat(stored.isDesiredEnabled()).isFalse();
        assertThat(stored.isRestartRequired()).isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT enabled FROM agent WHERE id = (SELECT agent_id FROM accountbook_connection WHERE user_id = ?)",
                        Boolean.class,
                        user.id()))
                .isFalse();
    }

    @Test
    @DisplayName("활성화 후보가 아닌 연결은 관리자 확인으로 READY가 되지 않는다")
    void adminConfirmDoesNotReadyNonEnableCandidate() {
        CurrentUser member = user(UserRole.MEMBER, 1L);
        CurrentUser admin = user(UserRole.ADMIN, 1L);
        service.register(member, TOKEN, null);
        reset(connector);
        doThrow(new IllegalStateException("delete failed")).when(connector).deleteEnv(anyString(), anyString());
        assertThatThrownBy(() -> service.disconnect(member)).isInstanceOf(ConnectorOperationFailure.class);
        reset(connector);
        when(connector.readConnector(anyString()))
                .thenReturn(new HermesConnectorClient.ConnectorState("p", true, true, false));

        assertThatThrownBy(() -> service.confirmApplied(admin, member.id()))
                .isInstanceOf(ConnectorOperationFailure.class);

        assertThat(connections.findById(member.id()).orElseThrow().getStatus()).isEqualTo(ConnectionStatus.PENDING);
    }

    @Test
    @DisplayName("같은 그룹 ADMIN은 반영 확인으로 READY로 바꾼다")
    void sameGroupAdminConfirmsConnectionToReady() {
        CurrentUser member = user(UserRole.MEMBER, 1L);
        CurrentUser admin = user(UserRole.ADMIN, 1L);
        service.register(member, TOKEN, null);
        when(connector.readConnector(anyString()))
                .thenReturn(new HermesConnectorClient.ConnectorState("p", true, true, false));
        when(connector.probeAccountbook(anyString()))
                .thenReturn(new HermesConnectorClient.ProbeResult(true, List.of("accountbook_search")));

        ConnectionSnapshot confirmed = service.confirmApplied(admin, member.id());

        assertThat(confirmed.status()).isEqualTo(ConnectionStatus.READY);
    }

    @Test
    @DisplayName("해제된 연결은 관리자 반영 확인 뒤에도 DISCONNECTED다")
    void disconnectedConnectionStaysDisconnectedAfterAdminConfirm() {
        CurrentUser member = user(UserRole.MEMBER, 1L);
        CurrentUser admin = user(UserRole.ADMIN, 1L);
        service.register(member, TOKEN, null);
        when(connector.deleteEnv(anyString(), anyString())).thenReturn(true);
        service.disconnect(member);
        when(connector.readConnector(anyString()))
                .thenReturn(new HermesConnectorClient.ConnectorState("p", false, false, false));

        ConnectionSnapshot confirmed = service.confirmApplied(admin, member.id());

        assertThat(confirmed.status()).isEqualTo(ConnectionStatus.DISCONNECTED);
        assertThat(confirmed.restartRequired()).isFalse();
    }

    @Test
    @DisplayName("MEMBER는 연결 반영 완료를 확인할 수 없다")
    void forbidsMemberFromConfirmingConnection() {
        CurrentUser member = user(UserRole.MEMBER, 1L);
        service.register(member, TOKEN, null);

        assertThatThrownBy(() -> service.confirmApplied(member, member.id()))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    @DisplayName("다른 그룹 ADMIN은 연결 반영을 확인할 수 없다")
    void forbidsOtherGroupAdminFromConfirmingConnection() {
        CurrentUser member = user(UserRole.MEMBER, 1L);
        CurrentUser anotherGroupAdmin = user(UserRole.ADMIN, 2L);
        service.register(member, TOKEN, null);

        assertThatThrownBy(() -> service.confirmApplied(anotherGroupAdmin, member.id()))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    @DisplayName("등록은 설치 전에 API 도구 목록을 Control Plane MCP 하나로 줄인다")
    void registrationLimitsApiToolsetToControlPlaneMcpBeforeInstall() {
        CurrentUser user = user(UserRole.MEMBER, 1L);

        ConnectionSnapshot registered = service.register(user, TOKEN, null);

        String profile = profileOf(registered);
        InOrder order = inOrder(toolsets, connector);
        order.verify(toolsets).writeApiServer(profile, List.of("fos-assistant"));
        order.verify(connector).putConnector(profile, true);
    }

    @Test
    @DisplayName("확인에서 내장 도구가 켜져 있으면 목록을 줄이고 READY가 된다")
    void verifyLimitsToolsetAndBecomesReadyWhenBuiltinsEnabled() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        String profile = profileOf(service.register(user, TOKEN, null));
        when(connector.readConnector(anyString()))
                .thenReturn(new HermesConnectorClient.ConnectorState("p", true, true, false));
        when(connector.probeAccountbook(anyString()))
                .thenReturn(new HermesConnectorClient.ProbeResult(true, List.of("accountbook_search")));
        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(List.of("delegation"), List.of());

        ConnectionSnapshot checked = service.check(user);

        verify(toolsets).writeApiServer(profile, List.of("fos-assistant", "accountbook"));
        assertThat(checked.status()).isEqualTo(ConnectionStatus.READY);
    }

    @Test
    @DisplayName("목록을 줄인 뒤에도 내장 도구가 남으면 PENDING이다")
    void staysPendingWhenBuiltinToolsRemainAfterLimiting() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        service.register(user, TOKEN, null);
        when(connector.readConnector(anyString()))
                .thenReturn(new HermesConnectorClient.ConnectorState("p", true, true, false));
        when(connector.probeAccountbook(anyString()))
                .thenReturn(new HermesConnectorClient.ProbeResult(true, List.of("accountbook_search")));
        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(List.of("delegation"));

        ConnectionSnapshot checked = service.check(user);

        assertThat(checked.status()).isEqualTo(ConnectionStatus.PENDING);
    }

    @Test
    @DisplayName("설치가 configured가 아니면 확인에서 목록을 쓰지 않는다")
    void verifyDoesNotWriteToolsetUnlessInstallConfigured() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        String profile = profileOf(service.register(user, TOKEN, null));
        when(connector.readConnector(anyString()))
                .thenReturn(new HermesConnectorClient.ConnectorState("p", true, false, false));
        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(List.of("delegation"));

        ConnectionSnapshot checked = service.check(user);

        verify(toolsets, never()).writeApiServer(profile, List.of("fos-assistant", "accountbook"));
        assertThat(checked.status()).isEqualTo(ConnectionStatus.PENDING);
    }

    @Test
    @DisplayName("관리자 확인도 설치가 configured가 아니면 목록을 쓰지 않고 PENDING으로 둔다")
    void adminConfirmKeepsPendingWithoutToolsetUnlessConfigured() {
        CurrentUser member = user(UserRole.MEMBER, 1L);
        CurrentUser admin = user(UserRole.ADMIN, 1L);
        String profile = profileOf(service.register(member, TOKEN, null));
        when(connector.readConnector(anyString()))
                .thenReturn(new HermesConnectorClient.ConnectorState("p", true, false, false));
        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(List.of("delegation"));

        assertThatThrownBy(() -> service.confirmApplied(admin, member.id()))
                .isInstanceOf(ConnectorOperationFailure.class);

        verify(toolsets, never()).writeApiServer(profile, List.of("fos-assistant", "accountbook"));
        assertThat(connections.findById(member.id()).orElseThrow().getStatus()).isEqualTo(ConnectionStatus.PENDING);
    }

    private String profileOf(ConnectionSnapshot snapshot) {
        return agents.findByCode(snapshot.agentCode()).orElseThrow().hermesProfile();
    }

    private CurrentUser user(UserRole role, long groupId) {
        String suffix = UUID.randomUUID().toString();
        AppUser saved = users.save(AppUser.of("connector-" + suffix + "@example.com", suffix, groupId, role));
        return new CurrentUser(saved.id(), saved.email(), saved.displayName(), saved.groupId(), saved.role());
    }
}
