package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.application.AgentLifecycleService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.connector.application.ConnectorActionService;
import com.bifos.assistant.connector.application.ConnectorCallLimiter;
import com.bifos.assistant.connector.application.ConnectorConnectionService;
import com.bifos.assistant.connector.application.ConnectorProperties;
import com.bifos.assistant.connector.application.model.AdminConnectionSnapshot;
import com.bifos.assistant.connector.application.model.ConnectionSnapshot;
import com.bifos.assistant.connector.application.model.ConnectorOperationFailure;
import com.bifos.assistant.connector.application.model.ConnectorOption;
import com.bifos.assistant.connector.application.model.ConnectorSummary;
import com.bifos.assistant.connector.application.model.ConnectorToolSummary;
import com.bifos.assistant.connector.domain.ConnectorConnection;
import com.bifos.assistant.connector.domain.type.ConnectionStatus;
import com.bifos.assistant.connector.domain.type.ToolApproval;
import com.bifos.assistant.connector.domain.type.ToolRisk;
import com.bifos.assistant.connector.infra.ConnectorConnectionRepository;
import com.bifos.assistant.hermes.HermesConnectorClient;
import com.bifos.assistant.hermes.HermesConnectorClient.ConnectorState;
import com.bifos.assistant.hermes.HermesConnectorClient.InstallResult;
import com.bifos.assistant.hermes.HermesConnectorClient.ProbeResult;
import com.bifos.assistant.hermes.HermesDashboardClient;
import com.bifos.assistant.hermes.HermesToolsetClient;
import com.bifos.assistant.hermes.dto.CallResult;
import com.bifos.assistant.hermes.dto.ConnectorCallError;
import com.bifos.assistant.hermes.dto.ConnectorField;
import com.bifos.assistant.hermes.dto.ConnectorFieldOptions;
import com.bifos.assistant.hermes.dto.ConnectorManifest;
import com.bifos.assistant.hermes.dto.ConnectorTool;
import com.bifos.assistant.mcp.infra.AgentTokenRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@ActiveProfiles("test")
class ConnectorConnectionServiceTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String DEMO = "demo-notes";
    private static final String PIN = "demo-pin";
    private static final String TOKEN = "demo_ok_0123456789";
    private static final String BAD_TOKEN = "demo_bad_0123456789";
    private static final Map<String, String> VALUES = Map.of("token", TOKEN);
    private static final ConnectorManifest DEMO_MANIFEST = new ConnectorManifest(
            DEMO,
            "검사용 메모",
            "검사에서만 쓰는 커넥터입니다.",
            List.of(
                    new ConnectorField("token", "DEMO_TOKEN", "토큰", "", true, true, "^demo_[a-z]+_[0-9]{10}$", null),
                    new ConnectorField(
                            "scope",
                            "DEMO_SCOPE",
                            "범위",
                            "",
                            false,
                            false,
                            null,
                            new ConnectorFieldOptions("list_scopes", "scopes", "id", "name", true))),
            "list_scopes",
            "demo",
            List.of(),
            false,
            1,
            List.of());
    /** 이미지 도구와 사진 받기를 선언한 커넥터다. 번호와 서버 이름은 {@link #DEMO_MANIFEST} 와 같다. */
    private static final ConnectorManifest VISION_MANIFEST = new ConnectorManifest(
            DEMO,
            DEMO_MANIFEST.title(),
            DEMO_MANIFEST.description(),
            DEMO_MANIFEST.fields(),
            "list_scopes",
            "demo",
            List.of("vision"),
            true,
            1,
            List.of());
    /** 형식이 없는 비밀 칸 하나만 가진 커넥터다. 비밀값의 길이 경계를 보는 데 쓴다. */
    private static final ConnectorManifest PIN_MANIFEST = new ConnectorManifest(
            PIN,
            "검사용 번호",
            "",
            List.of(new ConnectorField("pin", "DEMO_PIN", "번호", "", true, true, null, null)),
            "check_pin",
            "pin",
            List.of(),
            false,
            1,
            List.of());
    /** 도구마다 정책을 선언한 커넥터다. 번호와 칸과 서버 이름은 {@link #DEMO_MANIFEST} 와 같다. */
    private static final ConnectorManifest POLICY_MANIFEST = policyManifest(List.of(
            new ConnectorTool("list_scopes", "READ", "none", null, null),
            new ConnectorTool("write_note", "WRITE", "required", "메모 쓰기", null),
            new ConnectorTool("purge_notes", "DESTRUCTIVE", "always", null, null)));

    @Autowired
    ConnectorConnectionService service;

    @Autowired
    ConnectorConnectionRepository connections;

    @Autowired
    ConnectorActionService approvals;

    @Autowired
    AgentRepository agents;

    @Autowired
    AgentTokenRepository tokens;

    @Autowired
    AgentLifecycleService lifecycle;

    @Autowired
    PlatformTransactionManager transactionManager;

    @MockitoSpyBean
    AppUserRepository users;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    HermesConnectorClient connector;

    @MockitoBean
    HermesToolsetClient toolsets;

    @MockitoBean
    HermesDashboardClient dashboard;

    @BeforeEach
    void setUp() {
        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(List.of());
        when(connector.putConnector(anyString(), anyString(), anyBoolean(), anyString()))
                .thenReturn(new InstallResult(false, false));
        when(connector.readCatalog()).thenReturn(List.of(DEMO_MANIFEST, PIN_MANIFEST));
        when(connector.call(anyString(), anyString(), anyMap()))
                .thenReturn(CallResult.success(MAPPER.readTree(
                        "{\"scopes\":[{\"id\":\"a\",\"name\":\"범위 A\"},{\"id\":\"b\",\"name\":\"범위 B\"}]}")));
    }

    @AfterEach
    void tearDown() {
        connections.deleteAll();
        agents.deleteAll();
        tokens.deleteAll();
        users.deleteAll();
    }

    @Test
    @DisplayName("등록은 확인, 잠금, env, 설치 순서로 반영하고 도구 목록을 쓰지 않는다")
    void registrationVerifiesThenLocksThenWritesEnvThenInstallsWithoutWritingToolset() {
        CurrentUser user = user(UserRole.MEMBER, 1L);

        ConnectionSnapshot registered = service.register(user, DEMO, VALUES);

        String profile = profileOf(registered);
        InOrder order = inOrder(connector, users);
        order.verify(connector).call(DEMO, "list_scopes", VALUES);
        order.verify(users).findByIdForUpdate(user.id());
        order.verify(connector).putEnv(profile, "DEMO_TOKEN", TOKEN);
        order.verify(connector).deleteEnv(profile, "DEMO_SCOPE");
        order.verify(connector).putConnector(profile, DEMO, true, "u" + user.id());
        verify(toolsets, never()).writeApiServer(any(), any(), any());
        assertThat(registered.status()).isEqualTo(ConnectionStatus.PENDING);
        assertThat(stored(user).desiredEnabled()).isTrue();
        Agent agent = agents.findByCode(registered.agentCode()).orElseThrow();
        assertThat(agent.name()).isEqualTo("검사용 메모");
        // 선언이 없는 커넥터의 연결용 에이전트는 사진을 받지 않는다.
        assertThat(agent.acceptsAttachments()).isFalse();
    }

    @Test
    @DisplayName("manifest 가 toolset 을 선언해도 등록은 도구 목록을 쓰지 않고, 사진은 연결 확인 전이라 받지 않는다")
    void registrationLeavesDeclaredToolsetsToInstallAndDefersAttachments() {
        when(connector.readCatalog()).thenReturn(List.of(VISION_MANIFEST));
        CurrentUser user = user(UserRole.MEMBER, 1L);

        ConnectionSnapshot registered = service.register(user, DEMO, VALUES);

        String profile = profileOf(registered);
        // 목록은 설치가 커넥터의 MCP 서버와 선언한 toolset 으로 쓴다.
        verify(connector).putConnector(profile, DEMO, true, "u" + user.id());
        verify(toolsets, never()).writeApiServer(any(), any(), any());
        assertThat(agents.findByCode(registered.agentCode()).orElseThrow().acceptsAttachments())
                .isFalse();
    }

    @Test
    @DisplayName("이미 연결된 에이전트는 연결 확인에서 새로 선언된 toolset 과 사진 받기를 받는다")
    void checkAppliesNewlyDeclaredToolsetsToExistingConnection() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        ConnectionSnapshot registered = service.register(user, DEMO, VALUES);
        String profile = profileOf(registered);
        installed(true, true);
        when(connector.probe(profile, "demo")).thenReturn(new ProbeResult(true, List.of("list_scopes")));
        assertThat(service.check(user, DEMO).status()).isEqualTo(ConnectionStatus.READY);
        assertThat(agents.findByCode(registered.agentCode()).orElseThrow().acceptsAttachments())
                .isFalse();

        when(connector.readCatalog()).thenReturn(List.of(VISION_MANIFEST));
        // 다시 보낸 설치가 목록에 선언한 toolset 을 더한 뒤다.
        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(List.of("vision"));

        ConnectionSnapshot checked = service.check(user, DEMO);

        // 등록의 설치와 연결 확인마다 다시 보낸 설치다.
        verify(connector, times(3)).putConnector(eq(profile), eq(DEMO), eq(true), anyString());
        verify(toolsets, never()).writeApiServer(any(), any(), any());
        assertThat(checked.status()).isEqualTo(ConnectionStatus.READY);
        assertThat(agents.findByCode(registered.agentCode()).orElseThrow().acceptsAttachments())
                .isTrue();
    }

    @Test
    @DisplayName("관리자 반영 완료도 설치를 다시 보내고 선언한 toolset 이 켜졌으면 사진 받기를 옮긴다")
    void adminConfirmResendsInstallAndAcceptsAttachmentsWhenDeclaredToolsetsEnabled() {
        when(connector.readCatalog()).thenReturn(List.of(VISION_MANIFEST));
        CurrentUser member = user(UserRole.MEMBER, 1L);
        CurrentUser admin = user(UserRole.ADMIN, 1L);
        ConnectionSnapshot registered = service.register(member, DEMO, VALUES);
        String profile = profileOf(registered);
        installed(true, true);
        when(connector.probe(profile, "demo")).thenReturn(new ProbeResult(true, List.of("list_scopes")));
        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(List.of("vision"));

        ConnectionSnapshot confirmed = service.confirmApplied(admin, DEMO, member.id());

        // 등록의 설치와 다시 보낸 설치다.
        verify(connector, times(2)).putConnector(eq(profile), eq(DEMO), eq(true), anyString());
        verify(toolsets, never()).writeApiServer(any(), any(), any());
        assertThat(confirmed.status()).isEqualTo(ConnectionStatus.READY);
        assertThat(agents.findByCode(registered.agentCode()).orElseThrow().acceptsAttachments())
                .isTrue();
    }

    @Test
    @DisplayName("선언한 toolset 이 설치를 다시 보낸 뒤에도 켜지지 않으면 목록을 쓰지 않고 PENDING이다")
    void staysPendingWhenDeclaredToolsetIsNotApplied() {
        when(connector.readCatalog()).thenReturn(List.of(VISION_MANIFEST));
        CurrentUser user = user(UserRole.MEMBER, 1L);
        String profile = profileOf(service.register(user, DEMO, VALUES));
        installed(true, true);
        when(connector.probe(profile, "demo")).thenReturn(new ProbeResult(true, List.of("list_scopes")));

        ConnectionSnapshot checked = service.check(user, DEMO);

        verify(connector, times(2)).putConnector(eq(profile), eq(DEMO), eq(true), anyString());
        verify(toolsets, never()).writeApiServer(any(), any(), any());
        assertThat(checked.status()).isEqualTo(ConnectionStatus.PENDING);
        assertThat(agentEnabled(user)).isFalse();
        // 사진 단추는 있는데 이미지 도구가 없는 상태를 만들지 않는다.
        assertThat(agents.findByCode(checked.agentCode()).orElseThrow().acceptsAttachments())
                .isFalse();
    }

    @Test
    @DisplayName("사진을 받던 연결도 vision 이 꺼져 다시 켜지지 않으면 READY 가 아니고 사진을 받지 않는다")
    void stopsAcceptingAttachmentsWhenDeclaredToolsetGoesMissing() {
        when(connector.readCatalog()).thenReturn(List.of(VISION_MANIFEST));
        CurrentUser user = user(UserRole.MEMBER, 1L);
        ConnectionSnapshot registered = service.register(user, DEMO, VALUES);
        String profile = profileOf(registered);
        installed(true, true);
        when(connector.probe(profile, "demo")).thenReturn(new ProbeResult(true, List.of("list_scopes")));
        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(List.of("vision"));
        assertThat(service.check(user, DEMO).status()).isEqualTo(ConnectionStatus.READY);
        assertThat(agents.findByCode(registered.agentCode()).orElseThrow().acceptsAttachments())
                .isTrue();

        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(List.of());

        assertThat(service.check(user, DEMO).status()).isEqualTo(ConnectionStatus.PENDING);
        assertThat(agents.findByCode(registered.agentCode()).orElseThrow().acceptsAttachments())
                .isFalse();
    }

    @Test
    @DisplayName("사진을 받던 연결이 꺼지는 경로마다 연결용 에이전트가 사진을 받지 않는다")
    void disabledConnectorAgentNeverAcceptsAttachments() {
        when(connector.readCatalog()).thenReturn(List.of(VISION_MANIFEST));
        CurrentUser member = user(UserRole.MEMBER, 1L);
        CurrentUser admin = user(UserRole.ADMIN, 1L);
        ConnectionSnapshot registered = service.register(member, DEMO, VALUES);
        String profile = profileOf(registered);
        when(connector.probe(profile, "demo")).thenReturn(new ProbeResult(true, List.of("list_scopes")));
        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(List.of("vision"));
        Runnable makeReady = () -> {
            installed(true, true);
            assertThat(service.check(member, DEMO).status()).isEqualTo(ConnectionStatus.READY);
            assertThat(agents.findByCode(registered.agentCode()).orElseThrow().acceptsAttachments())
                    .isTrue();
        };
        Runnable assertOff = () -> {
            assertThat(agentEnabled(member)).isFalse();
            assertThat(agents.findByCode(registered.agentCode()).orElseThrow().acceptsAttachments())
                    .isFalse();
        };

        // 연결 확인의 조기 반환: 설치가 configured 가 아니다.
        makeReady.run();
        installed(true, false);
        assertThat(service.check(member, DEMO).status()).isEqualTo(ConnectionStatus.PENDING);
        assertOff.run();

        // 반영 완료의 단락 평가: 설치가 꺼져 있어 도구 확인에 닿지 않는다.
        makeReady.run();
        installed(false, false);
        assertThatThrownBy(() -> service.confirmApplied(admin, DEMO, member.id()))
                .isInstanceOf(ConnectorOperationFailure.class);
        assertOff.run();

        // 다시 등록의 실패.
        makeReady.run();
        doThrow(new IllegalStateException()).when(connector).putEnv(anyString(), anyString(), anyString());
        assertThatThrownBy(() -> service.register(member, DEMO, VALUES)).isInstanceOf(ConnectorOperationFailure.class);
        assertOff.run();
        doReturn(false).when(connector).putEnv(anyString(), anyString(), anyString());
        service.register(member, DEMO, VALUES);

        // 해제의 실패.
        makeReady.run();
        doThrow(new IllegalStateException()).when(connector).putConnector(eq(profile), eq(DEMO), eq(false), anyString());
        assertThatThrownBy(() -> service.disconnect(member, DEMO)).isInstanceOf(ConnectorOperationFailure.class);
        assertOff.run();
    }

    @Test
    @DisplayName("연결 확인의 probe 가 실패하면 사진을 받지 않는 것으로 남는다")
    void probeFailureLeavesAttachmentsOff() {
        when(connector.readCatalog()).thenReturn(List.of(VISION_MANIFEST));
        CurrentUser user = user(UserRole.MEMBER, 1L);
        ConnectionSnapshot registered = service.register(user, DEMO, VALUES);
        installed(true, true);
        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(List.of("vision"));
        doThrow(new IllegalStateException()).when(connector).probe(anyString(), anyString());

        assertThatThrownBy(() -> service.check(user, DEMO)).isInstanceOf(ConnectorOperationFailure.class);

        assertThat(stored(user).status()).isEqualTo(ConnectionStatus.PENDING);
        assertThat(agents.findByCode(registered.agentCode()).orElseThrow().acceptsAttachments())
                .isFalse();
    }

    @Test
    @DisplayName("선언과 켜진 도구가 같으면 연결 확인이 도구 목록을 쓰지 않고 READY다")
    void checkBecomesReadyWithoutWritingToolsetWhenDeclaredToolsetsMatch() {
        when(connector.readCatalog()).thenReturn(List.of(VISION_MANIFEST));
        CurrentUser user = user(UserRole.MEMBER, 1L);
        String profile = profileOf(service.register(user, DEMO, VALUES));
        installed(true, true);
        when(connector.probe(profile, "demo")).thenReturn(new ProbeResult(true, List.of("list_scopes")));
        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(List.of("vision"));

        assertThat(service.check(user, DEMO).status()).isEqualTo(ConnectionStatus.READY);

        verify(toolsets, never()).writeApiServer(any(), any(), any());
    }

    @Test
    @DisplayName("manifest 로 열 수 없는 toolset 을 선언한 커넥터는 없는 커넥터다")
    void manifestDeclaringClosedToolsetsIsLeftOut() {
        for (List<String> declared : List.of(List.of("terminal"), List.of("vision", "file"), List.of("sight"))) {
            when(connector.readCatalog())
                    .thenReturn(List.of(
                            new ConnectorManifest(
                                    DEMO,
                                    "검사용 메모",
                                    "",
                                    DEMO_MANIFEST.fields(),
                                    "list_scopes",
                                    "demo",
                                    declared,
                                    false,
                                    1,
                                    List.of()),
                            PIN_MANIFEST));
            CurrentUser user = user(UserRole.MEMBER, 1L);

            assertThat(service.catalog(user)).extracting(ConnectorSummary::id).containsExactly(PIN);
            assertCode(() -> service.register(user, DEMO, VALUES), ErrorCode.CONNECTOR_NOT_FOUND);
        }
        verify(toolsets, never()).writeApiServer(anyString(), anyList(), anyString());
    }

    @Test
    @DisplayName("vision 없이 사진 받기를 선언한 커넥터는 없는 커넥터다")
    void manifestAcceptingAttachmentsWithoutVisionIsLeftOut() {
        when(connector.readCatalog())
                .thenReturn(List.of(new ConnectorManifest(
                        DEMO,
                        "검사용 메모",
                        "",
                        DEMO_MANIFEST.fields(),
                        "list_scopes",
                        "demo",
                        List.of(),
                        true,
                        1,
                        List.of())));
        CurrentUser user = user(UserRole.MEMBER, 1L);

        assertCode(() -> service.register(user, DEMO, VALUES), ErrorCode.CONNECTOR_NOT_FOUND);
    }

    @Test
    @DisplayName("연결 확인은 설치 요청을 다시 보내 지침과 도구 목록을 맞추고 그 재시작 값은 쓰지 않는다")
    void checkResendsInstallAndIgnoresItsRestartAnswer() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        String profile = profileOf(service.register(user, DEMO, VALUES));
        installed(true, true);
        when(connector.probe(profile, "demo")).thenReturn(new ProbeResult(true, List.of("list_scopes")));
        when(connector.putConnector(eq(profile), eq(DEMO), eq(true), anyString())).thenReturn(new InstallResult(true, false));

        ConnectionSnapshot checked = service.check(user, DEMO);

        InOrder order = inOrder(connector);
        order.verify(connector).readConnector(profile, DEMO);
        order.verify(connector).putConnector(eq(profile), eq(DEMO), eq(true), anyString());
        // 다시 보낸 뒤의 설치 상태를 읽고 나서 probe 한다.
        order.verify(connector).readConnector(profile, DEMO);
        order.verify(connector).probe(profile, "demo");
        verify(toolsets, never()).writeApiServer(any(), any(), any());
        assertThat(checked.status()).isEqualTo(ConnectionStatus.READY);
        assertThat(checked.restartRequired()).isFalse();
    }

    @Test
    @DisplayName("연결 확인의 설치 요청이 실패하면 PENDING 을 남기고 연결 실패로 끝난다")
    void checkStaysPendingWhenReinstallFails() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        String profile = profileOf(service.register(user, DEMO, VALUES));
        installed(true, true);
        doThrow(new IllegalStateException()).when(connector).putConnector(eq(profile), eq(DEMO), eq(true), anyString());

        assertThatThrownBy(() -> service.check(user, DEMO)).isInstanceOf(ConnectorOperationFailure.class);

        assertThat(stored(user).status()).isEqualTo(ConnectionStatus.PENDING);
        assertThat(agentEnabled(user)).isFalse();
    }

    @Test
    @DisplayName("해제하면 연결용 에이전트가 사진을 받지 않는다")
    void disconnectStopsAcceptingAttachments() {
        when(connector.readCatalog()).thenReturn(List.of(VISION_MANIFEST));
        CurrentUser user = user(UserRole.MEMBER, 1L);
        ConnectionSnapshot registered = service.register(user, DEMO, VALUES);

        service.disconnect(user, DEMO);

        assertThat(agents.findByCode(registered.agentCode()).orElseThrow().acceptsAttachments())
                .isFalse();
    }

    @Test
    @DisplayName("확인 도구가 실패하면 공통 어휘의 오류 코드로 끝나고 아무것도 저장하지 않는다")
    void verifyFailureMapsToErrorCodeAndStoresNothing() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        Map<String, String> values = Map.of("token", BAD_TOKEN);

        assertVerifyFailure(
                user, values, ConnectorCallError.CREDENTIAL_REJECTED, ErrorCode.CONNECTOR_CREDENTIAL_REJECTED);
        assertVerifyFailure(user, values, ConnectorCallError.FORBIDDEN, ErrorCode.CONNECTOR_FORBIDDEN);
        assertVerifyFailure(user, values, ConnectorCallError.INVALID_INPUT, ErrorCode.VALIDATION_FAILED);
        assertVerifyFailure(user, values, ConnectorCallError.UNAVAILABLE, ErrorCode.CONNECTOR_UNAVAILABLE);
        doThrow(new IllegalStateException()).when(connector).call(anyString(), anyString(), anyMap());
        assertCode(() -> service.register(user, DEMO, values), ErrorCode.CONNECTOR_UNAVAILABLE);

        assertThat(connections.count()).isZero();
        assertThat(agents.count()).isZero();
        verify(users, never()).findByIdForUpdate(anyLong());
        verify(connector, never()).putEnv(anyString(), anyString(), anyString());
        verify(connector, never()).putConnector(anyString(), anyString(), anyBoolean(), anyString());
    }

    @Test
    @DisplayName("모르는 키, 필수 칸 누락, 형식 불일치는 확인 도구를 부르기 전에 거절한다")
    void rejectsUnknownKeyMissingRequiredAndPatternMismatchBeforeVerify() {
        CurrentUser user = user(UserRole.MEMBER, 1L);

        assertCode(
                () -> service.register(user, DEMO, Map.of("token", TOKEN, "other", "x")), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.register(user, DEMO, Map.of("scope", "a")), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.register(user, DEMO, Map.of("token", " ")), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.register(user, DEMO, null), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.register(user, DEMO, Map.of("token", "demo_ok_123")), ErrorCode.VALIDATION_FAILED);
        assertCode(
                () -> service.register(user, DEMO, Map.of("token", TOKEN, "scope", "a\nOTHER=b")),
                ErrorCode.VALIDATION_FAILED);

        verify(connector, never()).call(anyString(), anyString(), anyMap());
        assertThat(connections.count()).isZero();
    }

    @Test
    @DisplayName("카탈로그에 없고 연결도 없는 커넥터는 CONNECTOR_NOT_FOUND 다")
    void unknownConnectorIsNotFound() {
        CurrentUser user = user(UserRole.MEMBER, 1L);

        assertCode(() -> service.read(user, "no-such"), ErrorCode.CONNECTOR_NOT_FOUND);
        assertCode(() -> service.register(user, "no-such", VALUES), ErrorCode.CONNECTOR_NOT_FOUND);
        assertCode(() -> service.options(user, "no-such", "scope", VALUES), ErrorCode.CONNECTOR_NOT_FOUND);
        assertCode(() -> service.check(user, "no-such"), ErrorCode.CONNECTOR_NOT_FOUND);
        assertCode(() -> service.disconnect(user, "no-such"), ErrorCode.CONNECTOR_NOT_FOUND);
        assertCode(() -> service.disconnect(user, DEMO), ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("등록 전의 연결은 빈 DISCONNECTED 로 읽힌다")
    void readsEmptyDisconnectedBeforeRegistration() {
        CurrentUser user = user(UserRole.MEMBER, 1L);

        ConnectionSnapshot read = service.read(user, DEMO);

        assertThat(read)
                .isEqualTo(new ConnectionSnapshot(
                        DEMO, ConnectionStatus.DISCONNECTED, Map.of(), Map.of(), false, null, null, 0));
    }

    @Test
    @DisplayName("16자 이상인 비밀 칸은 앞 4자만 저장하고 DB 의 fields 에 원문이 없다")
    void storesOnlyFirstFourCharactersOfSecretFields() throws Exception {
        CurrentUser user = user(UserRole.MEMBER, 1L);

        ConnectionSnapshot registered = service.register(user, DEMO, Map.of("token", TOKEN, "scope", "a"));

        assertThat(registered.secretPrefixes()).isEqualTo(Map.of("token", "demo"));
        assertThat(registered.values()).isEqualTo(Map.of("scope", "a"));
        String column = fieldsColumn(user, DEMO);
        assertThat(column).doesNotContain(TOKEN);
        assertThat(MAPPER.readTree(column))
                .isEqualTo(MAPPER.readTree("{\"values\":{\"scope\":\"a\"},\"secretPrefixes\":{\"token\":\"demo\"}}"));
        verify(connector).putEnv(profileOf(registered), "DEMO_SCOPE", "a");
    }

    @Test
    @DisplayName("15자 비밀값은 앞부분도 저장하지 않고 16자는 앞 4자를 저장한다")
    void doesNotStorePrefixOfFifteenCharacterSecretButStoresSixteenCharacterPrefix() {
        CurrentUser fifteen = user(UserRole.MEMBER, 1L);
        CurrentUser sixteen = user(UserRole.MEMBER, 1L);
        String secret15 = "abcdefghijklmno";
        String secret16 = "abcdefghijklmnop";

        ConnectionSnapshot short15 = service.register(fifteen, PIN, Map.of("pin", secret15));
        ConnectionSnapshot long16 = service.register(sixteen, PIN, Map.of("pin", secret16));

        assertThat(short15.secretPrefixes()).isEmpty();
        assertThat(fieldsColumn(fifteen, PIN)).doesNotContain("abcd");
        assertThat(long16.secretPrefixes()).isEqualTo(Map.of("pin", "abcd"));
        assertThat(fieldsColumn(sixteen, PIN)).doesNotContain("abcde");
    }

    @Test
    @DisplayName("앞 4자 자리에서 대리 쌍이 갈리는 비밀값은 그 앞 3자만 저장한다")
    void cutsSecretPrefixBeforeSplitSurrogatePair() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        // 넷째와 다섯째 char 가 한 글자의 대리 쌍이다.
        String secret = "abc" + new String(Character.toChars(0x1F600)) + "0123456789ab";

        ConnectionSnapshot registered = service.register(user, PIN, Map.of("pin", secret));

        assertThat(registered.secretPrefixes()).isEqualTo(Map.of("pin", "abc"));
    }

    @Test
    @DisplayName("외부 등록이 실패해도 새 트랜잭션에서 PENDING 비활성 상태가 남는다")
    void keepsPendingDisabledStateInNewTransactionWhenRegistrationFails() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        when(connector.putEnv(anyString(), eq("DEMO_TOKEN"), anyString())).thenReturn(true);
        doThrow(new IllegalStateException("dashboard unavailable"))
                .when(connector)
                .putConnector(anyString(), anyString(), anyBoolean(), anyString());

        assertThatThrownBy(() -> service.register(user, DEMO, VALUES))
                .isInstanceOf(ConnectorOperationFailure.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.CONNECTOR_OPERATION_FAILED);

        ConnectorConnection stored = stored(user);
        assertThat(stored.status()).isEqualTo(ConnectionStatus.PENDING);
        assertThat(stored.desiredEnabled()).isFalse();
        assertThat(stored.restartRequired()).isTrue();
        assertThat(stored.fields().secretPrefixes()).isEmpty();
        assertThat(agentEnabled(user)).isFalse();
    }

    @Test
    @DisplayName("env 와 설치 응답의 재시작 필요는 하나만 참이어도 누적된다")
    void accumulatesRestartRequiredAcrossEnvAndInstallResponses() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        when(connector.deleteEnv(anyString(), eq("DEMO_SCOPE"))).thenReturn(true);

        ConnectionSnapshot registered = service.register(user, DEMO, VALUES);

        assertThat(registered.restartRequired()).isTrue();
        assertThat(stored(user).restartRequired()).isTrue();
    }

    @Test
    @DisplayName("profile 생성 실패는 연결 실패로 바꾸지 않고 행을 되돌린다")
    void rollsBackRowWithoutConvertingProfileCreationFailure() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        doThrow(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "profile create failed"))
                .when(dashboard)
                .createProfile(anyString());

        assertCode(() -> service.register(user, DEMO, VALUES), ErrorCode.HERMES_UNAVAILABLE);

        assertThat(connections.findByUserIdAndConnectorId(user.id(), DEMO)).isEmpty();
        assertThat(agents.count()).isZero();
    }

    @Test
    @DisplayName("두 사용자는 자기 바인딩으로만 연결을 읽는다")
    void eachUserReadsConnectionOnlyThroughOwnBinding() {
        CurrentUser first = user(UserRole.MEMBER, 1L);
        CurrentUser second = user(UserRole.MEMBER, 1L);
        Map<String, String> firstValues = Map.of("token", TOKEN, "scope", "a");
        Map<String, String> secondValues = Map.of("token", "demo_zz_0123456789", "scope", "a");

        ConnectionSnapshot firstResult = service.register(first, DEMO, firstValues);
        ConnectionSnapshot secondResult = service.register(second, DEMO, secondValues);

        assertThat(service.read(first, DEMO).agentCode()).isEqualTo(firstResult.agentCode());
        assertThat(service.read(first, DEMO).agentCode()).isNotEqualTo(secondResult.agentCode());
        assertThat(service.read(second, DEMO).secretPrefixes()).isEqualTo(Map.of("token", "demo"));
        assertThat(firstResult.values()).isEqualTo(secondResult.values()).isEqualTo(Map.of("scope", "a"));
        verify(connector).call(DEMO, "list_scopes", firstValues);
        verify(connector).call(DEMO, "list_scopes", secondValues);
    }

    @Test
    @DisplayName("한 사용자는 커넥터마다 연결과 전용 에이전트를 따로 갖는다")
    void oneUserHasSeparateConnectionAndAgentPerConnector() {
        CurrentUser user = user(UserRole.MEMBER, 1L);

        ConnectionSnapshot demo = service.register(user, DEMO, VALUES);
        ConnectionSnapshot pin = service.register(user, PIN, Map.of("pin", "123456789"));

        assertThat(demo.agentCode()).isNotEqualTo(pin.agentCode());
        assertThat(service.catalog(user))
                .extracting(ConnectorSummary::id, ConnectorSummary::myStatus)
                .containsExactly(tuple(DEMO, ConnectionStatus.PENDING), tuple(PIN, ConnectionStatus.PENDING));
    }

    @Test
    @DisplayName("카탈로그는 칸 선언과 내 상태를 주고 등록 전에는 DISCONNECTED 다")
    void catalogListsFieldsAndMyStatus() {
        CurrentUser user = user(UserRole.MEMBER, 1L);

        ConnectorSummary demo = service.catalog(user).get(0);

        assertThat(demo.id()).isEqualTo(DEMO);
        assertThat(demo.title()).isEqualTo("검사용 메모");
        assertThat(demo.myStatus()).isEqualTo(ConnectionStatus.DISCONNECTED);
        assertThat(demo.available()).isTrue();
        assertThat(demo.fields().get(0).key()).isEqualTo("token");
        assertThat(demo.fields().get(0).secret()).isTrue();
        assertThat(demo.fields().get(0).hasOptions()).isFalse();
        assertThat(demo.fields().get(1).hasOptions()).isTrue();
        assertThat(demo.fields().get(1).autoSelectSingle()).isTrue();
        assertThat(demo.toString()).doesNotContain("DEMO_TOKEN", "DEMO_SCOPE", "list_scopes");
    }

    @Test
    @DisplayName("카탈로그에서 빠졌지만 해제하지 않은 내 연결은 쓸 수 없는 항목으로 함께 나온다")
    void catalogAppendsRemovedButConnectedConnectorAsUnavailable() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        CurrentUser other = user(UserRole.MEMBER, 1L);
        service.register(user, DEMO, VALUES);
        service.register(other, PIN, Map.of("pin", "123456789"));
        when(connector.readCatalog()).thenReturn(List.of(PIN_MANIFEST));

        List<ConnectorSummary> listed = service.catalog(user);

        assertThat(listed)
                .containsExactly(
                        new ConnectorSummary(
                                PIN,
                                "검사용 번호",
                                "",
                                service.catalog(other).get(0).fields(),
                                List.of(),
                                ConnectionStatus.DISCONNECTED,
                                true),
                        new ConnectorSummary(
                                DEMO, "검사용 메모", "", List.of(), List.of(), ConnectionStatus.PENDING, false));
    }

    @Test
    @DisplayName("카탈로그에서 빠진 연결을 해제하면 목록에서 사라진다")
    void catalogDropsRemovedConnectorOnceDisconnected() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        service.register(user, DEMO, VALUES);
        when(connector.readCatalog()).thenReturn(List.of());
        assertThat(service.catalog(user)).extracting(ConnectorSummary::id).containsExactly(DEMO);

        service.disconnect(user, DEMO);

        assertThat(service.catalog(user)).isEmpty();
    }

    @Test
    @DisplayName("확인 도구는 트랜잭션 밖에서 부르고 그 사이에 끼어든 해제 뒤에도 등록의 상태 전이가 지켜진다")
    void verifyRunsOutsideTransactionAndInterleavedDisconnectKeepsTransitionRules() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        service.register(user, DEMO, VALUES);
        List<Boolean> transactionActive = new ArrayList<>();
        Map<String, String> replaced = Map.of("token", "demo_zz_0123456789");
        when(connector.call(DEMO, "list_scopes", replaced)).thenAnswer(invocation -> {
            transactionActive.add(TransactionSynchronizationManager.isActualTransactionActive());
            // 확인이 도는 동안 같은 사용자의 해제가 먼저 끝난다.
            assertThat(service.disconnect(user, DEMO).status()).isEqualTo(ConnectionStatus.DISCONNECTED);
            return CallResult.success(MAPPER.readTree("{\"scopes\":[]}"));
        });

        ConnectionSnapshot registered = service.register(user, DEMO, replaced);

        assertThat(transactionActive).containsExactly(false);
        assertThat(registered.status()).isEqualTo(ConnectionStatus.PENDING);
        assertThat(registered.secretPrefixes()).isEqualTo(Map.of("token", "demo"));
        ConnectorConnection stored = stored(user);
        assertThat(stored.status()).isEqualTo(ConnectionStatus.PENDING);
        assertThat(stored.desiredEnabled()).isTrue();
        assertThat(stored.fields().secretPrefixes()).isEqualTo(Map.of("token", "demo"));
        assertThat(agentEnabled(user)).isFalse();
        assertThat(connections.count()).isEqualTo(1);
        assertThat(agents.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("처음 등록의 확인 사이에 같은 사용자의 다른 등록이 끼어도 연결과 에이전트는 하나다")
    void interleavedFirstRegistrationsShareOneConnectionAndAgent() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        // 기본 한도는 같은 사용자의 겹친 등록을 거절한다. 동시 한도를 올린 설정에서도 행이 하나임을 본다.
        ConnectorConnectionService relaxed = serviceLimitedTo(2, 1000);
        Map<String, String> outer = Map.of("token", "demo_zz_0123456789");
        when(connector.call(DEMO, "list_scopes", outer)).thenAnswer(invocation -> {
            relaxed.register(user, DEMO, VALUES);
            return CallResult.success(MAPPER.readTree("{\"scopes\":[]}"));
        });

        ConnectionSnapshot registered = relaxed.register(user, DEMO, outer);

        assertThat(connections.count()).isEqualTo(1);
        assertThat(agents.count()).isEqualTo(1);
        assertThat(registered.secretPrefixes()).isEqualTo(Map.of("token", "demo"));
        assertThat(stored(user).desiredEnabled()).isTrue();
    }

    @Test
    @DisplayName("카탈로그를 읽지 못하면 CONNECTOR_UNAVAILABLE 이다")
    void catalogFailureIsUnavailable() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        doThrow(new IllegalStateException()).when(connector).readCatalog();

        assertCode(() -> service.catalog(user), ErrorCode.CONNECTOR_UNAVAILABLE);
        assertCode(() -> service.register(user, DEMO, VALUES), ErrorCode.CONNECTOR_UNAVAILABLE);
    }

    @Test
    @DisplayName("선택지는 작성 중인 값으로 도구를 불러 항목을 꺼내고 아무것도 저장하지 않는다")
    void optionsCallToolWithCandidateValuesAndStoreNothing() {
        CurrentUser user = user(UserRole.MEMBER, 1L);

        List<ConnectorOption> options = service.options(user, DEMO, "scope", VALUES);

        assertThat(options).containsExactly(new ConnectorOption("a", "범위 A"), new ConnectorOption("b", "범위 B"));
        verify(connector).call(DEMO, "list_scopes", VALUES);
        assertThat(connections.count()).isZero();
        assertThat(agents.count()).isZero();
    }

    @Test
    @DisplayName("선택지가 없는 칸과 선택지 도구의 실패는 정해진 오류 코드로 끝난다")
    void optionsRejectFieldWithoutOptionsAndMapToolFailure() {
        CurrentUser user = user(UserRole.MEMBER, 1L);

        assertCode(() -> service.options(user, DEMO, "token", VALUES), ErrorCode.VALIDATION_FAILED);
        verify(connector, never()).call(anyString(), anyString(), anyMap());

        when(connector.call(anyString(), anyString(), anyMap()))
                .thenReturn(CallResult.failure(ConnectorCallError.CREDENTIAL_REJECTED));
        assertCode(() -> service.options(user, DEMO, "scope", VALUES), ErrorCode.CONNECTOR_CREDENTIAL_REJECTED);

        when(connector.call(anyString(), anyString(), anyMap()))
                .thenReturn(CallResult.success(MAPPER.readTree("{\"other\":[]}")));
        assertCode(() -> service.options(user, DEMO, "scope", VALUES), ErrorCode.CONNECTOR_UNAVAILABLE);
    }

    @Test
    @DisplayName("재시작 대기 연결을 확인해도 READY가 되지 않는다")
    void verifyingRestartPendingConnectionDoesNotBecomeReady() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        when(connector.putEnv(anyString(), anyString(), anyString())).thenReturn(true);
        service.register(user, DEMO, VALUES);
        installed(true, true);

        ConnectionSnapshot checked = service.check(user, DEMO);

        assertThat(checked.status()).isEqualTo(ConnectionStatus.PENDING);
        assertThat(checked.restartRequired()).isTrue();
    }

    @Test
    @DisplayName("해제는 칸마다 env 를 지운 뒤 설치를 끄고 칸 값을 비운다")
    void disconnectDeletesEachEnvThenUninstallsAndClearsFields() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        String profile = profileOf(service.register(user, DEMO, Map.of("token", TOKEN, "scope", "a")));
        when(connector.deleteEnv(anyString(), eq("DEMO_TOKEN"))).thenReturn(true);

        ConnectionSnapshot disconnected = service.disconnect(user, DEMO);

        InOrder order = inOrder(connector);
        order.verify(connector).deleteEnv(profile, "DEMO_TOKEN");
        order.verify(connector).deleteEnv(profile, "DEMO_SCOPE");
        order.verify(connector).putConnector(eq(profile), eq(DEMO), eq(false), anyString());
        assertThat(disconnected.status()).isEqualTo(ConnectionStatus.DISCONNECTED);
        assertThat(disconnected.restartRequired()).isTrue();
        assertThat(disconnected.secretPrefixes()).isEmpty();
        assertThat(disconnected.values()).isEmpty();
        assertThat(MAPPER.readTree(fieldsColumn(user, DEMO)))
                .isEqualTo(MAPPER.readTree("{\"values\":{},\"secretPrefixes\":{}}"));
        assertThat(agentEnabled(user)).isFalse();
    }

    @Test
    @DisplayName("카탈로그에서 빠진 커넥터는 읽기와 해제만 되고 해제는 설치만 끈 뒤 재시작 대기로 둔다")
    void removedConnectorCanOnlyBeReadAndDisconnectedWithoutEnvDeletion() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        String profile = profileOf(service.register(user, DEMO, VALUES));
        when(connector.readCatalog()).thenReturn(List.of(PIN_MANIFEST));

        assertThat(service.read(user, DEMO).secretPrefixes()).isEqualTo(Map.of("token", "demo"));
        assertCode(() -> service.register(user, DEMO, VALUES), ErrorCode.CONNECTOR_NOT_FOUND);
        assertCode(() -> service.options(user, DEMO, "scope", VALUES), ErrorCode.CONNECTOR_NOT_FOUND);

        ConnectionSnapshot disconnected = service.disconnect(user, DEMO);

        verify(connector, never()).deleteEnv(profile, "DEMO_TOKEN");
        verify(connector).putConnector(eq(profile), eq(DEMO), eq(false), anyString());
        assertThat(disconnected.status()).isEqualTo(ConnectionStatus.DISCONNECTED);
        assertThat(disconnected.restartRequired()).isTrue();
        assertThat(disconnected.secretPrefixes()).isEmpty();
        assertThat(service.read(user, DEMO).status()).isEqualTo(ConnectionStatus.DISCONNECTED);
    }

    @Test
    @DisplayName("카탈로그에서 빠진 커넥터의 해제도 관리자 반영 완료로 재시작 대기가 풀린다")
    void adminConfirmClearsRestartOfRemovedConnectorDisconnect() {
        CurrentUser member = user(UserRole.MEMBER, 1L);
        CurrentUser admin = user(UserRole.ADMIN, 1L);
        String profile = profileOf(service.register(member, DEMO, VALUES));
        when(connector.readCatalog()).thenReturn(List.of());
        service.disconnect(member, DEMO);
        // 대시보드의 목록에 그 커넥터가 없을 때 클라이언트가 돌려주는 모양이다.
        when(connector.readConnector(profile, DEMO)).thenReturn(new ConnectorState(profile, false, false, false, true));

        ConnectionSnapshot confirmed = service.confirmApplied(admin, DEMO, member.id());

        assertThat(confirmed.status()).isEqualTo(ConnectionStatus.DISCONNECTED);
        assertThat(confirmed.restartRequired()).isFalse();
        assertThat(service.check(member, DEMO).status()).isEqualTo(ConnectionStatus.DISCONNECTED);
    }

    @Test
    @DisplayName("env 단계에서 실패해 설치 전 PENDING 으로 남은 연결도 카탈로그에서 빠진 뒤 해제와 반영 완료가 끝난다")
    void pendingBeforeInstallCanBeDisconnectedAfterConnectorIsRemoved() {
        CurrentUser member = user(UserRole.MEMBER, 1L);
        CurrentUser admin = user(UserRole.ADMIN, 1L);
        doThrow(new IllegalStateException()).when(connector).putEnv(anyString(), eq("DEMO_TOKEN"), anyString());
        assertThatThrownBy(() -> service.register(member, DEMO, VALUES)).isInstanceOf(ConnectorOperationFailure.class);
        String profile = agents.findAll().get(0).hermesProfile();
        verify(connector, never()).putConnector(anyString(), anyString(), anyBoolean(), anyString());
        when(connector.readCatalog()).thenReturn(List.of());
        // 대시보드는 모르는 plugin 의 해제를 바뀐 것 없는 성공으로 답한다.
        when(connector.putConnector(eq(profile), eq(DEMO), eq(false), anyString())).thenReturn(new InstallResult(false, false));
        when(connector.readConnector(profile, DEMO)).thenReturn(new ConnectorState(profile, false, false, false, true));

        ConnectionSnapshot disconnected = service.disconnect(member, DEMO);

        assertThat(disconnected.status()).isEqualTo(ConnectionStatus.DISCONNECTED);
        assertThat(disconnected.restartRequired()).isTrue();
        verify(connector, never()).deleteEnv(anyString(), anyString());

        ConnectionSnapshot confirmed = service.confirmApplied(admin, DEMO, member.id());

        assertThat(confirmed.status()).isEqualTo(ConnectionStatus.DISCONNECTED);
        assertThat(confirmed.restartRequired()).isFalse();
        assertThat(service.catalog(member)).isEmpty();
    }

    @Test
    @DisplayName("비밀 칸은 4096자까지 받고 넘으면 확인 도구를 부르기 전에 거절한다")
    void acceptsSecretUpTo4096CharactersAndRejectsLongerBeforeVerify() {
        CurrentUser user = user(UserRole.MEMBER, 1L);

        assertCode(() -> service.register(user, PIN, Map.of("pin", "1".repeat(4097))), ErrorCode.VALIDATION_FAILED);
        verify(connector, never()).call(anyString(), anyString(), anyMap());
        assertThat(connections.count()).isZero();

        ConnectionSnapshot registered = service.register(user, PIN, Map.of("pin", "1".repeat(4096)));

        assertThat(registered.secretPrefixes()).isEqualTo(Map.of("pin", "1111"));
    }

    @Test
    @DisplayName("저장할 칸 값이 fields 열 크기를 넘으면 외부에 반영하기 전에 거절하고 딱 맞으면 저장한다")
    void rejectsFieldsLongerThanColumnBeforeExternalCallsAndStoresExactFit() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        List<ConnectorField> fields = new ArrayList<>();
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 1; index <= 8; index++) {
            fields.add(new ConnectorField("k" + index, "DEMO_K" + index, "칸", "", false, true, null, null));
            values.put("k" + index, "v".repeat(500));
        }
        when(connector.readCatalog())
                .thenReturn(List.of(new ConnectorManifest(
                        "demo-wide", "넓은 칸", "", fields, "check", "wide", List.of(), false, 1, List.of())));
        // {"values":{"k1":"…",…},"secretPrefixes":{}} 에서 값 말고 드는 글자 수는 33 + 칸마다 7 + 쉼표 7 이다.
        int lastLength = 4000 - (33 + 8 * 7 + 7) - 7 * 500;
        values.put("k8", "v".repeat(lastLength + 1));

        assertCode(() -> service.register(user, "demo-wide", values), ErrorCode.VALIDATION_FAILED);
        verify(connector, never()).call(anyString(), anyString(), anyMap());
        verify(connector, never()).putEnv(anyString(), anyString(), anyString());
        assertThat(connections.count()).isZero();

        values.put("k8", "v".repeat(lastLength));
        service.register(user, "demo-wide", values);

        assertThat(fieldsColumn(user, "demo-wide")).hasSize(4000);
    }

    @Test
    @DisplayName("해제 중 외부 실패는 비활성 PENDING 상태를 남긴다")
    void leavesDisabledPendingStateWhenDisconnectFailsExternally() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        service.register(user, DEMO, VALUES);
        when(connector.deleteEnv(anyString(), eq("DEMO_TOKEN"))).thenReturn(true);
        doThrow(new IllegalStateException("delete failed")).when(connector).deleteEnv(anyString(), eq("DEMO_SCOPE"));

        assertThatThrownBy(() -> service.disconnect(user, DEMO)).isInstanceOf(ConnectorOperationFailure.class);

        ConnectorConnection stored = stored(user);
        assertThat(stored.status()).isEqualTo(ConnectionStatus.PENDING);
        assertThat(stored.desiredEnabled()).isFalse();
        assertThat(stored.restartRequired()).isTrue();
        assertThat(agentEnabled(user)).isFalse();
    }

    @Test
    @DisplayName("활성화 후보가 아닌 연결은 관리자 확인으로 READY가 되지 않는다")
    void adminConfirmDoesNotReadyNonEnableCandidate() {
        CurrentUser member = user(UserRole.MEMBER, 1L);
        CurrentUser admin = user(UserRole.ADMIN, 1L);
        service.register(member, DEMO, VALUES);
        doThrow(new IllegalStateException("delete failed")).when(connector).deleteEnv(anyString(), anyString());
        assertThatThrownBy(() -> service.disconnect(member, DEMO)).isInstanceOf(ConnectorOperationFailure.class);
        installed(true, true);

        assertThatThrownBy(() -> service.confirmApplied(admin, DEMO, member.id()))
                .isInstanceOf(ConnectorOperationFailure.class);

        assertThat(stored(member).status()).isEqualTo(ConnectionStatus.PENDING);
    }

    @Test
    @DisplayName("같은 그룹 ADMIN은 반영 확인으로 READY로 바꾼다")
    void sameGroupAdminConfirmsConnectionToReady() {
        CurrentUser member = user(UserRole.MEMBER, 1L);
        CurrentUser admin = user(UserRole.ADMIN, 1L);
        when(connector.putEnv(anyString(), anyString(), anyString())).thenReturn(true);
        String profile = profileOf(service.register(member, DEMO, VALUES));
        installed(true, true);
        when(connector.probe(profile, "demo")).thenReturn(new ProbeResult(true, List.of("list_scopes")));

        ConnectionSnapshot confirmed = service.confirmApplied(admin, DEMO, member.id());

        assertThat(confirmed.status()).isEqualTo(ConnectionStatus.READY);
        assertThat(confirmed.restartRequired()).isFalse();
        assertThat(agentEnabled(member)).isTrue();
    }

    @Test
    @DisplayName("해제된 연결은 관리자 반영 확인 뒤에도 DISCONNECTED다")
    void disconnectedConnectionStaysDisconnectedAfterAdminConfirm() {
        CurrentUser member = user(UserRole.MEMBER, 1L);
        CurrentUser admin = user(UserRole.ADMIN, 1L);
        service.register(member, DEMO, VALUES);
        when(connector.deleteEnv(anyString(), anyString())).thenReturn(true);
        service.disconnect(member, DEMO);
        installed(false, false);

        ConnectionSnapshot confirmed = service.confirmApplied(admin, DEMO, member.id());

        assertThat(confirmed.status()).isEqualTo(ConnectionStatus.DISCONNECTED);
        assertThat(confirmed.restartRequired()).isFalse();
    }

    @Test
    @DisplayName("MEMBER는 연결 반영 완료를 확인할 수 없다")
    void forbidsMemberFromConfirmingConnection() {
        CurrentUser member = user(UserRole.MEMBER, 1L);
        service.register(member, DEMO, VALUES);

        assertCode(() -> service.confirmApplied(member, DEMO, member.id()), ErrorCode.FORBIDDEN);
        assertCode(() -> service.listForAdmin(member), ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("다른 그룹 ADMIN은 연결 반영을 확인할 수 없다")
    void forbidsOtherGroupAdminFromConfirmingConnection() {
        CurrentUser member = user(UserRole.MEMBER, 1L);
        CurrentUser anotherGroupAdmin = user(UserRole.ADMIN, 2L);
        service.register(member, DEMO, VALUES);

        assertCode(() -> service.confirmApplied(anotherGroupAdmin, DEMO, member.id()), ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("관리자 목록은 같은 그룹의 연결만 이름과 함께 준다")
    void adminListShowsOnlySameGroupConnectionsWithNames() {
        CurrentUser member = user(UserRole.MEMBER, 1L);
        CurrentUser outsider = user(UserRole.MEMBER, 2L);
        CurrentUser admin = user(UserRole.ADMIN, 1L);
        ConnectionSnapshot registered = service.register(member, DEMO, VALUES);
        service.register(outsider, DEMO, VALUES);

        List<AdminConnectionSnapshot> listed = service.listForAdmin(admin);

        assertThat(listed)
                .containsExactly(new AdminConnectionSnapshot(
                        DEMO,
                        member.id(),
                        member.displayName(),
                        ConnectionStatus.PENDING,
                        registered.agentCode(),
                        false,
                        0));
    }

    @Test
    @DisplayName("configured 인 설치에 선언 밖의 내장 도구가 켜져 있으면 목록을 쓰지 않고 PENDING이다")
    void staysPendingWithoutWritingToolsetWhenUndeclaredBuiltinsEnabled() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        String profile = profileOf(service.register(user, DEMO, VALUES));
        installed(true, true);
        when(connector.probe(profile, "demo")).thenReturn(new ProbeResult(true, List.of("list_scopes")));
        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(List.of("delegation"));

        ConnectionSnapshot checked = service.check(user, DEMO);

        verify(toolsets, never()).writeApiServer(any(), any(), any());
        // 등록의 설치와 다시 보낸 설치다.
        verify(connector, times(2)).putConnector(eq(profile), eq(DEMO), eq(true), anyString());
        assertThat(checked.status()).isEqualTo(ConnectionStatus.PENDING);
        assertThat(agentEnabled(user)).isFalse();
    }

    @Test
    @DisplayName("내장 도구가 켜져 있지 않으면 확인에서 READY가 된다")
    void checkBecomesReadyWhenNoBuiltinsEnabled() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        String profile = profileOf(service.register(user, DEMO, VALUES));
        installed(true, true);
        when(connector.probe(profile, "demo")).thenReturn(new ProbeResult(true, List.of("list_scopes")));

        ConnectionSnapshot checked = service.check(user, DEMO);

        verify(toolsets, never()).writeApiServer(any(), any(), any());
        assertThat(checked.status()).isEqualTo(ConnectionStatus.READY);
        assertThat(agentEnabled(user)).isTrue();
    }

    @Test
    @DisplayName("이전 판이 설치해 configured 가 아닌 연결은 확인이 설치를 다시 보내고 다시 읽어 READY가 된다")
    void checkReinstallsWhenInstallIsNotConfigured() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        String profile = profileOf(service.register(user, DEMO, VALUES));
        when(connector.readConnector(anyString(), eq(DEMO)))
                .thenReturn(
                        new ConnectorState("p", true, false, false, true),
                        new ConnectorState("p", true, true, false, true));
        when(connector.probe(profile, "demo")).thenReturn(new ProbeResult(true, List.of("list_scopes")));

        ConnectionSnapshot checked = service.check(user, DEMO);

        // 등록의 설치와 다시 보낸 설치다.
        verify(connector, times(2)).putConnector(eq(profile), eq(DEMO), eq(true), anyString());
        verify(toolsets, never()).writeApiServer(any(), any(), any());
        assertThat(checked.status()).isEqualTo(ConnectionStatus.READY);
        assertThat(checked.restartRequired()).isFalse();
        assertThat(agentEnabled(user)).isTrue();
    }

    @Test
    @DisplayName("카탈로그에서 빠진 커넥터는 확인에서 설치를 다시 보내지 않고 PENDING이다")
    void checkDoesNotReinstallConnectorRemovedFromCatalog() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        service.register(user, DEMO, VALUES);
        installed(true, false);
        when(connector.readCatalog()).thenReturn(List.of(PIN_MANIFEST));

        ConnectionSnapshot checked = service.check(user, DEMO);

        verify(connector, times(1)).putConnector(anyString(), anyString(), anyBoolean(), anyString());
        assertThat(checked.status()).isEqualTo(ConnectionStatus.PENDING);
    }

    @Test
    @DisplayName("확인 중 카탈로그 조회가 실패하면 설치를 다시 보내지 않고 PENDING 을 남기며 연결 실패로 끝난다")
    void checkLeavesPendingWhenCatalogReadFailsBeforeReinstall() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        service.register(user, DEMO, VALUES);
        installed(true, false);
        doThrow(new IllegalStateException("dashboard unavailable"))
                .when(connector)
                .readCatalog();

        assertThatThrownBy(() -> service.check(user, DEMO)).isInstanceOf(ConnectorOperationFailure.class);

        assertThat(stored(user).status()).isEqualTo(ConnectionStatus.PENDING);
        assertThat(agentEnabled(user)).isFalse();
        // 등록의 설치 한 번뿐이다.
        verify(connector, times(1)).putConnector(anyString(), anyString(), anyBoolean(), anyString());
        verify(connector, never()).probe(anyString(), anyString());
    }

    @Test
    @DisplayName("확인 중 다시 설치가 실패하면 PENDING 을 남기고 연결 실패로 끝난다")
    void checkLeavesPendingWhenReinstallFails() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        service.register(user, DEMO, VALUES);
        installed(true, false);
        doThrow(new IllegalStateException("dashboard unavailable"))
                .when(connector)
                .putConnector(anyString(), anyString(), anyBoolean(), anyString());

        assertThatThrownBy(() -> service.check(user, DEMO)).isInstanceOf(ConnectorOperationFailure.class);

        assertThat(stored(user).status()).isEqualTo(ConnectionStatus.PENDING);
        assertThat(agentEnabled(user)).isFalse();
        verify(connector, never()).probe(anyString(), anyString());
    }

    @Test
    @DisplayName("관리자 반영 완료는 옛 목록의 설치를 다시 보내고 그 재시작 값을 쓰지 않으며 다시 읽은 설치가 configured 이면 READY다")
    void confirmAppliedReinstallsOldAllowlistAndIgnoresItsRestartAnswer() {
        CurrentUser member = user(UserRole.MEMBER, 1L);
        CurrentUser admin = user(UserRole.ADMIN, 1L);
        String profile = profileOf(service.register(member, DEMO, VALUES));
        // 이전 판이 쓴 목록이라 configured 가 아니다. 다시 보낸 설치가 목록을 맞춘 뒤에는 configured 다.
        when(connector.readConnector(anyString(), eq(DEMO)))
                .thenReturn(
                        new ConnectorState("p", true, false, false, true),
                        new ConnectorState("p", true, true, false, true));
        when(connector.putConnector(eq(profile), eq(DEMO), eq(true), anyString())).thenReturn(new InstallResult(true, false));
        when(connector.probe(profile, "demo")).thenReturn(new ProbeResult(true, List.of("list_scopes")));

        ConnectionSnapshot confirmed = service.confirmApplied(admin, DEMO, member.id());

        // 등록의 설치와 다시 보낸 설치다.
        verify(connector, times(2)).putConnector(eq(profile), eq(DEMO), eq(true), anyString());
        verify(toolsets, never()).writeApiServer(any(), any(), any());
        assertThat(confirmed.status()).isEqualTo(ConnectionStatus.READY);
        assertThat(confirmed.restartRequired()).isFalse();
        assertThat(agentEnabled(member)).isTrue();
    }

    @Test
    @DisplayName("probe 가 도구를 하나도 보이지 않으면 PENDING이다")
    void staysPendingWhenProbeShowsNoTools() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        String profile = profileOf(service.register(user, DEMO, VALUES));
        installed(true, true);
        when(connector.probe(profile, "demo")).thenReturn(new ProbeResult(true, List.of()));

        assertThat(service.check(user, DEMO).status()).isEqualTo(ConnectionStatus.PENDING);
    }

    @Test
    @DisplayName("재시작 대기인 연결은 확인에서 설치를 다시 보내지 않는다")
    void checkDoesNotReinstallRestartPendingConnection() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        when(connector.putEnv(anyString(), anyString(), anyString())).thenReturn(true);
        service.register(user, DEMO, VALUES);
        installed(true, false);

        ConnectionSnapshot checked = service.check(user, DEMO);

        verify(connector, times(1)).putConnector(anyString(), anyString(), anyBoolean(), anyString());
        assertThat(checked.status()).isEqualTo(ConnectionStatus.PENDING);
        assertThat(checked.restartRequired()).isTrue();
    }

    @Test
    @DisplayName("다시 보낸 뒤에도 설치가 configured가 아니면 확인은 목록을 쓰지 않고 PENDING으로 둔다")
    void checkStaysPendingWithoutWritingToolsetWhenStillNotConfigured() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        service.register(user, DEMO, VALUES);
        installed(true, false);
        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(List.of("delegation"));

        ConnectionSnapshot checked = service.check(user, DEMO);

        verify(toolsets, never()).writeApiServer(any(), any(), any());
        verify(connector, never()).probe(anyString(), anyString());
        assertThat(checked.status()).isEqualTo(ConnectionStatus.PENDING);
    }

    @Test
    @DisplayName("확인 중 설치 조회가 실패하면 PENDING 을 남기고 연결 실패로 끝난다")
    void checkFailureLeavesPending() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        service.register(user, DEMO, VALUES);
        doThrow(new IllegalStateException()).when(connector).readConnector(anyString(), anyString());

        assertThatThrownBy(() -> service.check(user, DEMO)).isInstanceOf(ConnectorOperationFailure.class);

        assertThat(stored(user).status()).isEqualTo(ConnectionStatus.PENDING);
        assertThat(agentEnabled(user)).isFalse();
    }

    @Test
    @DisplayName("관리자 확인도 다시 보낸 뒤 설치가 configured가 아니면 목록을 쓰지 않고 PENDING으로 둔다")
    void adminConfirmKeepsPendingWithoutToolsetWhenStillNotConfigured() {
        CurrentUser member = user(UserRole.MEMBER, 1L);
        CurrentUser admin = user(UserRole.ADMIN, 1L);
        String profile = profileOf(service.register(member, DEMO, VALUES));
        installed(true, false);
        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(List.of("delegation"));

        assertThatThrownBy(() -> service.confirmApplied(admin, DEMO, member.id()))
                .isInstanceOf(ConnectorOperationFailure.class);

        // 등록의 설치와 다시 설치다.
        verify(connector, times(2)).putConnector(eq(profile), eq(DEMO), eq(true), anyString());
        verify(toolsets, never()).writeApiServer(any(), any(), any());
        assertThat(stored(member).status()).isEqualTo(ConnectionStatus.PENDING);
    }

    @Test
    @DisplayName("호출 제한을 넘은 선택지 조회는 도구를 부르지 않고 거절되며 해제는 제한과 관계없이 된다")
    void rejectsOptionsOverLimitWithoutCallingToolAndStillDisconnects() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        service.register(user, DEMO, VALUES);
        ConnectorConnectionService limited = serviceLimitedTo(1, 1);

        assertThat(limited.options(user, DEMO, "scope", VALUES)).hasSize(2);
        assertCode(() -> limited.options(user, DEMO, "scope", VALUES), ErrorCode.CONNECTOR_RATE_LIMITED);
        assertCode(() -> limited.register(user, DEMO, VALUES), ErrorCode.CONNECTOR_RATE_LIMITED);
        assertCode(() -> limited.check(user, DEMO), ErrorCode.CONNECTOR_RATE_LIMITED);

        // 등록의 확인 한 번과 받아들인 선택지 조회 한 번이다.
        verify(connector, times(2)).call(DEMO, "list_scopes", VALUES);
        verify(connector, never()).readConnector(anyString(), anyString());
        // 직접 만든 서비스는 프록시를 거치지 않아 해제의 트랜잭션을 여기서 연다.
        ConnectionSnapshot disconnected =
                new TransactionTemplate(transactionManager).execute(status -> limited.disconnect(user, DEMO));
        assertThat(disconnected.status()).isEqualTo(ConnectionStatus.DISCONNECTED);
    }

    @Test
    @DisplayName("한 사용자가 호출 제한에 걸려도 다른 사용자의 선택지 조회는 된다")
    void limitsEachUserSeparately() {
        CurrentUser first = user(UserRole.MEMBER, 1L);
        CurrentUser second = user(UserRole.MEMBER, 1L);
        ConnectorConnectionService limited = serviceLimitedTo(1, 1);
        limited.options(first, DEMO, "scope", VALUES);

        assertCode(() -> limited.options(first, DEMO, "scope", VALUES), ErrorCode.CONNECTOR_RATE_LIMITED);
        assertThat(limited.options(second, DEMO, "scope", VALUES)).hasSize(2);
    }

    @Test
    @DisplayName("설치를 다시 보낸 뒤 읽은 상태의 정책 hook 이 꺼져 있으면 PENDING 이고 probe 를 부르지 않는다")
    void staysPendingWithoutProbeWhenPolicyHookIsOffAfterReinstall() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        String profile = profileOf(service.register(user, DEMO, VALUES));
        when(connector.readConnector(anyString(), eq(DEMO)))
                .thenReturn(new ConnectorState("p", true, true, false, false));

        ConnectionSnapshot checked = service.check(user, DEMO);

        assertThat(checked.status()).isEqualTo(ConnectionStatus.PENDING);
        assertThat(agentEnabled(user)).isFalse();
        // hook 을 판정하기 전에 설치를 다시 보냈다. 등록의 설치와 다시 보낸 설치다.
        verify(connector, times(2)).putConnector(eq(profile), eq(DEMO), eq(true), anyString());
        verify(connector, never()).probe(anyString(), anyString());
    }

    @Test
    @DisplayName("첫 조회에서 정책 hook 이 꺼져 있어도 설치를 다시 보낸 뒤 켜졌으면 READY다")
    void becomesReadyWhenReinstallTurnsPolicyHookOn() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        String profile = profileOf(service.register(user, DEMO, VALUES));
        when(connector.readConnector(anyString(), eq(DEMO)))
                .thenReturn(
                        new ConnectorState("p", true, true, false, false),
                        new ConnectorState("p", true, true, false, true));
        when(connector.probe(profile, "demo")).thenReturn(new ProbeResult(true, List.of("list_scopes")));

        assertThat(service.check(user, DEMO).status()).isEqualTo(ConnectionStatus.READY);
    }

    @Test
    @DisplayName("도구를 선언한 커넥터는 probe 가 낸 선언 밖 도구를 세고 그 도구가 있어도 READY다")
    void countsUndeclaredToolsAndStillBecomesReady() {
        when(connector.readCatalog()).thenReturn(List.of(POLICY_MANIFEST));
        CurrentUser user = user(UserRole.MEMBER, 1L);
        String profile = profileOf(service.register(user, DEMO, VALUES));
        installed(true, true);
        when(connector.probe(profile, "demo"))
                .thenReturn(new ProbeResult(true, List.of("list_scopes", "write_note", "purge_notes")));

        ConnectionSnapshot declaredOnly = service.check(user, DEMO);

        assertThat(declaredOnly.status()).isEqualTo(ConnectionStatus.READY);
        assertThat(declaredOnly.undeclaredTools()).isZero();

        when(connector.probe(profile, "demo")).thenReturn(new ProbeResult(true, List.of("list_scopes", "hidden_tool")));

        ConnectionSnapshot withHidden = service.check(user, DEMO);

        assertThat(withHidden.status()).isEqualTo(ConnectionStatus.READY);
        assertThat(withHidden.undeclaredTools()).isEqualTo(1);
        assertThat(stored(user).undeclaredTools()).isEqualTo(1);
        assertThat(agentEnabled(user)).isTrue();
    }

    @Test
    @DisplayName("도구를 선언하지 않는 판의 커넥터는 선언 밖 도구를 세지 않는다")
    void doesNotCountUndeclaredToolsForManifestWithoutToolDeclarations() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        String profile = profileOf(service.register(user, DEMO, VALUES));
        installed(true, true);
        when(connector.probe(profile, "demo")).thenReturn(new ProbeResult(true, List.of("list_scopes", "hidden_tool")));

        ConnectionSnapshot checked = service.check(user, DEMO);

        assertThat(checked.status()).isEqualTo(ConnectionStatus.READY);
        assertThat(checked.undeclaredTools()).isZero();
    }

    @Test
    @DisplayName("해제와 다시 등록은 세어 둔 선언 밖 도구 수를 0 으로 되돌린다")
    void disconnectAndReregisterResetUndeclaredTools() {
        when(connector.readCatalog()).thenReturn(List.of(POLICY_MANIFEST));
        CurrentUser user = user(UserRole.MEMBER, 1L);
        String profile = profileOf(service.register(user, DEMO, VALUES));
        installed(true, true);
        when(connector.probe(profile, "demo")).thenReturn(new ProbeResult(true, List.of("list_scopes", "hidden_tool")));
        assertThat(service.check(user, DEMO).undeclaredTools()).isEqualTo(1);

        assertThat(service.register(user, DEMO, VALUES).undeclaredTools()).isZero();

        assertThat(service.check(user, DEMO).undeclaredTools()).isEqualTo(1);
        assertThat(service.disconnect(user, DEMO).undeclaredTools()).isZero();
    }

    @Test
    @DisplayName("위험도의 하한보다 느슨하게 선언한 커넥터는 카탈로그에 나오지 않는 없는 커넥터다")
    void manifestDeclaringApprovalBelowFloorIsLeftOut() {
        when(connector.readCatalog())
                .thenReturn(List.of(
                        policyManifest(List.of(
                                new ConnectorTool("list_scopes", "READ", "none", null, null),
                                new ConnectorTool("write_note", "WRITE", "none", null, null))),
                        PIN_MANIFEST));
        CurrentUser user = user(UserRole.MEMBER, 1L);

        assertThat(service.catalog(user)).extracting(ConnectorSummary::id).containsExactly(PIN);
        assertCode(() -> service.register(user, DEMO, VALUES), ErrorCode.CONNECTOR_NOT_FOUND);
    }

    @Test
    @DisplayName("판이나 도구 선언을 읽을 수 없던 커넥터만 카탈로그에서 빠지고 다른 커넥터는 남는다")
    void manifestWithUnreadableSchemaOrToolsIsLeftOutAlone() {
        // 클라이언트는 판이 정수가 아니거나 도구 선언이 객체가 아닌 커넥터를 판 0 과 빈 도구로 읽는다.
        ConnectorManifest unreadable = new ConnectorManifest(
                DEMO,
                DEMO_MANIFEST.title(),
                DEMO_MANIFEST.description(),
                DEMO_MANIFEST.fields(),
                "list_scopes",
                "demo",
                List.of(),
                false,
                0,
                List.of());
        when(connector.readCatalog()).thenReturn(List.of(unreadable, PIN_MANIFEST));
        CurrentUser user = user(UserRole.MEMBER, 1L);

        assertThat(service.catalog(user)).extracting(ConnectorSummary::id).containsExactly(PIN);
        assertCode(() -> service.register(user, DEMO, VALUES), ErrorCode.CONNECTOR_NOT_FOUND);
    }

    @Test
    @DisplayName("카탈로그는 선언한 도구의 이름과 제목과 위험도와 승인 방식을 선언한 순서로 준다")
    void catalogListsDeclaredTools() {
        when(connector.readCatalog()).thenReturn(List.of(POLICY_MANIFEST, PIN_MANIFEST));
        CurrentUser user = user(UserRole.MEMBER, 1L);

        List<ConnectorSummary> listed = service.catalog(user);

        assertThat(listed.get(0).tools())
                .containsExactly(
                        new ConnectorToolSummary("list_scopes", null, ToolRisk.READ, ToolApproval.NONE, false),
                        new ConnectorToolSummary("write_note", "메모 쓰기", ToolRisk.WRITE, ToolApproval.REQUIRED, true),
                        new ConnectorToolSummary(
                                "purge_notes", null, ToolRisk.DESTRUCTIVE, ToolApproval.ALWAYS, false));
        // 도구를 선언하지 않는 판의 커넥터는 빈 목록이다.
        assertThat(listed.get(1).tools()).isEmpty();
    }

    @Test
    @DisplayName("카탈로그의 도구는 상시 허락을 줄 수 있는지를 함께 주고 required 도구만 참이 될 수 있다")
    void catalogListsWhetherEachToolCanBeGranted() {
        when(connector.readCatalog())
                .thenReturn(List.of(policyManifest(List.of(
                        new ConnectorTool("list_scopes", "READ", "none", null, Boolean.TRUE),
                        new ConnectorTool("write_note", "WRITE", "required", null, null),
                        new ConnectorTool("share_note", "WRITE", "required", null, Boolean.TRUE),
                        new ConnectorTool("mail_note", "WRITE", "required", null, Boolean.FALSE)))));

        List<ConnectorSummary> listed = service.catalog(user(UserRole.MEMBER, 1L));

        assertThat(listed.get(0).tools())
                .extracting(ConnectorToolSummary::name, ConnectorToolSummary::grant)
                .containsExactly(
                        tuple("list_scopes", false),
                        tuple("write_note", true),
                        tuple("share_note", true),
                        tuple("mail_note", false));
    }

    @Test
    @DisplayName("연결 확인의 설치가 hook plugin 을 바꿨으면 PENDING 과 재시작 대기이고 관리자 반영 완료 뒤 READY다")
    void pluginUpdateLeavesRestartPendingUntilAdminConfirms() {
        CurrentUser member = user(UserRole.MEMBER, 1L);
        CurrentUser admin = user(UserRole.ADMIN, 1L);
        String profile = profileOf(service.register(member, DEMO, VALUES));
        installed(true, true);
        when(connector.probe(profile, "demo")).thenReturn(new ProbeResult(true, List.of("list_scopes")));
        when(connector.putConnector(eq(profile), eq(DEMO), eq(true), anyString())).thenReturn(new InstallResult(true, true));

        ConnectionSnapshot checked = service.check(member, DEMO);

        assertThat(checked.status()).isEqualTo(ConnectionStatus.PENDING);
        assertThat(checked.restartRequired()).isTrue();
        assertThat(agentEnabled(member)).isFalse();
        verify(connector, never()).probe(anyString(), anyString());

        // 재시작한 뒤에는 파일이 이미 새 판이라 설치가 바꾼 것이 없다.
        when(connector.putConnector(eq(profile), eq(DEMO), eq(true), anyString())).thenReturn(new InstallResult(true, false));

        ConnectionSnapshot confirmed = service.confirmApplied(admin, DEMO, member.id());

        assertThat(confirmed.status()).isEqualTo(ConnectionStatus.READY);
        assertThat(confirmed.restartRequired()).isFalse();
        assertThat(agentEnabled(member)).isTrue();
    }

    @Test
    @DisplayName("관리자 반영 완료의 설치가 hook plugin 을 바꿨으면 READY 가 되지 않고 재시작 대기로 남는다")
    void adminConfirmStaysRestartPendingWhenItsInstallUpdatesPlugin() {
        CurrentUser member = user(UserRole.MEMBER, 1L);
        CurrentUser admin = user(UserRole.ADMIN, 1L);
        String profile = profileOf(service.register(member, DEMO, VALUES));
        installed(true, true);
        when(connector.putConnector(eq(profile), eq(DEMO), eq(true), anyString())).thenReturn(new InstallResult(true, true));

        assertThatThrownBy(() -> service.confirmApplied(admin, DEMO, member.id()))
                .isInstanceOf(ConnectorOperationFailure.class);

        assertThat(stored(member).status()).isEqualTo(ConnectionStatus.PENDING);
        assertThat(stored(member).restartRequired()).isTrue();
    }

    @Test
    @DisplayName("등록의 설치가 hook plugin 을 바꿨으면 재시작 대기로 등록된다")
    void registrationMarksRestartWhenInstallUpdatesPlugin() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        when(connector.putConnector(anyString(), eq(DEMO), eq(true), anyString())).thenReturn(new InstallResult(false, true));

        assertThat(service.register(user, DEMO, VALUES).restartRequired()).isTrue();
    }

    @Test
    @DisplayName("READY 에서 PENDING 으로 내려진 연결은 연결 확인 뒤 재시작 대기가 되고 관리자 반영 완료 뒤 READY 와 켜진 에이전트로 돌아온다")
    void connectionLoweredFromReadyReturnsThroughCheckAndAdminConfirm() {
        CurrentUser member = user(UserRole.MEMBER, 1L);
        CurrentUser admin = user(UserRole.ADMIN, 1L);
        String profile = profileOf(service.register(member, DEMO, VALUES));
        installed(true, true);
        when(connector.probe(profile, "demo")).thenReturn(new ProbeResult(true, List.of("list_scopes")));
        assertThat(service.check(member, DEMO).status()).isEqualTo(ConnectionStatus.READY);
        // 정책 판정을 들이는 마이그레이션이 READY 이던 연결에 하는 일과 같다. 재시작 대기는 건드리지 않는다.
        jdbc.update("UPDATE agent SET enabled = FALSE, connector_attachments = FALSE WHERE id IN"
                + " (SELECT agent_id FROM connector_connection WHERE status = 'READY')");
        jdbc.update("UPDATE connector_connection SET status = 'PENDING' WHERE status = 'READY'");
        ConnectorConnection lowered = stored(member);
        assertThat(lowered.status()).isEqualTo(ConnectionStatus.PENDING);
        assertThat(lowered.desiredEnabled()).isTrue();
        assertThat(lowered.restartRequired()).isFalse();
        assertThat(agentEnabled(member)).isFalse();
        // 옛 판의 hook 을 가진 profile 이라 다시 보낸 설치가 파일을 바꾼다.
        when(connector.putConnector(eq(profile), eq(DEMO), eq(true), anyString())).thenReturn(new InstallResult(true, true));

        ConnectionSnapshot checked = service.check(member, DEMO);

        assertThat(checked.status()).isEqualTo(ConnectionStatus.PENDING);
        assertThat(checked.restartRequired()).isTrue();
        assertThat(agentEnabled(member)).isFalse();

        when(connector.putConnector(eq(profile), eq(DEMO), eq(true), anyString())).thenReturn(new InstallResult(true, false));

        ConnectionSnapshot confirmed = service.confirmApplied(admin, DEMO, member.id());

        assertThat(confirmed.status()).isEqualTo(ConnectionStatus.READY);
        assertThat(confirmed.restartRequired()).isFalse();
        assertThat(agentEnabled(member)).isTrue();
    }

    private static ConnectorManifest policyManifest(List<ConnectorTool> tools) {
        return new ConnectorManifest(
                DEMO,
                DEMO_MANIFEST.title(),
                DEMO_MANIFEST.description(),
                DEMO_MANIFEST.fields(),
                "list_scopes",
                "demo",
                List.of(),
                false,
                2,
                tools);
    }

    /** 주어진 한도의 limiter 를 쓰는 서비스를 직접 만든다. 주입된 서비스는 검사 설정의 넉넉한 한도를 쓴다. */
    private ConnectorConnectionService serviceLimitedTo(int maxConcurrentCalls, int callsPerMinute) {
        return new ConnectorConnectionService(
                connections,
                users,
                lifecycle,
                connector,
                toolsets,
                transactionManager,
                new ConnectorCallLimiter(
                        new ConnectorProperties(maxConcurrentCalls, callsPerMinute),
                        Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)),
                approvals,
                Clock.systemUTC());
    }

    private void assertVerifyFailure(
            CurrentUser user, Map<String, String> values, ConnectorCallError error, ErrorCode expected) {
        when(connector.call(anyString(), anyString(), anyMap())).thenReturn(CallResult.failure(error));
        assertCode(() -> service.register(user, DEMO, values), expected);
    }

    private static void assertCode(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(expected));
    }

    private void installed(boolean enabled, boolean configured) {
        when(connector.readConnector(anyString(), eq(DEMO)))
                .thenReturn(new ConnectorState("p", enabled, configured, false, true));
    }

    private ConnectorConnection stored(CurrentUser user) {
        return connections.findByUserIdAndConnectorId(user.id(), DEMO).orElseThrow();
    }

    private String fieldsColumn(CurrentUser user, String connectorId) {
        return jdbc.queryForObject(
                "SELECT fields FROM connector_connection WHERE user_id = ? AND connector_id = ?",
                String.class,
                user.id(),
                connectorId);
    }

    private Boolean agentEnabled(CurrentUser user) {
        return jdbc.queryForObject(
                "SELECT enabled FROM agent WHERE id ="
                        + " (SELECT agent_id FROM connector_connection WHERE user_id = ? AND connector_id = ?)",
                Boolean.class,
                user.id(),
                DEMO);
    }

    private String profileOf(ConnectionSnapshot snapshot) {
        return agents.findByCode(snapshot.agentCode()).orElseThrow().hermesProfile();
    }

    private CurrentUser user(UserRole role, long groupId) {
        String suffix = UUID.randomUUID().toString();
        AppUser saved =
                users.save(AppUser.of("connector-" + suffix + "@example.com", suffix, groupId, role, Instant.now()));
        return new CurrentUser(saved.id(), saved.email(), saved.displayName(), saved.groupId(), saved.role());
    }
}
