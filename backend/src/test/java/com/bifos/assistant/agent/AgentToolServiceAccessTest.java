package com.bifos.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.application.AgentToolService;
import com.bifos.assistant.agent.application.AgentToolService.ToolView;
import com.bifos.assistant.agent.application.AgentToolService.ToolsetsView;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentToolPolicy;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.hermes.HermesToolsetClient;
import com.bifos.assistant.hermes.HermesToolsetClient.ToolsetCatalogEntry;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 에이전트를 코드로 찾아 도구를 읽고 바꾸는 서비스 메서드가 접근 범위를 지키는지와, 잠금 조회에 필요한
 * 트랜잭션을 스스로 여는지를 실제 저장소로 본다.
 *
 * <p>이 클래스의 검사는 트랜잭션 밖에서 돈다. 잠금 조회는 트랜잭션이 없으면 저장소가 거절하므로, 서비스
 * 메서드가 트랜잭션을 열지 않으면 아래 검사는 기대한 오류나 결과 대신 저장소의 예외로 실패한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class AgentToolServiceAccessTest {

    private static final Instant DELETED_AT = Instant.parse("2026-09-29T00:00:00Z");

    @Autowired
    AgentToolService agentTools;

    @Autowired
    AgentRepository agents;

    @Autowired
    AppUserRepository users;

    /** 실제 Hermes 를 부르지 않도록 도구 목록과 설정 저장을 대역으로 둔다. */
    @MockitoBean
    HermesToolsetClient hermesToolsets;

    private final List<Long> createdUserIds = new ArrayList<>();
    private final List<Long> createdAgentIds = new ArrayList<>();

    private CurrentUser owner;
    private CurrentUser otherMember;
    private CurrentUser administrator;

    @BeforeEach
    void setUp() {
        owner = user(UserRole.MEMBER);
        otherMember = user(UserRole.MEMBER);
        administrator = user(UserRole.ADMIN);
    }

    /** 그룹에 사용자가 남으면 다른 검사의 첫 사용자 판정이 달라지므로 이 클래스가 만든 행을 지운다. */
    @AfterEach
    void deleteCreatedRows() {
        agents.deleteAllById(createdAgentIds);
        users.deleteAllById(createdUserIds);
    }

    @Test
    @DisplayName("관리자가 지운 에이전트의 도구를 바꾸면 없는 에이전트이고 Hermes 를 부르지 않는다")
    void writeAsAdminOnDeletedAgentIsNotFound() {
        String code = deletedAgentOf(owner);

        assertNotFound(() -> agentTools.writeAsAdmin(administrator, code, List.of("web")));
        verifyNoInteractions(hermesToolsets);
    }

    @Test
    @DisplayName("관리자가 지운 에이전트의 도구를 읽으면 없는 에이전트이고 Hermes 를 부르지 않는다")
    void readAsAdminOnDeletedAgentIsNotFound() {
        String code = deletedAgentOf(owner);

        assertNotFound(() -> agentTools.readAsAdmin(administrator, code));
        verifyNoInteractions(hermesToolsets);
    }

    @Test
    @DisplayName("다른 사용자의 비공개 에이전트의 도구를 바꾸면 없는 에이전트이고 Hermes 를 부르지 않는다")
    void writeReadableOnOthersPrivateAgentIsNotFound() {
        String code = saved(privateAgentOf(owner)).code();

        assertNotFound(() -> agentTools.writeReadable(otherMember, code, List.of("web")));
        verifyNoInteractions(hermesToolsets);
    }

    @Test
    @DisplayName("없는 코드로 도구를 바꾸면 주인 경로와 관리자 경로가 모두 없는 에이전트를 낸다")
    void writingUnknownCodeIsNotFoundOnBothPaths() {
        String unknown = randomCode("unknown");

        assertNotFound(() -> agentTools.writeReadable(owner, unknown, List.of("web")));
        assertNotFound(() -> agentTools.writeAsAdmin(administrator, unknown, List.of("web")));
    }

    @Test
    @DisplayName("주인이 트랜잭션 밖에서 자기 에이전트의 도구를 바꾸면 Hermes 에 저장하고 켜진 목록을 돌려준다")
    void writeReadableOutsideTransactionAppliesToolsets() {
        Agent agent = saved(privateAgentOf(owner));
        stubHermesApplying(agent, "web");

        ToolsetsView view = agentTools.writeReadable(owner, agent.code(), List.of("web"));

        assertThat(view.toolsets())
                .filteredOn(ToolView::enabled)
                .extracting(ToolView::name)
                .containsExactly("web");
        verify(hermesToolsets).writeApiServer(agent.hermesProfile(), List.of("web", AgentToolPolicy.CONTROL_PLANE_MCP));
    }

    @Test
    @DisplayName("관리자가 트랜잭션 밖에서 다른 사용자의 비공개 에이전트의 도구를 바꾸면 Hermes 에 저장하고 켜진 목록을 돌려준다")
    void writeAsAdminOutsideTransactionAppliesToolsets() {
        Agent agent = saved(privateAgentOf(owner));
        stubHermesApplying(agent, "web");

        ToolsetsView view = agentTools.writeAsAdmin(administrator, agent.code(), List.of("web"));

        assertThat(view.toolsets())
                .filteredOn(ToolView::enabled)
                .extracting(ToolView::name)
                .containsExactly("web");
        verify(hermesToolsets).writeApiServer(agent.hermesProfile(), List.of("web", AgentToolPolicy.CONTROL_PLANE_MCP));
    }

    /** 위 검사들이 트랜잭션을 보는 근거다. 이 검사가 실패하면 위 검사는 트랜잭션이 없어도 통과한다. */
    @Test
    @DisplayName("트랜잭션 밖에서 저장소의 잠금 조회를 직접 부르면 InvalidDataAccessApiUsageException 이 난다")
    void rejectsDirectLockingLookupOutsideTransaction() {
        String code = saved(privateAgentOf(owner)).code();

        assertThatThrownBy(() -> agents.findByCodeForUpdate(code))
                .isInstanceOf(InvalidDataAccessApiUsageException.class);
    }

    /** 저장 전에는 꺼져 있고 저장 뒤에는 요청한 toolset 이 켜진 것으로 읽히는 Hermes 다. */
    private void stubHermesApplying(Agent agent, String toolset) {
        when(hermesToolsets.readCatalog()).thenReturn(List.of(new ToolsetCatalogEntry(toolset, toolset, toolset)));
        when(hermesToolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile()))
                .thenReturn(List.of())
                .thenReturn(List.of(toolset));
    }

    private void assertNotFound(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ApiException.class)
                .extracting(thrown -> ((ApiException) thrown).code())
                .isEqualTo(ErrorCode.AGENT_NOT_FOUND);
    }

    private String deletedAgentOf(CurrentUser agentOwner) {
        Agent agent = saved(privateAgentOf(agentOwner));
        agent.markDeleted(DELETED_AT);
        agents.save(agent);
        return agent.code();
    }

    private Agent saved(Agent agent) {
        Agent saved = agents.save(agent);
        createdAgentIds.add(saved.id());
        return saved;
    }

    private CurrentUser user(UserRole role) {
        String email = "tool-access-" + UUID.randomUUID() + "@example.com";
        AppUser saved = users.save(AppUser.of(email, email, 1L, role, Instant.now()));
        createdUserIds.add(saved.id());
        return new CurrentUser(saved.id(), saved.email(), saved.displayName(), saved.groupId(), saved.role());
    }

    /** 에이전트 번호 규칙에 맞는 소문자와 숫자, {@code -} 로 만든다. */
    private static String randomCode(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 13);
    }

    private static Agent privateAgentOf(CurrentUser agentOwner) {
        String code = randomCode("tools");
        return Agent.of(
                code,
                code,
                code,
                "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                agentOwner.id(),
                Instant.now());
    }
}
