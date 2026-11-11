package com.bifos.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.application.AgentCapabilitiesReader;
import com.bifos.assistant.agent.application.AgentLifecycleService;
import com.bifos.assistant.agent.application.dto.AgentCapabilities;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentIntroduction;
import com.bifos.assistant.agent.domain.type.AgentColor;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.connector.connection.domain.ConnectionFields;
import com.bifos.assistant.connector.connection.domain.ConnectorBinding;
import com.bifos.assistant.connector.connection.domain.ConnectorConnection;
import com.bifos.assistant.connector.connection.infra.ConnectorBindingRepository;
import com.bifos.assistant.connector.connection.infra.ConnectorConnectionRepository;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import com.bifos.assistant.hermes.application.dto.ConnectorTool;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 소개 저장의 권한과 저장 결과, 할 수 있는 일 모으기를 실제 저장소로 본다(docs/features/agent-team/spec.md 의 REQ1).
 *
 * <p>Hermes 대시보드는 대역이다. 카탈로그는 플러그인 이름을 주는 데만 쓴다.
 */
@BackendIntegrationTest
class AgentIntroductionApiTest {

    private static final String SHOP = "shop-intro-test";

    private static final ConnectorManifest SHOP_MANIFEST = new ConnectorManifest(
        SHOP, "검사용 쇼핑", "", List.of(), "list_scopes", "shop", 2,
        List.of(new ConnectorTool("list_scopes", "READ", "none", null, null)));

    @Autowired
    AgentLifecycleService lifecycle;

    @Autowired
    AgentCapabilitiesReader capabilities;

    @Autowired
    AgentRepository agents;

    @Autowired
    AppUserRepository users;

    @Autowired
    ConnectorConnectionRepository connections;

    @Autowired
    ConnectorBindingRepository bindings;

    @Autowired
    HermesConnectorClient connector;


    @AfterEach
    void tearDown() {
        bindings.deleteAll();
        connections.deleteAll();
    }


    @Test
    @DisplayName("주인이 소개, 아이콘, 색을 저장하면 다시 읽어도 같고 공개 범위와 주인은 그대로다")
    void ownerSavesIntroductionWithoutTouchingAccess() {
        CurrentUser owner = user(UserRole.MEMBER);
        Agent agent = agent(owner.id(), AgentVisibility.PRIVATE);

        lifecycle.changeIntroduction(owner, agent.code(), AgentIntroduction.of(" 쇼핑 담당 ", "쇼핑을 맡아요", "🛍️", AgentColor.ROSE));

        Agent saved = agents.findByCode(agent.code()).orElseThrow();
        assertThat(saved.name()).isEqualTo("쇼핑 담당");
        assertThat(saved.description()).isEqualTo("쇼핑을 맡아요");
        assertThat(saved.icon()).isEqualTo("🛍️");
        assertThat(saved.color()).isEqualTo(AgentColor.ROSE);
        assertThat(saved.visibility()).isEqualTo(AgentVisibility.PRIVATE);
        assertThat(saved.ownerUserId()).isEqualTo(owner.id());
        assertThat(saved.enabled()).isTrue();
    }


    @Test
    @DisplayName("빈 칸으로 저장하면 소개, 아이콘, 색이 비워진다")
    void blankFieldsClearIntroduction() {
        CurrentUser owner = user(UserRole.MEMBER);
        Agent agent = agent(owner.id(), AgentVisibility.PRIVATE);
        lifecycle.changeIntroduction(owner, agent.code(), AgentIntroduction.of("쇼핑 담당", "쇼핑을 맡아요", "🛍️", AgentColor.ROSE));

        lifecycle.changeIntroduction(owner, agent.code(), AgentIntroduction.of("쇼핑 담당", "", null, null));

        Agent saved = agents.findByCode(agent.code()).orElseThrow();
        assertThat(saved.description()).isNull();
        assertThat(saved.icon()).isNull();
        assertThat(saved.color()).isNull();
    }


    @Test
    @DisplayName("ADMIN 은 남의 비공개 에이전트도 고친다")
    void adminEditsOthersPrivateAgent() {
        CurrentUser owner = user(UserRole.MEMBER);
        CurrentUser admin = user(UserRole.ADMIN);
        Agent agent = agent(owner.id(), AgentVisibility.PRIVATE);

        lifecycle.changeIntroduction(admin, agent.code(), AgentIntroduction.of("집안일 담당", null, "🏠", null));

        assertThat(agents.findByCode(agent.code()).orElseThrow().name()).isEqualTo("집안일 담당");
    }


    @Test
    @DisplayName("주인이 아닌 사용자는 그룹 공개 에이전트를 고치지 못하고 남의 비공개 에이전트는 찾지 못한다")
    void otherMemberCannotEdit() {
        CurrentUser owner = user(UserRole.MEMBER);
        CurrentUser other = user(UserRole.MEMBER);
        Agent shared = agent(owner.id(), AgentVisibility.GROUP);
        Agent hidden = agent(owner.id(), AgentVisibility.PRIVATE);
        AgentIntroduction introduction = AgentIntroduction.of("바꾼 이름", null, null, null);

        assertThatThrownBy(() -> lifecycle.changeIntroduction(other, shared.code(), introduction))
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> lifecycle.changeIntroduction(other, hidden.code(), introduction))
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.AGENT_NOT_FOUND));
        assertThat(agents.findByCode(shared.code()).orElseThrow().name()).isNotEqualTo("바꾼 이름");
    }


    @Test
    @DisplayName("할 수 있는 일에는 연결과 바인딩이 모두 준비된 플러그인 이름만 든다")
    void capabilitiesListOnlyUsableConnectors() {
        when(connector.readCatalog()).thenReturn(List.of(SHOP_MANIFEST));
        CurrentUser owner = user(UserRole.MEMBER);
        Agent ready = agent(owner.id(), AgentVisibility.PRIVATE);
        Agent pending = agent(owner.id(), AgentVisibility.PRIVATE);
        ConnectorConnection connection = readyConnection(owner);
        ConnectorBinding readyBinding = ConnectorBinding.pending(ready, connection, "shop", Instant.now());
        readyBinding.ready(Instant.now());
        bindings.save(readyBinding);
        bindings.save(ConnectorBinding.pending(pending, connection, "shop", Instant.now()));

        Map<Long, AgentCapabilities> read = capabilities.read(List.of(ready, pending));

        assertThat(read.get(ready.id()).connectors()).containsExactly("검사용 쇼핑");
        assertThat(read.get(pending.id()).connectors()).isEmpty();
    }


    @Test
    @DisplayName("카탈로그를 읽지 못해도 할 수 있는 일은 실패하지 않고 플러그인만 빠진다")
    void capabilitiesSurviveCatalogFailure() {
        when(connector.readCatalog()).thenThrow(new IllegalStateException("dashboard down"));
        CurrentUser owner = user(UserRole.MEMBER);
        Agent ready = agent(owner.id(), AgentVisibility.PRIVATE);
        ConnectorBinding binding = ConnectorBinding.pending(ready, readyConnection(owner), "shop", Instant.now());
        binding.ready(Instant.now());
        bindings.save(binding);

        Map<Long, AgentCapabilities> read = capabilities.read(List.of(ready));

        assertThat(read.get(ready.id()).connectors()).isEmpty();
        assertThat(read.get(ready.id()).skills()).isEmpty();
    }


    private ConnectorConnection readyConnection(CurrentUser owner) {
        ConnectorConnection connection = ConnectorConnection.pending(owner.id(), SHOP, Instant.now());
        connection.connected(ConnectionFields.empty(), Instant.now());
        return connections.save(connection);
    }


    private Agent agent(Long ownerId, AgentVisibility visibility) {
        String code = "intro-" + UUID.randomUUID().toString().substring(0, 8);
        return agents.save(Agent.of(
            code, "검사용 " + code, code, "http://hermes.test/" + code, CostMode.API, CredentialScope.SHARED_HOUSEHOLD, visibility, ownerId));
    }


    private CurrentUser user(UserRole role) {
        String email = "intro-" + UUID.randomUUID() + "@example.com";
        AppUser saved = users.save(AppUser.of(email, email, 1L, role));
        return new CurrentUser(saved.id(), email, email, 1L, role);
    }
}
