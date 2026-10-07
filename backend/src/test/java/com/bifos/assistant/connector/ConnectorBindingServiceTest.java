package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.application.AgentLifecycleService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.connector.application.ConnectorBindingService;
import com.bifos.assistant.connector.application.ConnectorConnectionService;
import com.bifos.assistant.connector.application.model.AdminConnectionSnapshot;
import com.bifos.assistant.connector.application.model.AgentConnectionView;
import com.bifos.assistant.connector.application.model.AgentConnectionsView;
import com.bifos.assistant.connector.application.model.ConnectorOperationFailure;
import com.bifos.assistant.connector.domain.ConnectorBinding;
import com.bifos.assistant.connector.domain.ConnectorConnection;
import com.bifos.assistant.connector.domain.type.BindingStatus;
import com.bifos.assistant.connector.domain.type.ConnectionStatus;
import com.bifos.assistant.connector.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.infra.ConnectorBindingRepository;
import com.bifos.assistant.connector.infra.ConnectorConnectionRepository;
import com.bifos.assistant.hermes.ConnectorInstallConflict;
import com.bifos.assistant.hermes.ConnectorProfileRejected;
import com.bifos.assistant.hermes.HermesConnectorClient;
import com.bifos.assistant.hermes.HermesConnectorClient.ConnectorState;
import com.bifos.assistant.hermes.HermesConnectorClient.InstallResult;
import com.bifos.assistant.hermes.HermesConnectorClient.ProbeResult;
import com.bifos.assistant.hermes.HermesSkillClient;
import com.bifos.assistant.hermes.HermesSkillClient.HermesSkill;
import com.bifos.assistant.hermes.HermesToolsetClient;
import com.bifos.assistant.hermes.dto.CallResult;
import com.bifos.assistant.hermes.dto.ConnectorField;
import com.bifos.assistant.hermes.dto.ConnectorManifest;
import com.bifos.assistant.hermes.dto.ConnectorTool;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 연결을 에이전트에 붙이고 떼는 권한과 잠금 순서, 관리자 반영 완료, 바인딩 단위의 해제를 본다(ADR-083).
 *
 * <p>테스트 클래스에 트랜잭션을 두지 않는다. 서비스의 트랜잭션과 사용자 행과 에이전트 행의 잠금이 실제로 돌아야 동시 요청의
 * 차례를 볼 수 있다. 대시보드의 커넥터, 스킬, 도구 목록 경로만 대역이다.
 */
@BackendIntegrationTest
class ConnectorBindingServiceTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String DEMO = "demo-notes";
    private static final String PLAIN = "demo-plain";
    private static final String SKILLED = "demo-skilled";
    private static final String TOKEN = "demo_ok_0123456789";
    private static final Map<String, String> VALUES = Map.of("token", TOKEN);
    private static final ConnectorManifest DEMO_MANIFEST = new ConnectorManifest(
            DEMO,
            "검사용 메모",
            "",
            List.of(new ConnectorField("token", "DEMO_TOKEN", "토큰", "", true, true, null, null)),
            "list_scopes",
            "demo",
            List.of(),
            false,
            2,
            List.of(
                    new ConnectorTool("list_scopes", "READ", "none", null, null),
                    new ConnectorTool("write_note", "WRITE", "required", null, null)),
            List.of());
    /** 입력 칸이 없는 일반 MCP 서버다. */
    private static final ConnectorManifest PLAIN_MANIFEST =
            new ConnectorManifest(PLAIN, "칸 없는 서버", "", List.of(), "ping", "plain", List.of(), false, 1, List.of());
    /** 붙일 때 스킬을 하나 설치하는 커넥터다. */
    private static final ConnectorManifest SKILLED_MANIFEST = new ConnectorManifest(
            SKILLED,
            "지침 있는 서버",
            "",
            List.of(),
            "ping",
            "skilled",
            List.of(),
            false,
            1,
            List.of(),
            List.of("demo-guide"));
    /** 이 시각 뒤의 설치는 모두 관리자가 본 뒤의 설치다. */
    private static final Instant LONG_AGO = Instant.parse("2020-01-01T00:00:00Z");

    @Autowired
    ConnectorBindingService service;

    @Autowired
    ConnectorConnectionService connectionService;

    @Autowired
    AgentLifecycleService lifecycle;

    @Autowired
    ConnectorBindingRepository bindings;

    @Autowired
    ConnectorConnectionRepository connections;

    @Autowired
    ConnectorActionRepository actions;

    @Autowired
    AgentRepository agents;

    @Autowired
    AppUserRepository users;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    HermesConnectorClient connector;

    @Autowired
    HermesSkillClient skills;

    @Autowired
    HermesToolsetClient toolsets;

    @BeforeEach
    void setUp() {
        when(connector.readCatalog()).thenReturn(List.of(DEMO_MANIFEST, PLAIN_MANIFEST, SKILLED_MANIFEST));
        when(connector.call(anyString(), anyString(), anyMap()))
                .thenReturn(CallResult.success(MAPPER.readTree("{\"ok\":true}")));
        when(connector.bindConnector(anyString(), anyString(), anyString())).thenReturn(new InstallResult(true, false));
        when(connector.unbindConnector(anyString(), anyString())).thenReturn(new InstallResult(false, false));
        when(connector.putConnector(anyString(), anyString(), anyBoolean(), anyString()))
                .thenReturn(new InstallResult(false, false));
        when(connector.readConnector(anyString(), anyString()))
                .thenReturn(new ConnectorState("p", true, true, false, true, HermesConnectorClient.MODE_BIND));
        when(connector.probe(anyString(), anyString())).thenReturn(new ProbeResult(true, List.of("list_scopes")));
        when(skills.list(anyString())).thenReturn(List.of(new HermesSkill("hermes-help", "Hermes 기본", true)));
        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(List.of("web"));
    }

    @AfterEach
    void tearDown() {
        actions.deleteAll();
        bindings.deleteAll();
        connections.deleteAll();
        agents.deleteAll();
        users.deleteAll();
    }

    @Test
    @DisplayName("붙이면 바인딩 설치를 한 번 보내고 바인딩은 재시작 대기 PENDING 이며 skills 도구를 켜지 않는다")
    void bindInstallsOnceAndLeavesRestartPending() {
        CurrentUser owner = user(UserRole.MEMBER, 1L);
        ConnectorConnection connection = connect(owner, DEMO, VALUES);
        Agent agent = agent(owner, AgentVisibility.PRIVATE);

        AgentConnectionView bound = service.bind(owner, agent.code(), DEMO);

        verify(connector, times(1)).bindConnector(agent.hermesProfile(), DEMO, connection.vault());
        assertThat(bound.bound()).isTrue();
        assertThat(bound.status()).isEqualTo(BindingStatus.PENDING);
        assertThat(bound.restartRequired()).isTrue();
        assertThat(bound.toolCount()).isEqualTo(2);
        ConnectorBinding stored = onlyBinding();
        assertThat(stored.mcpServer()).isEqualTo("demo");
        assertThat(stored.restartRequiredSince()).isNotNull();
        verify(toolsets, never()).writeApiServer(anyString(), anyList(), anyString());
        verify(skills, never()).publish(anyString(), anyList(), any(), anyString());

        assertThat(service.bind(owner, agent.code(), DEMO).status())
                .as("다시 붙이기")
                .isEqualTo(BindingStatus.PENDING);
        verify(connector, times(1)).bindConnector(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("남의 비공개 에이전트는 AGENT_NOT_FOUND, 읽을 수 있는 남의 에이전트와 관리자는 FORBIDDEN 이다")
    void onlyOwnerManagesConnectionsOfAgent() {
        CurrentUser owner = user(UserRole.MEMBER, 1L);
        CurrentUser other = user(UserRole.MEMBER, 1L);
        CurrentUser admin = user(UserRole.ADMIN, 1L);
        connect(other, DEMO, VALUES);
        connect(admin, DEMO, VALUES);
        Agent privateAgent = agent(owner, AgentVisibility.PRIVATE);
        Agent groupAgent = agent(owner, AgentVisibility.GROUP);

        assertCode(() -> service.bind(other, privateAgent.code(), DEMO), ErrorCode.AGENT_NOT_FOUND);
        assertCode(() -> service.unbind(other, privateAgent.code(), DEMO), ErrorCode.AGENT_NOT_FOUND);
        assertCode(() -> service.listForAgent(other, privateAgent.code()), ErrorCode.AGENT_NOT_FOUND);
        assertCode(() -> service.bind(other, groupAgent.code(), DEMO), ErrorCode.FORBIDDEN);
        assertCode(() -> service.unbind(other, groupAgent.code(), DEMO), ErrorCode.FORBIDDEN);
        assertCode(() -> service.listForAgent(other, groupAgent.code()), ErrorCode.FORBIDDEN);
        assertCode(() -> service.bind(admin, privateAgent.code(), DEMO), ErrorCode.FORBIDDEN);
        assertCode(() -> service.unbind(admin, privateAgent.code(), DEMO), ErrorCode.FORBIDDEN);
        assertCode(() -> service.listForAgent(admin, privateAgent.code()), ErrorCode.FORBIDDEN);
        assertCode(() -> service.bind(owner, "no-such-agent", DEMO), ErrorCode.AGENT_NOT_FOUND);
        assertThat(bindings.count()).isZero();
        verify(connector, never()).bindConnector(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("그룹 공개 에이전트, 옛 커넥터 에이전트, 연결되지 않은 연결은 각자의 오류 코드로 붙지 않는다")
    void refusesGroupAgentLegacyAgentAndUnconnectedConnection() {
        CurrentUser owner = user(UserRole.MEMBER, 1L);
        connect(owner, DEMO, VALUES);
        Agent groupAgent = agent(owner, AgentVisibility.GROUP);
        Agent legacy = agent(owner, AgentVisibility.PRIVATE);
        legacy.markConnectorManaged();
        agents.save(legacy);
        Agent ordinary = agent(owner, AgentVisibility.PRIVATE);

        assertCode(() -> service.bind(owner, groupAgent.code(), DEMO), ErrorCode.AGENT_CONNECTIONS_REQUIRE_PRIVATE);
        assertCode(() -> service.bind(owner, legacy.code(), DEMO), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.bind(owner, ordinary.code(), PLAIN), ErrorCode.CONNECTOR_NOT_CONNECTED);
        connectionService.disconnect(owner, DEMO);
        assertCode(() -> service.bind(owner, ordinary.code(), DEMO), ErrorCode.CONNECTOR_NOT_CONNECTED);
        assertThat(bindings.count()).isZero();
        verify(connector, never()).bindConnector(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("보관 파일이 없는 옛 연결은 READY 여도 다른 에이전트에 붙지 않는다")
    void refusesConnectionWithoutVault() {
        CurrentUser owner = user(UserRole.MEMBER, 1L);
        ConnectorConnection legacyConnection = ConnectorConnection.pending(owner.id(), DEMO, Instant.now());
        legacyConnection.ready(Instant.now());
        connections.save(legacyConnection);
        Agent ordinary = agent(owner, AgentVisibility.PRIVATE);

        assertCode(() -> service.bind(owner, ordinary.code(), DEMO), ErrorCode.CONNECTOR_NOT_CONNECTED);
    }

    /**
     * 공개 범위 변경이 에이전트 행 잠금을 쥔 동안 붙이기는 기다리고, 그 변경이 커밋한 그룹 공개를 보고 거절된다.
     *
     * <p>공개 범위 변경이 그룹에 공개해도 되는지 도구 목록을 읽는 자리에서 멈춘다. 그때 그 변경은 이미 에이전트 행을 잠갔다.
     */
    @Test
    @DisplayName("공개 범위 변경이 에이전트 행을 잠근 동안 붙이기는 기다렸다가 커밋된 그룹 공개를 보고 거절된다")
    void bindWaitsForVisibilityChangeAndSeesCommittedGroup() throws Exception {
        CurrentUser owner = user(UserRole.MEMBER, 1L);
        connect(owner, DEMO, VALUES);
        Agent agent = agent(owner, AgentVisibility.PRIVATE);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(toolsets.readEnabled(anyString(), anyString())).thenAnswer(invocation -> {
            locked.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return List.of("web");
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Agent> changing =
                    pool.submit(() -> lifecycle.changeVisibility(owner, agent.code(), AgentVisibility.GROUP));
            assertThat(locked.await(10, TimeUnit.SECONDS))
                    .as("공개 범위 변경이 잠금을 쥐었다")
                    .isTrue();
            Future<AgentConnectionView> binding = pool.submit(() -> service.bind(owner, agent.code(), DEMO));
            Thread.sleep(300);
            assertThat(binding.isDone()).as("붙이기가 에이전트 행 잠금을 기다린다").isFalse();

            release.countDown();

            assertThat(changing.get(10, TimeUnit.SECONDS).visibility()).isEqualTo(AgentVisibility.GROUP);
            assertThatThrownBy(() -> binding.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOfSatisfying(
                            ApiException.class,
                            ex -> assertThat(ex.code()).isEqualTo(ErrorCode.AGENT_CONNECTIONS_REQUIRE_PRIVATE));
            assertThat(bindings.count()).isZero();
            verify(connector, never()).bindConnector(anyString(), anyString(), anyString());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("대시보드의 409 와 401 은 새 오류 코드이고 바인딩 행이 남지 않는다")
    void dashboardConflictAndProfileRefusalLeaveNoRow() {
        CurrentUser owner = user(UserRole.MEMBER, 1L);
        connect(owner, DEMO, VALUES);
        Agent agent = agent(owner, AgentVisibility.PRIVATE);

        doThrow(new ConnectorInstallConflict()).when(connector).bindConnector(anyString(), anyString(), anyString());
        assertCode(() -> service.bind(owner, agent.code(), DEMO), ErrorCode.CONNECTOR_BIND_CONFLICT);
        assertThat(bindings.count()).isZero();

        doThrow(new ConnectorProfileRejected()).when(connector).bindConnector(anyString(), anyString(), anyString());
        assertCode(() -> service.bind(owner, agent.code(), DEMO), ErrorCode.CONNECTOR_PROFILE_NOT_READY);
        assertThat(bindings.count()).isZero();
    }

    @Test
    @DisplayName("그 밖의 붙이기 실패는 바인딩을 PENDING 으로 남기고 연결 실패로 끝난다")
    void otherBindFailureLeavesPendingRow() {
        CurrentUser owner = user(UserRole.MEMBER, 1L);
        connect(owner, DEMO, VALUES);
        Agent agent = agent(owner, AgentVisibility.PRIVATE);
        doThrow(new IllegalStateException()).when(connector).bindConnector(anyString(), anyString(), anyString());

        assertThatThrownBy(() -> service.bind(owner, agent.code(), DEMO)).isInstanceOf(ConnectorOperationFailure.class);

        ConnectorBinding stored = onlyBinding();
        assertThat(stored.status()).isEqualTo(BindingStatus.PENDING);
        assertThat(stored.desiredEnabled()).isFalse();
    }

    @Test
    @DisplayName("커넥터의 스킬 이름이 그 profile 의 스킬과 겹치면 SKILL_NAME_TAKEN 이고 설치하지 않는다")
    void refusesWhenConnectorSkillNameIsTaken() {
        CurrentUser owner = user(UserRole.MEMBER, 1L);
        connect(owner, SKILLED, Map.of());
        Agent agent = agent(owner, AgentVisibility.PRIVATE);
        when(skills.list(agent.hermesProfile())).thenReturn(List.of(new HermesSkill("demo-guide", "올린 스킬", true)));

        assertCode(() -> service.bind(owner, agent.code(), SKILLED), ErrorCode.SKILL_NAME_TAKEN);

        assertThat(bindings.count()).isZero();
        verify(connector, never()).bindConnector(anyString(), anyString(), anyString());

        when(skills.list(agent.hermesProfile())).thenReturn(List.of());
        assertThat(service.bind(owner, agent.code(), SKILLED).skills()).containsExactly("demo-guide");
    }

    @Test
    @DisplayName("칸이 없는 커넥터는 빈 값으로 연결되고 그 보관 파일로 붙는다")
    void connectorWithoutFieldsConnectsWithEmptyValuesAndBinds() {
        CurrentUser owner = user(UserRole.MEMBER, 1L);
        Agent agent = agent(owner, AgentVisibility.PRIVATE);

        ConnectorConnection connection = connect(owner, PLAIN, Map.of());

        verify(connector).call(PLAIN, "ping", Map.of());
        verify(connector).putVault(connection.vault(), PLAIN, Map.of());
        assertThat(connection.status()).isEqualTo(ConnectionStatus.READY);
        assertThat(service.bind(owner, agent.code(), PLAIN).bound()).isTrue();
        verify(connector).bindConnector(agent.hermesProfile(), PLAIN, connection.vault());
    }

    @Test
    @DisplayName("에이전트의 연결 목록은 해제하지 않은 내 연결마다 붙었는지를 주고 붙일 수 없는 까닭을 함께 준다")
    void listsConnectionsOfAgentWithBlockedReason() {
        CurrentUser owner = user(UserRole.MEMBER, 1L);
        connect(owner, DEMO, VALUES);
        connect(owner, PLAIN, Map.of());
        Agent agent = agent(owner, AgentVisibility.PRIVATE);
        service.bind(owner, agent.code(), PLAIN);
        Agent groupAgent = agent(owner, AgentVisibility.GROUP);

        AgentConnectionsView listed = service.listForAgent(owner, agent.code());

        assertThat(listed.blockedReason()).isNull();
        assertThat(listed.connections())
                .extracting(AgentConnectionView::connectorId, AgentConnectionView::title, AgentConnectionView::bound)
                .containsExactly(tuple(DEMO, "검사용 메모", false), tuple(PLAIN, "칸 없는 서버", true));
        assertThat(service.listForAgent(owner, groupAgent.code()).blockedReason())
                .isEqualTo(AgentConnectionsView.AGENT_NOT_PRIVATE);
    }

    @Test
    @DisplayName("떼면 unbindConnector 를 부르고 행을 지우며 그 에이전트가 판정한 승인 줄만 끝낸다")
    void unbindRemovesRowAndRejectsOnlyThatAgentsPendingActions() {
        CurrentUser owner = user(UserRole.MEMBER, 1L);
        connect(owner, DEMO, VALUES);
        Agent agent = agent(owner, AgentVisibility.PRIVATE);
        Agent other = agent(owner, AgentVisibility.PRIVATE);
        service.bind(owner, agent.code(), DEMO);
        service.bind(owner, other.code(), DEMO);
        UUID mine = action(owner, agent, "PENDING");
        UUID theirs = action(owner, other, "PENDING");

        service.unbind(owner, agent.code(), DEMO);

        verify(connector).unbindConnector(agent.hermesProfile(), DEMO);
        assertThat(jdbc.queryForList("SELECT agent_id FROM agent_connector_binding", Long.class))
                .containsExactly(other.id());
        assertThat(actionStatus(mine)).isEqualTo(tuple("REJECTED", "connection_changed"));
        assertThat(actionStatus(theirs)).isEqualTo(tuple("PENDING", null));

        service.unbind(owner, agent.code(), DEMO);
        verify(connector, times(1)).unbindConnector(anyString(), anyString());
    }

    @Test
    @DisplayName("그 에이전트의 승인 실행이 돌고 있으면 CONNECTOR_ACTION_EXECUTING 이고 떼지 않으며, 옛 에이전트의 바인딩은 떼지 않는다")
    void unbindRefusesWhileExecutingAndForLegacyAgent() {
        CurrentUser owner = user(UserRole.MEMBER, 1L);
        connect(owner, DEMO, VALUES);
        Agent agent = agent(owner, AgentVisibility.PRIVATE);
        service.bind(owner, agent.code(), DEMO);
        action(owner, agent, "EXECUTING");
        Agent legacy = agent(owner, AgentVisibility.PRIVATE);
        legacy.markConnectorManaged();
        agents.save(legacy);

        assertCode(() -> service.unbind(owner, agent.code(), DEMO), ErrorCode.CONNECTOR_ACTION_EXECUTING);
        assertCode(() -> service.unbind(owner, legacy.code(), DEMO), ErrorCode.VALIDATION_FAILED);

        assertThat(bindings.count()).isEqualTo(1);
        verify(connector, never()).unbindConnector(anyString(), anyString());
    }

    @Test
    @DisplayName("관리자 반영 완료는 본 시각이 같으면 설치를 다시 보내 확인하고 READY 로 바꾼다")
    void confirmAppliedMakesBindingReady() {
        CurrentUser owner = user(UserRole.MEMBER, 1L);
        CurrentUser admin = user(UserRole.ADMIN, 1L);
        connect(owner, DEMO, VALUES);
        Agent agent = agent(owner, AgentVisibility.PRIVATE);
        service.bind(owner, agent.code(), DEMO);
        Instant shown = shownTo(admin);
        // 재시작한 뒤에는 설치를 다시 보내도 바뀐 것이 없다.
        when(connector.bindConnector(anyString(), anyString(), anyString()))
                .thenReturn(new InstallResult(false, false));

        AgentConnectionView confirmed = service.confirmApplied(admin, agent.code(), DEMO, shown);

        assertThat(confirmed.status()).isEqualTo(BindingStatus.READY);
        assertThat(confirmed.restartRequired()).isFalse();
        InOrder order = inOrder(connector);
        order.verify(connector).bindConnector(anyString(), eq(DEMO), anyString());
        order.verify(connector).readConnector(agent.hermesProfile(), DEMO);
        order.verify(connector).probe(agent.hermesProfile(), "demo");
        assertThat(onlyBinding().status()).isEqualTo(BindingStatus.READY);
    }

    @Test
    @DisplayName("관리자 목록을 읽은 뒤 다시 설치된 바인딩의 반영 완료는 CONNECTOR_RESTART_AGAIN 이고 대기가 풀리지 않는다")
    void confirmAppliedRefusesBindingReinstalledAfterAdminLooked() {
        CurrentUser owner = user(UserRole.MEMBER, 1L);
        CurrentUser admin = user(UserRole.ADMIN, 1L);
        connect(owner, DEMO, VALUES);
        Agent agent = agent(owner, AgentVisibility.PRIVATE);
        service.bind(owner, agent.code(), DEMO);
        jdbc.update("UPDATE agent_connector_binding SET restart_required_since = ?", Timestamp.from(LONG_AGO));
        Instant shown = shownTo(admin);
        assertThat(shown).isEqualTo(LONG_AGO);
        // 관리자가 재시작하는 사이 사용자가 값을 바꿔 그 profile 에 설치가 다시 들어갔다.
        connectionService.register(owner, DEMO, Map.of("token", "demo_zz_0123456789"));

        assertCode(() -> service.confirmApplied(admin, agent.code(), DEMO, shown), ErrorCode.CONNECTOR_RESTART_AGAIN);

        ConnectorBinding stored = onlyBinding();
        assertThat(stored.status()).isEqualTo(BindingStatus.PENDING);
        assertThat(stored.restartRequired()).isTrue();
        assertThat(stored.restartRequiredSince()).isAfter(LONG_AGO);
        assertCode(() -> service.confirmApplied(admin, agent.code(), DEMO, null), ErrorCode.CONNECTOR_RESTART_AGAIN);
    }

    @Test
    @DisplayName("재시작 대기 시각이 없고 관리자도 보지 못했으면 같은 값으로 보고 반영 완료를 받는다")
    void confirmAppliedTreatsTwoMissingInstantsAsSame() {
        CurrentUser owner = user(UserRole.MEMBER, 1L);
        CurrentUser admin = user(UserRole.ADMIN, 1L);
        connect(owner, DEMO, VALUES);
        Agent agent = agent(owner, AgentVisibility.PRIVATE);
        when(connector.bindConnector(anyString(), anyString(), anyString()))
                .thenReturn(new InstallResult(false, false));
        service.bind(owner, agent.code(), DEMO);
        assertThat(shownTo(admin)).isNull();

        assertThat(service.confirmApplied(admin, agent.code(), DEMO, null).status())
                .isEqualTo(BindingStatus.READY);
    }

    @Test
    @DisplayName("반영 완료에서 다시 보낸 설치가 바뀐 것이 있다고 답하면 READY 가 아니고 연결 실패로 끝난다")
    void confirmAppliedStaysPendingWhenReinstallNeedsRestartAgain() {
        CurrentUser owner = user(UserRole.MEMBER, 1L);
        CurrentUser admin = user(UserRole.ADMIN, 1L);
        connect(owner, DEMO, VALUES);
        Agent agent = agent(owner, AgentVisibility.PRIVATE);
        service.bind(owner, agent.code(), DEMO);
        Instant shown = shownTo(admin);

        assertThatThrownBy(() -> service.confirmApplied(admin, agent.code(), DEMO, shown))
                .isInstanceOf(ConnectorOperationFailure.class);

        ConnectorBinding stored = onlyBinding();
        assertThat(stored.status()).isEqualTo(BindingStatus.PENDING);
        assertThat(stored.restartRequired()).isTrue();
        verify(connector, never()).probe(anyString(), anyString());
    }

    @Test
    @DisplayName("반영 완료에서 다시 보낸 설치가 configured 가 아니거나 probe 가 실패하면 PENDING 과 재시작 대기로 남고 연결 실패다")
    void confirmAppliedKeepsRestartWaitWhenNotConfiguredOrProbeFails() {
        CurrentUser owner = user(UserRole.MEMBER, 1L);
        CurrentUser admin = user(UserRole.ADMIN, 1L);
        connect(owner, DEMO, VALUES);
        Agent agent = agent(owner, AgentVisibility.PRIVATE);
        service.bind(owner, agent.code(), DEMO);
        Instant shown = shownTo(admin);
        when(connector.bindConnector(anyString(), anyString(), anyString()))
                .thenReturn(new InstallResult(false, false));

        when(connector.readConnector(anyString(), anyString()))
                .thenReturn(new ConnectorState("p", true, false, false, true, HermesConnectorClient.MODE_BIND));
        assertThatThrownBy(() -> service.confirmApplied(admin, agent.code(), DEMO, shown))
                .as("configured 가 아니다")
                .isInstanceOf(ConnectorOperationFailure.class);
        assertThat(onlyBinding())
                .extracting(ConnectorBinding::status, ConnectorBinding::restartRequired)
                .containsExactly(BindingStatus.PENDING, true);

        when(connector.readConnector(anyString(), anyString()))
                .thenReturn(new ConnectorState("p", true, true, false, true, HermesConnectorClient.MODE_BIND));
        when(connector.probe(anyString(), anyString())).thenReturn(new ProbeResult(false, List.of()));
        assertThatThrownBy(() -> service.confirmApplied(admin, agent.code(), DEMO, shown))
                .as("probe 가 실패한다")
                .isInstanceOf(ConnectorOperationFailure.class);
        assertThat(onlyBinding())
                .extracting(ConnectorBinding::status, ConnectorBinding::restartRequired)
                .containsExactly(BindingStatus.PENDING, true);
    }

    @Test
    @DisplayName("MEMBER 는 반영 완료를 누를 수 없고, 다른 그룹의 관리자에게는 그 에이전트가 없는 것으로 답한다")
    void forbidsMemberAndHidesAgentFromOtherGroupAdminWhenConfirming() {
        CurrentUser owner = user(UserRole.MEMBER, 1L);
        CurrentUser outsider = user(UserRole.ADMIN, 2L);
        connect(owner, DEMO, VALUES);
        Agent agent = agent(owner, AgentVisibility.PRIVATE);
        service.bind(owner, agent.code(), DEMO);

        assertCode(() -> service.confirmApplied(owner, agent.code(), DEMO, null), ErrorCode.FORBIDDEN);
        assertCode(() -> service.confirmApplied(outsider, agent.code(), DEMO, null), ErrorCode.AGENT_NOT_FOUND);
        assertThat(onlyBinding().status()).isEqualTo(BindingStatus.PENDING);
    }

    @Test
    @DisplayName("주인 없는 에이전트의 반영 완료는 없는 에이전트와 같은 404 다")
    void answersOwnerlessAgentAsNotFoundWhenConfirming() {
        CurrentUser administrator = user(UserRole.ADMIN, 1L);
        String code = "ownerless-" + UUID.randomUUID().toString().substring(0, 13);
        agents.save(Agent.of(
                code,
                code,
                "profile-" + code,
                "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.GROUP,
                null,
                Instant.now()));

        assertCode(() -> service.confirmApplied(administrator, code, DEMO, null), ErrorCode.AGENT_NOT_FOUND);
    }

    @Test
    @DisplayName("해제가 두 번째 바인딩에서 실패하면 첫 바인딩 행만 지워지고 보관 파일이 남으며, 다시 해제하면 이어서 끝난다")
    void disconnectResumesAfterFailingOnSecondBinding() {
        CurrentUser owner = user(UserRole.MEMBER, 1L);
        ConnectorConnection connection = connect(owner, DEMO, VALUES);
        Agent first = agent(owner, AgentVisibility.PRIVATE);
        Agent second = agent(owner, AgentVisibility.PRIVATE);
        service.bind(owner, first.code(), DEMO);
        service.bind(owner, second.code(), DEMO);
        List<String> unbound = new ArrayList<>();
        when(connector.unbindConnector(anyString(), eq(DEMO))).thenAnswer(invocation -> {
            unbound.add(invocation.getArgument(0));
            if (unbound.size() == 2) {
                throw new IllegalStateException();
            }
            return new InstallResult(false, false);
        });

        assertThatThrownBy(() -> connectionService.disconnect(owner, DEMO))
                .isInstanceOf(ConnectorOperationFailure.class);

        assertThat(jdbc.queryForList(
                        "SELECT a.hermes_profile FROM agent_connector_binding b JOIN agent a ON a.id = b.agent_id",
                        String.class))
                .containsExactly(unbound.get(1));
        verify(connector, never()).deleteVault(anyString());
        assertThat(connections.findById(connection.id()).orElseThrow().status()).isEqualTo(ConnectionStatus.PENDING);

        assertThat(connectionService.disconnect(owner, DEMO).status()).isEqualTo(ConnectionStatus.DISCONNECTED);

        assertThat(unbound).hasSize(3);
        assertThat(unbound.get(2)).isEqualTo(unbound.get(1));
        assertThat(bindings.count()).isZero();
        verify(connector, times(1)).deleteVault(connection.vault());
    }

    @Test
    @DisplayName("옛 커넥터 에이전트의 바인딩을 모두 떼면 값을 먼저 보관 파일로 옮기고, 옮기지 못하면 떼지 않는다")
    void detachAllImportsLegacyValuesFirst() {
        CurrentUser owner = user(UserRole.MEMBER, 1L);
        Agent legacy = agent(owner, AgentVisibility.PRIVATE);
        legacy.markConnectorManaged();
        agents.save(legacy);
        ConnectorConnection connection = ConnectorConnection.pending(owner.id(), DEMO, Instant.now());
        connection.ready(Instant.now());
        connection = connections.save(connection);
        ConnectorBinding binding = ConnectorBinding.pending(legacy, connection, null, Instant.now());
        binding.installed(false, Instant.now());
        bindings.save(binding);
        doThrow(new IllegalStateException()).when(connector).importVault(anyString(), anyString(), anyString());

        assertThatThrownBy(() -> service.detachAll(legacy)).isInstanceOf(ConnectorOperationFailure.class);

        assertThat(bindings.count()).isEqualTo(1);
        verify(connector, never()).putConnector(anyString(), anyString(), anyBoolean(), anyString());

        clearInvocations(connector);
        doNothing().when(connector).importVault(anyString(), anyString(), anyString());
        service.detachAll(legacy);

        InOrder order = inOrder(connector);
        order.verify(connector).importVault(connection.vault(), DEMO, legacy.hermesProfile());
        order.verify(connector).deleteEnv(legacy.hermesProfile(), "DEMO_TOKEN");
        order.verify(connector).putConnector(legacy.hermesProfile(), DEMO, false, legacy.sandboxOwner());
        assertThat(bindings.count()).isZero();
        assertThat(connections.findById(connection.id()).orElseThrow().vaultStored())
                .isTrue();
    }

    /** 관리자 목록이 보인 그 바인딩의 재시작 대기 시작 시각이다. */
    private Instant shownTo(CurrentUser admin) {
        List<AdminConnectionSnapshot> listed = connectionService.listForAdmin(admin);
        assertThat(listed).hasSize(1);
        return listed.get(0).restartRequiredSince();
    }

    private ConnectorConnection connect(CurrentUser owner, String connectorId, Map<String, String> values) {
        connectionService.register(owner, connectorId, values);
        return connections.findByUserIdAndConnectorId(owner.id(), connectorId).orElseThrow();
    }

    private ConnectorBinding onlyBinding() {
        List<ConnectorBinding> all = bindings.findAll();
        assertThat(all).hasSize(1);
        return all.get(0);
    }

    /** 그 에이전트의 실행이 판정한 승인 줄 한 줄을 넣는다. 대화 없는 실행의 줄이라 사건이 나가지 않는다. */
    private UUID action(CurrentUser owner, Agent agent, String status) {
        UUID publicId = UUID.randomUUID();
        byte[] bytes = ByteBuffer.allocate(16)
                .putLong(publicId.getMostSignificantBits())
                .putLong(publicId.getLeastSignificantBits())
                .array();
        jdbc.update(
                "INSERT INTO connector_action (public_id, user_id, agent_id, connector_id, hermes_tool, decision,"
                        + " passed, origin_execution_id, dedupe_key, args_sha256, created_at, status)"
                        + " VALUES (?, ?, ?, ?, 'mcp__demo__write_note', 'NEEDS_APPROVAL', FALSE, 1, ?, 'sha', ?, ?)",
                bytes,
                owner.id(),
                agent.id(),
                DEMO,
                publicId.toString().replace("-", ""),
                Timestamp.from(Instant.now()),
                status);
        return publicId;
    }

    private Tuple actionStatus(UUID publicId) {
        return actions.findAll().stream()
                .filter(action -> action.publicId().equals(publicId))
                .map(action -> tuple(action.status().name(), action.errorCode()))
                .findFirst()
                .orElseThrow();
    }

    private Agent agent(CurrentUser owner, AgentVisibility visibility) {
        String code = "bind-" + UUID.randomUUID().toString().substring(0, 13);
        return agents.save(Agent.of(
                code,
                code,
                "profile-" + code,
                "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                visibility,
                owner.id(),
                Instant.now()));
    }

    private CurrentUser user(UserRole role, long groupId) {
        String suffix = UUID.randomUUID().toString();
        AppUser saved =
                users.save(AppUser.of("binding-" + suffix + "@example.com", suffix, groupId, role, Instant.now()));
        return new CurrentUser(saved.id(), saved.email(), saved.displayName(), saved.groupId(), saved.role());
    }

    private static void assertCode(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(expected));
    }
}
