package com.bifos.assistant.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.conversation.domain.ChatMessage;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.domain.type.MessageRole;
import com.bifos.assistant.chat.conversation.infra.ChatMessageRepository;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.chat.turn.application.ChatService;
import com.bifos.assistant.chat.turn.application.TurnCancellation;
import com.bifos.assistant.chat.turn.application.dto.DelegationLine;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.application.dto.HermesRunResult;
import com.bifos.assistant.hermes.application.port.HermesRunsClient;
import com.bifos.assistant.orchestration.application.SkillDelegations;
import com.bifos.assistant.orchestration.application.dto.SkillDelegationStarted;
import com.bifos.assistant.orchestration.application.dto.SkillDelegationTarget;
import com.bifos.assistant.runtime.domain.TokenUsage;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.skill.library.application.SkillService;
import com.bifos.assistant.skill.library.application.dto.SkillList;
import com.bifos.assistant.skill.library.application.dto.SkillListItem;
import com.bifos.assistant.skill.library.application.dto.SkillSource;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.DelegationWakeEnabled;
import com.bifos.assistant.testsupport.TestPrimaryAgents;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.execution.infra.ExecutionEventRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 비서 대화의 {@code /} 목록으로 전문 에이전트의 스킬을 맡긴다(docs/features/assistant-hub/spec.md 의 REQ10, DECISION6, DECISION10,
 * DECISION26).
 *
 * <p>맡긴 실행은 대화 turn 없이 돌고, 끝나면 깨우기가 결과 도착 줄과 비서 답을 연다. 스킬 목록은 테스트가 정한다(Hermes 대시보드를 부르지
 * 않는다). 커리어 실행은 테스트가 풀 때까지 붙잡아 둔다.
 */
@BackendIntegrationTest
@DelegationWakeEnabled
class SkillDelegationTest {

    private static final Duration WAIT_LIMIT = Duration.ofSeconds(10);
    private static final String TEXT = "/커리어 · position-recommender 이번 주 공고";

    @Autowired
    SkillDelegations delegations;

    @Autowired
    ChatService chat;

    @Autowired
    TurnCancellation turns;

    @Autowired
    SkillService skillService;

    @Autowired
    TestPrimaryAgents primaries;

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
    JdbcTemplate jdbc;

    private final CountDownLatch careerRelease = new CountDownLatch(1);
    /** 비서 답을 붙잡을 때만 센다. 기본은 붙잡지 않는다. */
    private volatile CountDownLatch primaryRelease = new CountDownLatch(0);
    private CurrentUser dad;
    private Agent primary;
    private Agent career;
    private Agent quiet;
    private Agent off;


    private StubHermesRunsClient stub() {
        return (StubHermesRunsClient)hermes;
    }


    @BeforeEach
    void setUp() {
        clean();
        stub().reset();
        stub().willAnswer(command -> {
            if (command.profileName().equals("career")) {
                await(careerRelease);
            }
            if (command.profileName().equals("dad")) {
                await(primaryRelease);
            }
            return HermesRunResult.of(
                "run-" + command.profileName(), command.sessionId(), "completed", command.profileName() + " 의 답", "model", "provider",
                TokenUsage.empty());
        });
        AppUser user = users.save(AppUser.of("skill-delegation@example.com", "아빠", 1L, UserRole.MEMBER));
        dad = new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
        primary = primaries.make(dad, agents.save(agent("dad", "비서", user.id())));
        career = agents.save(agent("career", "커리어", user.id()));
        quiet = agents.save(agent("quiet", "스킬 없음", user.id()));
        off = agents.save(agent("off", "꺼진 담당", user.id()));
        jdbc.update("UPDATE agent SET enabled = false WHERE id = ?", off.id());
        skillsOf("dad", "weekly-plan");
        skillsOf("career", "position-recommender", "resume-review:off", "hermes-help:hermes", "note_taking.v2");
        skillsOf("quiet");
        skillsOf("off", "position-recommender");
    }


    @AfterEach
    void tearDown() {
        careerRelease.countDown();
        primaryRelease.countDown();
        awaitTrue(() -> executions.findAll().stream().noneMatch(row -> row.status() == ExecutionStatus.RUNNING), "도는 실행");
        clean();
    }


    @Test
    @DisplayName("목록은 쓸 수 있고 켜진 전문 에이전트의 켜진 스킬만 주고 기본 비서, 꺼진 에이전트, 스킬 없는 에이전트, Hermes 기본 스킬과 커맨드로 못 부르는 이름, 글로 다시 읽히지 않거나 겹치는 에이전트 이름은 뺀다")
    void targetsAreEnabledSpecialistSkills() {
        agents.save(agent("sales", "영업 · 국내", dad.id()));
        agents.save(agent("memo-a", "메모", dad.id()));
        agents.save(agent("memo-b", "메모", dad.id()));
        skillsOf("sales", "position-recommender");
        skillsOf("memo-a", "position-recommender");
        skillsOf("memo-b", "weekly-plan");

        assertThat(delegations.targets(dad))
            .extracting(SkillDelegationTarget::agentCode, SkillDelegationTarget::agentName, SkillDelegationTarget::skills)
            .containsExactly(tuple("career", "커리어", List.of("position-recommender")));
    }


    @Test
    @DisplayName("고른 스킬을 보내면 질문과 부모 없는 커리어 실행이 생기고 turn 은 열리지 않으며, 끝나면 결과 도착 줄과 비서 답이 생긴다")
    void delegatesWithoutTurnAndDeliversResult() {
        Long conversationId = chat.send(dad, null, "안녕", null).conversationId();

        SkillDelegationStarted started = delegations.delegate(dad, conversationId, "career", TEXT);

        assertThat(started.conversation().id()).isEqualTo(conversationId);
        AgentExecution delegated = executions.findById(started.executionId()).orElseThrow();
        assertThat(delegated.parentExecutionId()).isNull();
        assertThat(delegated.agentId()).isEqualTo(career.id());
        assertThat(delegated.delegationKey()).isNotNull();
        assertThat(turns.markOf(conversationId).running()).as("대화 turn 잠금").isFalse();
        ChatMessage question = messages.findByConversationIdOrderByIdAsc(conversationId).getLast();
        assertThat(question.role()).isEqualTo(MessageRole.USER);
        assertThat(question.content()).isEqualTo(TEXT);
        awaitTrue(() -> chat.mentionMarks(dad, conversationId).questions().containsKey(question.id()), "질문 아래 맡김 줄");
        DelegationLine line = chat.mentionMarks(dad, conversationId).questions().get(question.id());
        assertThat(line.executionId()).isEqualTo(started.executionId());
        assertThat(line.agent().name()).isEqualTo("커리어");

        // 맡긴 일이 도는 동안에도 다음 질문에 비서가 바로 답한다(DECISION26).
        assertThat(chat.send(dad, conversationId, "다른 질문", null).assistantText()).isEqualTo("dad 의 답");
        careerRelease.countDown();

        awaitTrue(() -> executions.findById(started.executionId()).orElseThrow().resultDeliveredAt() != null, "결과 전달");
        awaitTrue(() -> !turns.markOf(conversationId).running(), "결과 전달 turn");
        List<ChatMessage> history = messages.findByConversationIdOrderByIdAsc(conversationId);
        assertThat(history).extracting(ChatMessage::role).containsSubsequence(
            MessageRole.USER, MessageRole.USER, MessageRole.ASSISTANT, MessageRole.SYSTEM, MessageRole.ASSISTANT);
        assertThat(stub().received())
            .filteredOn(command -> command.profileName().equals("career"))
            .singleElement()
            .satisfies(command -> assertThat(command.input())
                .contains("skill_view(name=\"position-recommender\")", "이번 주 공고"));
    }


    @Test
    @DisplayName("비서 답이 도는 동안에도 대기 줄 없이 바로 맡긴다")
    void delegatesWhilePrimaryAnswerRuns() throws Exception {
        Long conversationId = chat.startEmpty(dad, null).id();
        primaryRelease = new CountDownLatch(1);
        Thread answering = Thread.ofVirtual().start(() -> chat.send(dad, conversationId, "긴 질문", null));
        awaitTrue(() -> turns.markOf(conversationId).running(), "도는 비서 답");

        SkillDelegationStarted started = delegations.delegate(dad, conversationId, "career", TEXT);

        assertThat(executions.findById(started.executionId()).orElseThrow().agentId()).isEqualTo(career.id());
        primaryRelease.countDown();
        careerRelease.countDown();
        answering.join(WAIT_LIMIT.toMillis());
    }


    @Test
    @DisplayName("대화 번호 없이 보내면 기본 비서와 새 대화를 만들어 거기에 맡긴다")
    void startsPrimaryConversationWhenMissing() {
        SkillDelegationStarted started = delegations.delegate(dad, null, "career", TEXT);

        assertThat(started.conversation().agentId()).isEqualTo(primary.id());
        assertThat(messages.findByConversationIdOrderByIdAsc(started.conversation().id()))
            .extracting(ChatMessage::content)
            .containsExactly(TEXT);
    }


    @Test
    @DisplayName("남의 대화, 읽기 전용 대화, 기본 비서, 꺼진 에이전트, 꺼진 스킬, 꼴이 아닌 글은 각 오류로 거절하고 아무것도 남기지 않는다")
    void rejectsEachInvalidRequestWithoutSaving() {
        Long mine = chat.startEmpty(dad, null).id();
        AppUser mom = users.save(AppUser.of("skill-delegation-mom@example.com", "엄마", 1L, UserRole.MEMBER));
        Long others = conversations.save(Conversation.startedBy(mom.id(), "남의 대화", primary.id(), Instant.now())).id();
        Long past = conversations.save(Conversation.startedBy(dad.id(), "지난 대화", career.id(), Instant.now())).id();
        long savedMessages = messages.count();

        assertCode(() -> delegations.delegate(dad, others, "career", TEXT), ErrorCode.CONVERSATION_NOT_FOUND);
        assertCode(() -> delegations.delegate(dad, past, "career", TEXT), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> delegations.delegate(dad, mine, "dad", "/비서 · weekly-plan"), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> delegations.delegate(dad, mine, "off", "/꺼진 담당 · position-recommender"), ErrorCode.AGENT_DISABLED);
        assertCode(() -> delegations.delegate(dad, mine, "career", "/커리어 · resume-review"), ErrorCode.SKILL_COMMAND_UNKNOWN);
        assertCode(() -> delegations.delegate(dad, mine, "career", "/position-recommender 공고"), ErrorCode.VALIDATION_FAILED);

        assertThat(messages.count()).isEqualTo(savedMessages);
        assertThat(executions.findAll()).isEmpty();
    }


    private void skillsOf(String agentCode, String... names) {
        List<SkillListItem> items = Arrays.stream(names)
            .map(name -> name.endsWith(":off")
                ? new SkillListItem(name.substring(0, name.length() - 4), "", SkillSource.UPLOADED, false, null)
                : name.endsWith(":hermes")
                    ? new SkillListItem(name.substring(0, name.length() - 7), "", SkillSource.HERMES, true, null)
                    : new SkillListItem(name, "", SkillSource.UPLOADED, true, null))
            .toList();
        doReturn(new SkillList(items, false, true, 30))
            .when(skillService)
            .commandList(argThat(agent -> agent != null && agentCode.equals(agent.code())));
    }


    private void clean() {
        executionEvents.deleteAll();
        executions.deleteAll();
        messages.deleteAll();
        conversations.deleteAll();
        agents.deleteAll();
        users.deleteAll();
    }


    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS)) {
                fail("커리어 실행을 풀지 않았다");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }


    private static void awaitTrue(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + WAIT_LIMIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail(what + " 가 " + WAIT_LIMIT + " 안에 오지 않았다");
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        }
    }


    private static void assertCode(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
            .isInstanceOf(ApiException.class)
            .extracting(ex -> ((ApiException)ex).code())
            .isEqualTo(expected);
    }


    private static Agent agent(String code, String name, Long ownerId) {
        return Agent.of(
            code, name, code, "http://agent-runtime.test/p/" + code, CostMode.SUBSCRIPTION, CredentialScope.SHARED_HOUSEHOLD,
            AgentVisibility.PRIVATE, ownerId);
    }
}
