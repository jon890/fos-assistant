package com.bifos.assistant.proactive;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.hermes.HermesSkillClient;
import com.bifos.assistant.hermes.HermesSkillClient.HermesSkill;
import com.bifos.assistant.hermes.HermesToolsetClient;
import com.bifos.assistant.proactive.application.ProactiveCheckService;
import com.bifos.assistant.proactive.domain.ProactiveCheck;
import com.bifos.assistant.proactive.domain.type.CheckInvalidReason;
import com.bifos.assistant.proactive.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.infra.ProactiveCheckRepository;
import com.bifos.assistant.proactive.presentation.ProactiveCheckController;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.GlobalExceptionHandler;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 살펴보기 상태 조회 경로 {@code GET /api/v1/agents/{code}/proactive-check} 를 본다.
 *
 * <p>Hermes 의 toolset 과 스킬 목록은 대역으로 둔다. 검사들이 H2 를 함께 쓰므로 사용자 번호는 다른 검사와 겹치지 않는 값을 쓰고, 만든 줄은
 * 끝날 때 지운다.
 */
@BackendIntegrationTest
class ProactiveCheckStatusTest {

    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");

    @Autowired
    ProactiveCheckService service;

    @Autowired
    AgentRepository agents;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ProactiveCheckRepository checks;

    @Autowired
    TransactionTemplate transactions;

    /** 실제 Hermes 를 부르지 않도록 켜진 toolset 을 대역으로 둔다. */
    @Autowired
    HermesToolsetClient toolsets;

    /** 켜진 스킬 목록을 대역으로 둔다. */
    @Autowired
    HermesSkillClient skillClient;

    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final CurrentUser owner = new CurrentUser(932_001L, "owner@example.com", "주인", 1L, UserRole.MEMBER);
    private final CurrentUser other = new CurrentUser(932_002L, "other@example.com", "다른 사람", 1L, UserRole.MEMBER);
    private final List<Long> createdAgents = new ArrayList<>();
    private final List<Long> createdConversations = new ArrayList<>();
    private final List<Long> createdChecks = new ArrayList<>();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ProactiveCheckController(service, currentUser))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(List.of("web", "skills", "fos-assistant"));
        when(skillClient.list(anyString())).thenReturn(List.of(new HermesSkill("proactive-check", "살펴보기", true)));
    }

    @AfterEach
    void tearDown() {
        checks.deleteAllById(createdChecks);
        transactions.executeWithoutResult(status -> conversations.deleteAllById(createdConversations));
        agents.deleteAllById(createdAgents);
    }

    private Agent agent(AgentVisibility visibility) {
        String code = "status-" + UUID.randomUUID();
        Agent saved = agents.save(Agent.of(
                code,
                "커리어",
                code,
                "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                visibility,
                owner.id(),
                NOW));
        createdAgents.add(saved.id());
        return saved;
    }

    private Conversation checkConversationOf(CurrentUser user, Agent agent) {
        Conversation saved = conversations.save(Conversation.startedForCheck(user.id(), "점검", agent.id(), NOW));
        createdConversations.add(saved.id());
        return saved;
    }

    /** 실행 번호와 루트 session 과 오류 코드가 적힌, 실패한 살펴보기를 남긴다. */
    private void failedCheck(CurrentUser user, Agent agent, Conversation conversation) {
        ProactiveCheck check =
                ProactiveCheck.started(user.id(), agent.id(), conversation.id(), CheckTrigger.MANUAL, false, NOW);
        check.attachRoot(987_654L, "root-session-secret");
        check.fail("HERMES_UNAVAILABLE", 3, 1, NOW.plusSeconds(30));
        createdChecks.add(checks.save(check).id());
    }

    @Test
    @DisplayName("결과를 읽지 못한 마지막 살펴보기는 그 까닭을 함께 받는다")
    void ownerSeesInvalidReasonOfLastCheck() throws Exception {
        Agent groupAgent = agent(AgentVisibility.GROUP);
        Conversation ownersCheck = checkConversationOf(owner, groupAgent);
        ProactiveCheck check =
                ProactiveCheck.started(owner.id(), groupAgent.id(), ownersCheck.id(), CheckTrigger.MANUAL, false, NOW);
        check.succeedInvalid(CheckInvalidReason.EMPTY_ANSWER, 0, 0, NOW.plusSeconds(30));
        createdChecks.add(checks.save(check).id());
        when(currentUser.require()).thenReturn(owner);

        mvc.perform(get("/api/v1/agents/{code}/proactive-check", groupAgent.code()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastCheck.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.lastCheck.outcome").value("INVALID_RESULT"))
                .andExpect(jsonPath("$.lastCheck.invalidReason").value("EMPTY_ANSWER"));
    }

    @Test
    @DisplayName("다른 사용자의 비공개 에이전트는 404 AGENT_NOT_FOUND 이고 Hermes 를 부르지 않는다")
    void othersPrivateAgentIsNotFound() throws Exception {
        Agent privateAgent = agent(AgentVisibility.PRIVATE);
        when(currentUser.require()).thenReturn(other);

        mvc.perform(get("/api/v1/agents/{code}/proactive-check", privateAgent.code()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AGENT_NOT_FOUND"));
        verifyNoInteractions(toolsets, skillClient);
    }

    @Test
    @DisplayName("점검 대화와 살펴보기가 없으면 conversationId 와 lastCheck 가 null 이고 시작할 수 있다")
    void noCheckConversationGivesNullIds() throws Exception {
        Agent privateAgent = agent(AgentVisibility.PRIVATE);
        when(currentUser.require()).thenReturn(owner);

        mvc.perform(get("/api/v1/agents/{code}/proactive-check", privateAgent.code()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.blockers.length()").value(0))
                .andExpect(jsonPath("$.conversationId").value(nullValue()))
                .andExpect(jsonPath("$.lastCheck").value(nullValue()));
    }

    @Test
    @DisplayName("막는 까닭이 있으면 까닭 코드와 끌 toolset 이름을 싣는다")
    void blockersCarryCodeAndToolsets() throws Exception {
        Agent privateAgent = agent(AgentVisibility.PRIVATE);
        when(currentUser.require()).thenReturn(owner);
        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(List.of("web", "skills", "terminal"));

        mvc.perform(get("/api/v1/agents/{code}/proactive-check", privateAgent.code()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.blockers.length()").value(1))
                .andExpect(jsonPath("$.blockers[0].code").value("TOOLSETS_NOT_ALLOWED"))
                .andExpect(jsonPath("$.blockers[0].toolsets[0]").value("terminal"));
    }

    @Test
    @DisplayName("같은 그룹 공개 에이전트를 다른 사용자가 조회해도 남의 점검 대화와 살펴보기가 보이지 않는다")
    void otherUserDoesNotSeeOwnersCheckConversation() throws Exception {
        Agent groupAgent = agent(AgentVisibility.GROUP);
        Conversation ownersCheck = checkConversationOf(owner, groupAgent);
        failedCheck(owner, groupAgent, ownersCheck);
        when(currentUser.require()).thenReturn(other);

        mvc.perform(get("/api/v1/agents/{code}/proactive-check", groupAgent.code()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value(nullValue()))
                .andExpect(jsonPath("$.lastCheck").value(nullValue()));
    }

    @Test
    @DisplayName("주인은 자기 점검 대화와 마지막 살펴보기를 받고 실행 번호, session, 오류 코드, profile 은 받지 않는다")
    void ownerSeesOwnCheckWithoutInternalValues() throws Exception {
        Agent groupAgent = agent(AgentVisibility.GROUP);
        Conversation ownersCheck = checkConversationOf(owner, groupAgent);
        checkConversationOf(other, groupAgent);
        failedCheck(owner, groupAgent, ownersCheck);
        when(currentUser.require()).thenReturn(owner);

        mvc.perform(get("/api/v1/agents/{code}/proactive-check", groupAgent.code()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId")
                        .value(ownersCheck.publicId().toString()))
                .andExpect(jsonPath("$.lastCheck.status").value("FAILED"))
                .andExpect(jsonPath("$.lastCheck.outcome").value(nullValue()))
                .andExpect(jsonPath("$.lastCheck.invalidReason").value(nullValue()))
                .andExpect(jsonPath("$.lastCheck.startedAt").exists())
                .andExpect(jsonPath("$.lastCheck.finishedAt").exists())
                .andExpect(content().string(not(containsString("987654"))))
                .andExpect(content().string(not(containsString("root-session-secret"))))
                .andExpect(content().string(not(containsString("HERMES_UNAVAILABLE"))))
                .andExpect(content().string(not(containsString(groupAgent.hermesProfile()))));
    }
}
