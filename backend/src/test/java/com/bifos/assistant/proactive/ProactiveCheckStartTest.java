package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.TurnCancellation;
import com.bifos.assistant.chat.application.TurnHandle;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.hermes.HermesRunEventStream;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.HermesSkillClient;
import com.bifos.assistant.hermes.HermesSkillClient.HermesSkill;
import com.bifos.assistant.hermes.HermesToolsetClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.proactive.application.ProactiveCheckService;
import com.bifos.assistant.proactive.domain.CheckReport;
import com.bifos.assistant.proactive.domain.ProactiveCheck;
import com.bifos.assistant.proactive.domain.type.CheckOutcome;
import com.bifos.assistant.proactive.domain.type.CheckSkippedReason;
import com.bifos.assistant.proactive.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.infra.ProactiveCheckFindingRepository;
import com.bifos.assistant.proactive.infra.ProactiveCheckRepository;
import com.bifos.assistant.proactive.presentation.ProactiveCheckController;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.GlobalExceptionHandler;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 살펴보기 시작 경로 {@code POST /api/v1/agents/{code}/proactive-check/runs} 의 응답과 거절을 본다.
 *
 * <p>사용자 실행 한도를 2 로 둔다. 다른 두 대화의 turn 을 붙잡으면 사용자 자리가 없다. Hermes 의 실행, 스트림, toolset, 스킬 목록은
 * 대역이고 모든 데이터는 합성이다.
 */
@BackendIntegrationTest
@TestPropertySource(
        properties = {
            "hermes.run-timeout=30s",
            "assistant.proactive-check.max-duration=20s",
            "assistant.user-execution.max-running=2"
        })
class ProactiveCheckStartTest {

    private static final Duration WAIT_LIMIT = Duration.ofSeconds(10);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    ProactiveCheckService service;

    @Autowired
    TurnCancellation turns;

    @Autowired
    HermesRunsClient hermes;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ChatMessageRepository messages;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    ExecutionEventRepository executionEvents;

    @Autowired
    ProactiveCheckRepository checks;

    @Autowired
    ProactiveCheckFindingRepository findings;

    @Autowired
    TransactionTemplate transactions;

    /** 실제 Hermes 를 부르지 않도록 켜진 toolset 을 대역으로 둔다. */
    @Autowired
    HermesToolsetClient toolsets;

    /** 켜진 스킬 목록을 대역으로 둔다. */
    @Autowired
    HermesSkillClient skillClient;

    /** 실제 스트림 주소로 연결하지 않게 대역으로 둔다. */
    @Autowired
    HermesRunEventStream eventStream;

    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final List<TurnHandle> held = new ArrayList<>();
    private final List<Long> createdUsers = new ArrayList<>();
    private final List<Long> createdAgents = new ArrayList<>();
    private MockMvc mvc;
    private CurrentUser owner;
    private Agent agent;

    private StubHermesRunsClient stub() {
        return (StubHermesRunsClient) hermes;
    }

    @BeforeEach
    void setUp() {
        stub().reset();
        stub().willReturn(HermesRunResult.of(
                "run-start",
                null,
                "completed",
                "<fos-check-result>{\"version\":1,\"outcome\":\"NOTHING_NEW\"}</fos-check-result>",
                "model",
                "provider",
                TokenUsage.empty()));
        mvc = MockMvcBuilders.standaloneSetup(new ProactiveCheckController(service, currentUser))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(List.of("web", "skills", "fos-assistant"));
        when(skillClient.list(anyString())).thenReturn(List.of(new HermesSkill("proactive-check", "살펴보기", true)));
        owner = user("owner");
        agent = agent(owner, AgentVisibility.PRIVATE);
        when(currentUser.require()).thenReturn(owner);
    }

    @AfterEach
    void tearDown() {
        held.forEach(turns::close);
        List<Conversation> owned = conversations.findAll().stream()
                .filter(conversation -> createdUsers.contains(conversation.userId()))
                .toList();
        owned.forEach(conversation -> awaitIdle(conversation.id()));
        List<Long> conversationIds = owned.stream().map(Conversation::id).toList();
        findings.deleteAll(findings.findAll().stream()
                .filter(finding -> conversationIds.contains(finding.conversationId()))
                .toList());
        checks.deleteAll(checks.findAll().stream()
                .filter(check -> createdUsers.contains(check.userId()))
                .toList());
        List<AgentExecution> ownExecutions = executions.findAll().stream()
                .filter(execution -> createdUsers.contains(execution.userId()))
                .toList();
        executionEvents.deleteAll(executionEvents.findAll().stream()
                .filter(event -> ownExecutions.stream()
                        .anyMatch(execution -> execution.id().equals(event.executionId())))
                .toList());
        executions.deleteAll(ownExecutions);
        transactions.executeWithoutResult(status -> {
            conversationIds.forEach(id -> messages.deleteAll(messages.findByConversationIdOrderByIdAsc(id)));
            conversations.deleteAllById(conversationIds);
        });
        agents.deleteAllById(createdAgents);
        users.deleteAllById(createdUsers);
    }

    @Test
    @DisplayName("시작하면 202 와 점검 대화 식별자를 주고 두 번째 시작은 같은 점검 대화를 쓴다")
    void startsWith202AndReusesCheckConversation() throws Exception {
        UUID first = started();
        awaitIdle(checkConversation().id());

        UUID second = started();
        awaitIdle(checkConversation().id());

        assertThat(second).isEqualTo(first);
        Conversation conversation = checkConversation();
        assertThat(conversation.publicId()).isEqualTo(first);
        assertThat(checks.findAll().stream()
                        .filter(check -> check.conversationId().equals(conversation.id()))
                        .map(ProactiveCheck::status)
                        .toList())
                .as("두 살펴보기 줄")
                .hasSize(2);
    }

    @Test
    @DisplayName("막는 까닭이 있으면 409 PROACTIVE_CHECK_UNAVAILABLE 이고 점검 대화를 만들지 않는다")
    void rejectsBlockedAgentWithoutCreatingConversation() throws Exception {
        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(List.of("web", "terminal"));

        mvc.perform(post("/api/v1/agents/{code}/proactive-check/runs", agent.code()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROACTIVE_CHECK_UNAVAILABLE"));

        assertThat(ownersConversations()).as("만든 대화").isEmpty();
        assertThat(stub().received()).isEmpty();
    }

    @Test
    @DisplayName("쓰기 허용을 켠 에이전트는 terminal 이 켜져 있어도 202 로 시작하고 살펴보기 줄에 그 값을 옮겨 적는다")
    void startsWithTerminalWhenWritesAllowed() throws Exception {
        agent.changeProactiveCheckWritesAllowed(true);
        agent = agents.save(agent);
        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(List.of("web", "skills", "terminal", "file"));

        started();
        awaitIdle(checkConversation().id());

        Conversation conversation = checkConversation();
        assertThat(checks.findAll().stream()
                        .filter(check -> check.conversationId().equals(conversation.id()))
                        .map(ProactiveCheck::writesAllowed)
                        .toList())
                .as("살펴보기 줄의 쓰기 허용 값")
                .containsExactly(true);
    }

    @Test
    @DisplayName("사용자 자리가 없으면 409 USER_BUSY 이고 새 점검 대화가 남지 않는다")
    void rejectsUserBusyWithoutLeavingConversation() throws Exception {
        Conversation first = conversations.save(Conversation.startedBy(owner.id(), "첫 대화", agent.id(), Instant.now()));
        Conversation second =
                conversations.save(Conversation.startedBy(owner.id(), "둘째 대화", agent.id(), Instant.now()));
        held.add(turns.open(owner.id(), first.id()));
        held.add(turns.open(owner.id(), second.id()));

        mvc.perform(post("/api/v1/agents/{code}/proactive-check/runs", agent.code()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_BUSY"));

        assertThat(ownersConversations())
                .extracting(Conversation::id)
                .containsExactlyInAnyOrder(first.id(), second.id());
        assertThat(stub().received()).isEmpty();
    }

    @Test
    @DisplayName("점검 대화에 도는 turn 이 있으면 409 CONVERSATION_BUSY 이고 살펴보기 줄을 남기지 않는다")
    void rejectsConversationBusy() throws Exception {
        Conversation existing = conversations.save(
                Conversation.startedForCheck(owner.id(), "먼저 살펴보기 · 커리어", agent.id(), Instant.now()));
        held.add(turns.open(owner.id(), existing.id()));

        mvc.perform(post("/api/v1/agents/{code}/proactive-check/runs", agent.code()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONVERSATION_BUSY"));

        assertThat(checks.findAll())
                .filteredOn(check -> check.conversationId().equals(existing.id()))
                .isEmpty();
    }

    @Test
    @DisplayName("삭제된 점검 대화의 미열람 보고는 새 점검 대화의 예약 실행을 막지 않는다")
    void deletedConversationReportDoesNotBlockNextScheduledCheck() {
        Conversation deleted =
                conversations.save(Conversation.startedForCheck(owner.id(), "삭제할 점검", agent.id(), Instant.now()));
        ProactiveCheck old =
                ProactiveCheck.started(owner.id(), agent.id(), deleted.id(), CheckTrigger.MANUAL, false, Instant.now());
        old.succeed(
                CheckOutcome.FINDINGS,
                0,
                0,
                new CheckReport(List.of("지난 보고"), List.of(), List.of(), List.of(), List.of()),
                0,
                0,
                0,
                0,
                0,
                Instant.now());
        checks.save(old);
        transactions.executeWithoutResult(
                status -> conversations.deleteIfActive(deleted.id(), owner.id(), Instant.now()));

        UUID returned = service.start(owner, agent.code(), CheckTrigger.SCHEDULED);
        Conversation created = ownersConversations().stream()
                .filter(conversation -> conversation.publicId().equals(returned))
                .findFirst()
                .orElseThrow();
        awaitIdle(created.id());

        ProactiveCheck current = checks.findFirstByUserIdAndAgentIdOrderByIdDesc(owner.id(), agent.id())
                .orElseThrow();
        assertThat(returned).isEqualTo(created.publicId()).isNotEqualTo(deleted.publicId());
        assertThat(current.skippedReason()).isNull();
        assertThat(current.rootExecutionId()).isNotNull();
        assertThat(stub().received()).hasSize(1);
    }

    @Test
    @DisplayName("열지 않은 보고가 있으면 예약 살펴보기는 모델을 부르지 않고 UNREAD_REPORT로 끝난다")
    void skipsScheduledCheckWhenReportIsUnread() {
        Conversation conversation = conversations.save(
                Conversation.startedForCheck(owner.id(), "먼저 살펴보기 · 커리어", agent.id(), Instant.now()));
        ProactiveCheck report = checks.save(ProactiveCheck.started(
                owner.id(), agent.id(), conversation.id(), CheckTrigger.MANUAL, false, Instant.now()));
        report.succeed(
                CheckOutcome.FINDINGS,
                0,
                0,
                new CheckReport(List.of("바뀜"), List.of(), List.of(), List.of(), List.of()),
                0,
                0,
                0,
                0,
                0,
                Instant.now());
        checks.save(report);
        int receivedBefore = stub().received().size();

        UUID returned = service.start(owner, agent.code(), CheckTrigger.SCHEDULED);

        ProactiveCheck skipped = checks.findFirstByUserIdAndAgentIdOrderByIdDesc(owner.id(), agent.id())
                .orElseThrow();
        assertThat(returned).isEqualTo(conversation.publicId());
        assertThat(skipped.skippedReason()).isEqualTo(CheckSkippedReason.UNREAD_REPORT);
        assertThat(skipped.rootExecutionId()).isNull();
        assertThat(stub().received()).hasSize(receivedBefore);
    }

    @Test
    @DisplayName("다른 사용자의 비공개 에이전트는 404 AGENT_NOT_FOUND 이고 Hermes 를 부르지 않는다")
    void othersPrivateAgentIsNotFound() throws Exception {
        CurrentUser other = user("other");
        when(currentUser.require()).thenReturn(other);

        mvc.perform(post("/api/v1/agents/{code}/proactive-check/runs", agent.code()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AGENT_NOT_FOUND"));

        verifyNoInteractions(toolsets, skillClient);
        assertThat(stub().received()).isEmpty();
    }

    private UUID started() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/agents/{code}/proactive-check/runs", agent.code()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.conversationId").exists())
                .andReturn();
        return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString())
                .get("conversationId")
                .asString());
    }

    private Conversation checkConversation() {
        List<Conversation> found = ownersConversations();
        assertThat(found).as("주인의 점검 대화").hasSize(1);
        return found.getFirst();
    }

    private List<Conversation> ownersConversations() {
        return conversations.findAll().stream()
                .filter(conversation -> conversation.userId().equals(owner.id()))
                .toList();
    }

    private CurrentUser user(String name) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        AppUser saved =
                users.save(AppUser.of(name + "-" + suffix + "@example.com", name, 1L, UserRole.MEMBER, Instant.now()));
        createdUsers.add(saved.id());
        return new CurrentUser(saved.id(), saved.email(), saved.displayName(), saved.groupId(), saved.role());
    }

    private Agent agent(CurrentUser user, AgentVisibility visibility) {
        String code = "start-" + UUID.randomUUID();
        Agent saved = agents.save(Agent.of(
                code,
                "커리어",
                code,
                "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                visibility,
                user.id(),
                Instant.now()));
        createdAgents.add(saved.id());
        return saved;
    }

    /** 그 대화에 도는 turn 이 없어질 때까지 기다린다. 제한 시간을 넘으면 실패한다. */
    private void awaitIdle(Long conversationId) {
        long deadline = System.nanoTime() + WAIT_LIMIT.toNanos();
        while (turns.markOf(conversationId).running()) {
            if (System.nanoTime() > deadline) {
                fail("대화 %d 의 turn 이 %s 안에 끝나지 않았다", conversationId, WAIT_LIMIT);
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                fail("기다리는 중에 끊겼다");
            }
        }
    }
}
