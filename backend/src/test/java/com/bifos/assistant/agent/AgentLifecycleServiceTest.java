package com.bifos.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.application.AgentLifecycleService;
import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.connector.domain.ConnectionFields;
import com.bifos.assistant.connector.domain.ConnectorBinding;
import com.bifos.assistant.connector.domain.ConnectorConnection;
import com.bifos.assistant.connector.infra.ConnectorBindingRepository;
import com.bifos.assistant.connector.infra.ConnectorConnectionRepository;
import com.bifos.assistant.hermes.HermesConnectorClient;
import com.bifos.assistant.hermes.HermesDashboardClient;
import com.bifos.assistant.hermes.HermesProfileKeyStore;
import com.bifos.assistant.hermes.HermesToolsetClient;
import com.bifos.assistant.mcp.domain.AgentToken;
import com.bifos.assistant.mcp.infra.AgentTokenRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.skill.domain.SkillBundle;
import com.bifos.assistant.skill.infra.SkillStore;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 사용자가 에이전트를 만들고, 공개 범위를 바꾸고, 지우는 순서를 본다.
 *
 * <p>테스트 클래스에 트랜잭션을 두지 않는다. 서비스의 트랜잭션과 주인 행 잠금이 실제로 돌아야 동시 요청이
 * 차례로 세어지는지 볼 수 있다. Hermes 대시보드와 도구 목록만 대역이고, profile 을 만드는 순서와 토큰
 * 발급, key 파일은 실제 bean 이다.
 */
@SpringBootTest
@ActiveProfiles("test")
class AgentLifecycleServiceTest {

    /** plugin 틀이 붙이는 안전한 기본 도구다. 셸과 파일 등급이 없다. */
    private static final List<String> SAFE_TOOLSETS = List.of("web", "skills", "todo", "vision");

    private static final List<String> WITH_TERMINAL = List.of("web", "terminal");

    private static final String MCP_TOKEN_ENV = "MCP_FOS_ASSISTANT_API_KEY";

    /** {@code application-test.yml} 의 공유 listener 주소다. */
    private static final String LISTENER = "https://hermes-listener.example.com";

    private static final String DEMO = "demo-notes";

    @Autowired
    AgentLifecycleService lifecycle;

    @Autowired
    AgentService agentService;

    @Autowired
    AgentRepository agents;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentTokenRepository tokens;

    @Autowired
    HermesProfileKeyStore keyStore;

    @Autowired
    ConnectorBindingRepository bindingRows;

    @Autowired
    ConnectorConnectionRepository connections;

    @MockitoBean
    HermesConnectorClient connector;

    @MockitoBean
    HermesDashboardClient dashboard;

    @MockitoBean
    HermesToolsetClient toolsets;

    /** 실제 디렉터리에 쓰되, 지우기가 실패하는 경우만 흉내 낼 수 있게 감싼다. */
    @MockitoSpyBean
    SkillStore skillStore;

    /** 이 테스트가 실제 key 디렉터리에 남긴 파일을 지우려고 적어 둔다. */
    private final List<String> createdProfiles = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(SAFE_TOOLSETS);
    }

    @AfterEach
    void tearDown() {
        createdProfiles.forEach(keyStore::delete);
        // 바인딩 줄이 에이전트를 가리켜 남으면 같은 컨텍스트의 다른 검사가 에이전트를 지우지 못한다.
        bindingRows.deleteAll();
        connections.deleteAll();
    }

    @Test
    @DisplayName("만들면 profile 과 토큰이 생기고 요청자가 주인인 관리 대상 행이 남는다")
    void createsProfileAndTokenAndManagedRowOwnedByRequester() {
        CurrentUser kid = member();

        Agent created = create(kid, "  숙제 도우미  ", null);

        String profile = created.hermesProfile();
        assertThat(created.code()).matches("a[a-z0-9]{10}");
        assertThat(profile).matches("ua-[a-z0-9]{10}");
        verify(dashboard).createProfile(profile);
        verify(dashboard).putEnv(eq(profile), eq(MCP_TOKEN_ENV), anyString());
        verify(toolsets).readEnabled(LISTENER + "/p/" + profile, profile);
        assertThat(tokens.findByProfileNameAndRevokedAtIsNull(profile))
                .as("profile %s 에 묶인 폐기 안 된 토큰", profile)
                .hasSize(1);

        Agent stored = agents.findByCode(created.code()).orElseThrow();
        assertThat(stored.profileManaged()).isTrue();
        assertThat(stored.ownerUserId()).isEqualTo(kid.id());
        assertThat(stored.name()).isEqualTo("숙제 도우미");
        assertThat(stored.visibility()).isEqualTo(AgentVisibility.PRIVATE);
        assertThat(stored.credentialScope()).isEqualTo(CredentialScope.SHARED_HOUSEHOLD);
        assertThat(stored.apiBaseUrl()).isEqualTo(LISTENER + "/p/" + profile);
        assertThat(stored.isDeleted()).isFalse();
    }

    @Test
    @DisplayName("그룹 공개로 만들면 그룹에 보이고 주인은 요청자다")
    void groupVisibleCreateShowsInGroupWithRequesterAsOwner() {
        CurrentUser kid = member();
        CurrentUser other = member();

        Agent created = create(kid, "가족 요리사", AgentVisibility.GROUP);

        assertThat(created.visibility()).isEqualTo(AgentVisibility.GROUP);
        assertThat(created.ownerUserId()).isEqualTo(kid.id());
        assertThat(agentService.readableBy(other)).extracting(Agent::code).contains(created.code());
    }

    @Test
    @DisplayName("이미 다섯인 MEMBER 는 만들지 못하고 Hermes 를 부르지 않는다")
    void memberAtFiveCannotCreateAndSkipsHermes() {
        CurrentUser kid = member();
        seedAgents(kid, 5);

        assertCode(() -> lifecycle.create(kid, "여섯째", null), ErrorCode.AGENT_LIMIT_REACHED);

        verifyNoInteractions(dashboard);
        assertThat(agents.countByOwnerUserIdAndDeletedAtIsNullAndConnectorManagedFalse(kid.id()))
                .isEqualTo(5);
    }

    @Test
    @DisplayName("ADMIN 은 다섯이어도 만든다")
    void adminCanCreateEvenAtFive() {
        CurrentUser admin = admin();
        seedAgents(admin, 5);

        Agent created = create(admin, "여섯째", null);

        assertThat(created.ownerUserId()).isEqualTo(admin.id());
        assertThat(agents.countByOwnerUserIdAndDeletedAtIsNullAndConnectorManagedFalse(admin.id()))
                .isEqualTo(6);
    }

    @Test
    @DisplayName("지운 에이전트는 상한에 세지 않는다")
    void deletedAgentDoesNotCountTowardLimit() {
        CurrentUser kid = member();
        List<Agent> seeded = seedAgents(kid, 5);
        Agent removed = seeded.get(0);
        removed.markDeleted(Instant.parse("2026-09-01T00:00:00Z"));
        agents.save(removed);

        Agent created = create(kid, "다시 다섯째", null);

        assertThat(created.ownerUserId()).isEqualTo(kid.id());
        assertThat(agents.countByOwnerUserIdAndDeletedAtIsNullAndConnectorManagedFalse(kid.id()))
                .isEqualTo(5);
    }

    @Test
    @DisplayName("예전 방식의 연결 에이전트는 상한에 세지 않아 넷과 그것을 가진 사용자가 다섯째를 만든다")
    void legacyConnectorAgentsDoNotCountTowardLimit() {
        CurrentUser kid = member();
        seedAgents(kid, 4);
        Agent legacy = seedAgents(kid, 1).get(0);
        legacy.markConnectorManaged();
        agents.save(legacy);

        Agent created = create(kid, "다섯째", null);

        assertThat(created.connectorManaged()).isFalse();
        assertThat(agents.countByOwnerUserIdAndDeletedAtIsNullAndConnectorManagedFalse(kid.id()))
                .isEqualTo(5);
        assertCode(() -> lifecycle.create(kid, "여섯째", null), ErrorCode.AGENT_LIMIT_REACHED);
    }

    /**
     * 같은 사람이 두 번 눌러도 상한을 넘지 않는다.
     *
     * <p>넷을 가진 사용자로 두 요청을 한꺼번에 보낸다. 주인 행 잠금이 없으면 둘 다 넷을 세고 여섯이 된다.
     * 스레드가 엇갈리는 차례는 매번 달라서 회차마다 새 사용자로 여러 번 돌린다.
     */
    @Test
    @DisplayName("넷인 사용자가 동시에 두 번 만들면 하나만 성공하고 하나는 상한이다")
    void concurrentCreatesAtFourSucceedOnceAndHitLimitOnce() throws Exception {
        for (int round = 0; round < 20; round++) {
            CurrentUser kid = member();
            seedAgents(kid, 4);
            CyclicBarrier start = new CyclicBarrier(2);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<Object> outcomes = new ArrayList<>();
            try {
                List<Future<Agent>> results = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    results.add(pool.submit(() -> {
                        start.await(5, TimeUnit.SECONDS);
                        return lifecycle.create(kid, "동시에", null);
                    }));
                }
                for (Future<Agent> result : results) {
                    outcomes.add(outcomeOf(result));
                }
            } finally {
                pool.shutdownNow();
            }

            assertThat(outcomes)
                    .as("%d번째 동시 만들기의 결과", round)
                    .filteredOn(Agent.class::isInstance)
                    .hasSize(1);
            assertThat(outcomes)
                    .as("%d번째 동시 만들기의 결과", round)
                    .filteredOn(outcome -> outcome == ErrorCode.AGENT_LIMIT_REACHED)
                    .hasSize(1);
            assertThat(agents.countByOwnerUserIdAndDeletedAtIsNullAndConnectorManagedFalse(kid.id()))
                    .as("%d번째 동시 만들기 뒤의 에이전트 수", round)
                    .isEqualTo(5);
        }
    }

    @Test
    @DisplayName("만든 profile 에 셸 도구가 켜져 있으면 거두고 행을 남기지 않는다")
    void revokesAndLeavesNoRowWhenCreatedProfileHasShellTool() {
        CurrentUser kid = member();
        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(WITH_TERMINAL);
        List<String> made = new ArrayList<>();
        doAnswer(call -> {
                    made.add(call.getArgument(0));
                    return null;
                })
                .when(dashboard)
                .createProfile(anyString());

        assertCode(() -> lifecycle.create(kid, "셸이 켜진 틀", null), ErrorCode.HERMES_PROVISION_FAILED);

        assertThat(made).hasSize(1);
        String profile = made.get(0);
        createdProfiles.add(profile);
        verify(dashboard).deleteProfile(profile);
        assertThat(tokens.findByProfileNameAndRevokedAtIsNull(profile)).isEmpty();
        assertThat(tokensOf(profile))
                .as("발급했다가 폐기한 토큰")
                .isNotEmpty()
                .allSatisfy(token -> assertThat(token.revokedAt()).isNotNull());
        assertThat(agents.existsByHermesProfile(profile)).isFalse();
        assertThat(agents.countByOwnerUserIdAndDeletedAtIsNullAndConnectorManagedFalse(kid.id()))
                .isZero();
    }

    @Test
    @DisplayName("그룹으로 바꿔도 주인이 남는다")
    void keepsOwnerWhenChangedToGroup() {
        CurrentUser kid = member();
        Agent created = create(kid, "숙제 도우미", null);

        Agent changed = lifecycle.changeVisibility(kid, created.code(), AgentVisibility.GROUP);

        assertThat(changed.visibility()).isEqualTo(AgentVisibility.GROUP);
        Agent stored = agents.findByCode(created.code()).orElseThrow();
        assertThat(stored.visibility()).isEqualTo(AgentVisibility.GROUP);
        assertThat(stored.ownerUserId()).isEqualTo(kid.id());
    }

    @Test
    @DisplayName("셸 도구가 켜진 에이전트는 그룹으로 바꾸지 못한다")
    void cannotChangeToGroupWhenShellToolEnabled() {
        CurrentUser kid = member();
        Agent created = create(kid, "숙제 도우미", null);
        when(toolsets.readEnabled(created.apiBaseUrl(), created.hermesProfile()))
                .thenReturn(WITH_TERMINAL);

        assertCode(
                () -> lifecycle.changeVisibility(kid, created.code(), AgentVisibility.GROUP),
                ErrorCode.AGENT_TOOLS_REQUIRE_PRIVATE);

        assertThat(agents.findByCode(created.code()).orElseThrow().visibility()).isEqualTo(AgentVisibility.PRIVATE);
    }

    @Test
    @DisplayName("꺼진 에이전트는 Hermes 를 부르지 않고 그룹으로 바꾼다")
    void changesDisabledAgentToGroupWithoutCallingHermes() {
        CurrentUser kid = member();
        Agent created = create(kid, "숙제 도우미", null);
        Agent stored = agents.findByCode(created.code()).orElseThrow();
        stored.changeAccess(false, AgentVisibility.PRIVATE, kid.id());
        agents.save(stored);
        clearInvocations(toolsets);

        Agent changed = lifecycle.changeVisibility(kid, created.code(), AgentVisibility.GROUP);

        assertThat(changed.visibility()).isEqualTo(AgentVisibility.GROUP);
        assertThat(changed.enabled()).as("공개 범위만 바꾸고 켜지 않는다").isFalse();
        verify(toolsets, never()).readEnabled(anyString(), anyString());
    }

    @Test
    @DisplayName("주인 없는 그룹 에이전트를 ADMIN 이 자기만 보게 바꾸면 그 ADMIN 이 주인이다")
    void adminSwitchingOwnerlessGroupAgentToPrivateBecomesOwner() {
        CurrentUser administrator = admin();
        String code = "ownerless-" + UUID.randomUUID().toString().substring(0, 13);
        agents.save(Agent.of(
                code,
                code,
                code,
                "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.GROUP,
                null,
                Instant.now()));

        Agent changed = lifecycle.changeVisibility(administrator, code, AgentVisibility.PRIVATE);

        assertThat(changed.visibility()).isEqualTo(AgentVisibility.PRIVATE);
        Agent stored = agents.findByCode(code).orElseThrow();
        assertThat(stored.visibility()).isEqualTo(AgentVisibility.PRIVATE);
        assertThat(stored.ownerUserId()).as("주인이 비어 있던 에이전트의 새 주인").isEqualTo(administrator.id());
        assertThat(stored.isReadableBy(administrator.id())).isTrue();
    }

    @Test
    @DisplayName("연결이 붙은 에이전트는 그룹으로 바꾸지 못하고 비공개로 남는다")
    void cannotChangeAgentWithConnectionsToGroup() {
        CurrentUser kid = member();
        Agent created = create(kid, "숙제 도우미", null);
        bind(created, readyConnection(kid));

        assertCode(
                () -> lifecycle.changeVisibility(kid, created.code(), AgentVisibility.GROUP),
                ErrorCode.AGENT_CONNECTIONS_REQUIRE_PRIVATE);

        assertThat(agents.findByCode(created.code()).orElseThrow().visibility()).isEqualTo(AgentVisibility.PRIVATE);
    }

    @Test
    @DisplayName("공개 범위가 비면 거절한다")
    void rejectsBlankVisibility() {
        CurrentUser kid = member();
        Agent created = create(kid, "숙제 도우미", null);

        assertCode(() -> lifecycle.changeVisibility(kid, created.code(), null), ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("다른 MEMBER 는 그룹 에이전트를 바꾸거나 지우지 못하고 비공개는 없는 것과 같다")
    void otherMemberCannotEditOrDeleteGroupAgentAndPrivateLooksMissing() {
        CurrentUser kid = member();
        CurrentUser other = member();
        Agent shared = create(kid, "가족 요리사", AgentVisibility.GROUP);
        Agent secret = create(kid, "비밀 일기", null);

        assertCode(
                () -> lifecycle.changeVisibility(other, shared.code(), AgentVisibility.PRIVATE), ErrorCode.FORBIDDEN);
        assertCode(() -> lifecycle.delete(other, shared.code()), ErrorCode.FORBIDDEN);
        assertCode(
                () -> lifecycle.changeVisibility(other, secret.code(), AgentVisibility.GROUP),
                ErrorCode.AGENT_NOT_FOUND);
        assertCode(() -> lifecycle.delete(other, secret.code()), ErrorCode.AGENT_NOT_FOUND);

        assertThat(agents.findByCode(shared.code()).orElseThrow().visibility()).isEqualTo(AgentVisibility.GROUP);
        assertThat(agents.findByCode(shared.code()).orElseThrow().isDeleted()).isFalse();
        assertThat(agents.findByCode(secret.code()).orElseThrow().isDeleted()).isFalse();
        verify(dashboard, never()).deleteProfile(anyString());
    }

    @Test
    @DisplayName("ADMIN 은 다른 사람의 비공개 에이전트를 지운다")
    void adminDeletesOthersPrivateAgent() {
        CurrentUser kid = member();
        Agent secret = create(kid, "비밀 일기", null);

        lifecycle.delete(admin(), secret.code());

        assertThat(agents.findByCode(secret.code()).orElseThrow().isDeleted()).isTrue();
        verify(dashboard).deleteProfile(secret.hermesProfile());
    }

    @Test
    @DisplayName("만든 profile 의 에이전트를 지우면 profile 을 거두고 토큰을 폐기한다")
    void deleteRemovesCreatedProfileAndRevokesToken() {
        CurrentUser kid = member();
        Agent created = create(kid, "숙제 도우미", null);

        lifecycle.delete(kid, created.code());

        verify(dashboard).deleteProfile(created.hermesProfile());
        assertThat(tokens.findByProfileNameAndRevokedAtIsNull(created.hermesProfile()))
                .isEmpty();
        Agent stored = agents.findByCode(created.code()).orElseThrow();
        assertThat(stored.isDeleted()).isTrue();
        assertThat(stored.enabled()).isFalse();
        assertCode(() -> agentService.requireStartable(kid, created.code()), ErrorCode.AGENT_NOT_FOUND);
    }

    @Test
    @DisplayName("만든 profile 의 에이전트를 지우면 deprovision 뒤에 스킬 디렉터리가 없다")
    void deleteLeavesNoSkillDirectoryAfterDeprovision() {
        CurrentUser kid = member();
        Agent created = create(kid, "숙제 도우미", null);
        String profile = created.hermesProfile();
        skillStore.markPublished(
                profile,
                skillStore.writeVersion(
                        profile,
                        Map.of(
                                "weekly-plan",
                                new SkillBundle(
                                        "weekly-plan", "---\nname: weekly-plan\ndescription: 계획\n---\n", List.of()))));
        assertThat(skillStore.currentVersion(profile)).isPresent();

        lifecycle.delete(kid, created.code());

        verify(dashboard).deleteProfile(profile);
        verify(skillStore).deleteAll(profile);
        assertThat(skillStore.currentVersion(profile)).isEmpty();
        assertThat(Path.of("build/test-skills", profile)).doesNotExist();
        assertThat(agents.findByCode(created.code()).orElseThrow().isDeleted()).isTrue();
    }

    @Test
    @DisplayName("스킬 디렉터리를 지우지 못해도 에이전트 삭제는 성공한다")
    void deleteSucceedsEvenIfSkillDirectoryCannotBeRemoved() {
        CurrentUser kid = member();
        Agent created = create(kid, "숙제 도우미", null);
        doThrow(new ApiException(ErrorCode.INTERNAL_ERROR, "could not access the skill store"))
                .when(skillStore)
                .deleteAll(created.hermesProfile());

        lifecycle.delete(kid, created.code());

        verify(dashboard).deleteProfile(created.hermesProfile());
        assertThat(agents.findByCode(created.code()).orElseThrow().isDeleted()).isTrue();
    }

    @Test
    @DisplayName("운영에서 만든 profile 의 에이전트를 지우면 profile 을 남긴다")
    void deleteKeepsProfileCreatedInProduction() {
        CurrentUser kid = member();
        Agent seeded = seedAgents(kid, 1).get(0);

        lifecycle.delete(kid, seeded.code());

        verify(dashboard, never()).deleteProfile(anyString());
        assertThat(agents.findByCode(seeded.code()).orElseThrow().isDeleted()).isTrue();
        assertCode(() -> agentService.requireStartable(kid, seeded.code()), ErrorCode.AGENT_NOT_FOUND);
    }

    @Test
    @DisplayName("profile 을 지우지 못하면 에이전트를 지우지 않는다")
    void keepsAgentWhenProfileCannotBeRemoved() {
        CurrentUser kid = member();
        Agent created = create(kid, "숙제 도우미", null);
        doThrow(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "dashboard is down"))
                .when(dashboard)
                .deleteProfile(created.hermesProfile());

        assertCode(() -> lifecycle.delete(kid, created.code()), ErrorCode.HERMES_UNAVAILABLE);

        Agent stored = agents.findByCode(created.code()).orElseThrow();
        assertThat(stored.isDeleted()).isFalse();
        assertThat(stored.enabled()).isTrue();
    }

    @Test
    @DisplayName("연결이 붙은 에이전트를 지우면 profile 을 거두기 전에 붙은 연결을 떼고 바인딩 줄을 지운다")
    void deleteDetachesConnectionsBeforeRemovingProfile() {
        CurrentUser kid = member();
        Agent created = create(kid, "숙제 도우미", null);
        bind(created, readyConnection(kid));

        lifecycle.delete(kid, created.code());

        InOrder order = inOrder(connector, dashboard);
        order.verify(connector).unbindConnector(created.hermesProfile(), DEMO);
        order.verify(dashboard).deleteProfile(created.hermesProfile());
        assertThat(bindingRows.existsByAgentId(created.id())).isFalse();
        assertThat(agents.findByCode(created.code()).orElseThrow().isDeleted()).isTrue();
    }

    @Test
    @DisplayName("운영에서 만든 profile 의 에이전트도 지우면 붙은 연결을 떼고 profile 은 남긴다")
    void deleteDetachesConnectionsOfProfileCreatedInProduction() {
        CurrentUser kid = member();
        Agent seeded = seedAgents(kid, 1).get(0);
        bind(seeded, readyConnection(kid));

        lifecycle.delete(kid, seeded.code());

        verify(connector).unbindConnector(seeded.hermesProfile(), DEMO);
        verify(dashboard, never()).deleteProfile(anyString());
        assertThat(bindingRows.existsByAgentId(seeded.id())).isFalse();
        assertThat(agents.findByCode(seeded.code()).orElseThrow().isDeleted()).isTrue();
    }

    @Test
    @DisplayName("붙은 연결을 떼지 못하면 profile 을 거두지 않고 에이전트와 바인딩을 남긴다")
    void keepsAgentWhenConnectionCannotBeDetached() {
        CurrentUser kid = member();
        Agent created = create(kid, "숙제 도우미", null);
        bind(created, readyConnection(kid));
        doThrow(new IllegalStateException()).when(connector).unbindConnector(anyString(), anyString());

        assertCode(() -> lifecycle.delete(kid, created.code()), ErrorCode.CONNECTOR_OPERATION_FAILED);

        verify(dashboard, never()).deleteProfile(anyString());
        assertThat(bindingRows.existsByAgentId(created.id())).isTrue();
        assertThat(agents.findByCode(created.code()).orElseThrow().isDeleted()).isFalse();
    }

    @Test
    @DisplayName("옛 커넥터 에이전트는 고치지 못해도 주인이 지울 수 있고 지우면 그 바인딩을 뗀다")
    void ownerDeletesLegacyConnectorAgent() {
        CurrentUser kid = member();
        Agent legacy = seedAgents(kid, 1).get(0);
        legacy.markConnectorManaged();
        legacy = agents.save(legacy);
        bind(legacy, readyConnection(kid));
        String code = legacy.code();

        assertCode(() -> lifecycle.changeVisibility(kid, code, AgentVisibility.GROUP), ErrorCode.FORBIDDEN);
        lifecycle.delete(kid, code);

        verify(connector).putConnector(legacy.hermesProfile(), DEMO, false);
        assertThat(bindingRows.existsByAgentId(legacy.id())).isFalse();
        assertThat(agents.findByCode(code).orElseThrow().isDeleted()).isTrue();
    }

    @Test
    @DisplayName("이름이 공백뿐이거나 101자면 거절하고 100자는 된다")
    void rejectsBlankOr101CharNameAndAccepts100() {
        CurrentUser kid = member();

        assertCode(() -> lifecycle.create(kid, "   ", null), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> lifecycle.create(kid, "가".repeat(101), null), ErrorCode.VALIDATION_FAILED);
        verifyNoInteractions(dashboard);

        Agent created = create(kid, "가".repeat(100), null);

        assertThat(agents.findByCode(created.code()).orElseThrow().name()).isEqualTo("가".repeat(100));
    }

    private Agent create(CurrentUser user, String name, AgentVisibility visibility) {
        Agent created = lifecycle.create(user, name, visibility);
        createdProfiles.add(created.hermesProfile());
        return created;
    }

    /** 운영에서 만든 profile 을 가리키는 에이전트처럼 행만 넣는다. 관리 대상 표시가 없다. */
    private List<Agent> seedAgents(CurrentUser owner, int count) {
        List<Agent> seeded = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String code = "seed-" + UUID.randomUUID().toString().substring(0, 13);
            seeded.add(agents.save(Agent.of(
                    code,
                    code,
                    code,
                    "http://agent-runtime.test/p/" + code,
                    CostMode.SUBSCRIPTION,
                    CredentialScope.SHARED_HOUSEHOLD,
                    AgentVisibility.PRIVATE,
                    owner.id(),
                    Instant.now())));
        }
        return seeded;
    }

    /** 값을 보관 파일에 둔 그 사용자의 READY 연결이다. */
    private ConnectorConnection readyConnection(CurrentUser owner) {
        ConnectorConnection connection = ConnectorConnection.pending(owner.id(), DEMO, Instant.now());
        connection.connected(ConnectionFields.empty(), Instant.now());
        return connections.save(connection);
    }

    private void bind(Agent agent, ConnectorConnection connection) {
        ConnectorBinding binding = ConnectorBinding.pending(agent, connection, "demo", Instant.now());
        binding.installed(false, Instant.now());
        bindingRows.save(binding);
    }

    private CurrentUser member() {
        return user(UserRole.MEMBER);
    }

    private CurrentUser admin() {
        return user(UserRole.ADMIN);
    }

    private CurrentUser user(UserRole role) {
        String email = "lifecycle-" + UUID.randomUUID() + "@example.com";
        AppUser saved = users.save(AppUser.of(email, email, 1L, role, Instant.now()));
        return new CurrentUser(saved.id(), email, email, 1L, role);
    }

    private List<AgentToken> tokensOf(String profile) {
        return tokens.findAll().stream()
                .filter(token -> profile.equals(token.profileName()))
                .toList();
    }

    /** 성공이면 만든 에이전트, 거절이면 그 오류 코드다. 그 밖의 실패는 그대로 드러낸다. */
    private Object outcomeOf(Future<Agent> result) throws Exception {
        try {
            Agent created = result.get(10, TimeUnit.SECONDS);
            createdProfiles.add(created.hermesProfile());
            return created;
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof ApiException api) {
                return api.code();
            }
            throw failure;
        }
    }

    private static void assertCode(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(expected);
    }
}
