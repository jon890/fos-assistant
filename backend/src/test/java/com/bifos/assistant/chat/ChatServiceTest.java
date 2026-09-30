package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import com.bifos.assistant.chat.application.AskFormat;
import com.bifos.assistant.chat.application.ChatEvent;
import com.bifos.assistant.chat.application.ArtifactService;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.application.ChatTurn;
import com.bifos.assistant.chat.application.ConversationAccess;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.MessageRole;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.chat.presentation.ChatController;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.hermes.HermesRunEventStream;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.RunEvent;
import com.bifos.assistant.hermes.dto.SessionRuntime;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.MemoryScope;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.skill.domain.SkillUseSource;
import com.bifos.assistant.skill.infra.ExecutionSkillUseRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionEvent;
import com.bifos.assistant.usage.domain.ExecutionEventType;
import com.bifos.assistant.usage.domain.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest
@ActiveProfiles("test")
@Import(ChatServiceTest.StubRuntime.class)
class ChatServiceTest {

    @TestConfiguration
    static class StubRuntime {
        @Bean
        @Primary
        StubHermesRunsClient stubHermesRunsClient() {
            return new StubHermesRunsClient();
        }
    }

    @Autowired ChatService chat;
    /** 결과물 폴더 단락의 문구는 {@code ArtifactTest} 가 글자 그대로 견준다. 여기서는 그 단락을 받아 쓴다. */
    @Autowired ArtifactService artifactService;
    @Autowired ChatController controller;
    @Autowired ConversationAccess access;
    @Autowired AppUserRepository users;
    @Autowired AgentRepository agents;
    @Autowired ConversationRepository conversations;
    @Autowired ChatMessageRepository messages;
    @Autowired AgentExecutionRepository executions;
    @Autowired MemoryService memories;
    @Autowired MemoryRepository memoryRepository;
    @Autowired HermesRunsClient hermes;

    /** 실행 사건을 검사하려면 스트림을 우리가 열어 주어야 한다. */
    @MockitoBean HermesRunEventStream eventStream;

    /** 저장이 실패해도 대화가 이어지는지 보려면 저장소가 던지게 만들 수 있어야 한다. */
    @MockitoSpyBean ExecutionEventRepository executionEvents;

    @Autowired ExecutionSkillUseRepository skillUses;

    private StubHermesRunsClient stub() {
        return (StubHermesRunsClient) hermes;
    }

    /** Hermes 가 스트림으로 이 사건들을 차례로 보낸 것처럼 만든다. */
    private void hermesStreams(RunEvent... events) {
        doAnswer(invocation -> {
            Consumer<RunEvent> onEvent = invocation.getArgument(3);
            for (RunEvent event : events) {
                onEvent.accept(event);
            }
            return null;
        })
                .when(eventStream)
                .open(any(), any(), any(), any(), any());
    }

    private List<ExecutionEvent> eventsOf(Long executionId) {
        return executionEvents.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(List.of(executionId));
    }

    private static List<ExecutionEventType> typesOf(List<ExecutionEvent> events) {
        return events.stream().map(ExecutionEvent::eventType).toList();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @BeforeEach
    void reset() {
        stub().reset();
        skillUses.deleteAll();
        executionEvents.deleteAll();
        executions.deleteAll();
        messages.deleteAll();
        agents.deleteAll();
        memoryRepository.deleteAll();
        users.deleteAll();
    }

    private CurrentUser member(String email, String profileName) {
        AppUser user = users.save(AppUser.of(email, email, 1L, UserRole.MEMBER));
        if (profileName != null) {
            agents.save(
                    Agent.of(
                            profileName,
                            profileName,
                            profileName,
                            "http://agent-runtime.test/p/" + profileName,
                            CostMode.SUBSCRIPTION,
                            CredentialScope.SHARED_HOUSEHOLD,
                            AgentVisibility.PRIVATE,
                            user.id()));
        }
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }

    @Test
    void routes_the_turn_to_the_caller_own_profile_and_records_what_it_used() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub()
                .willReturn(
                        HermesRunResult.of(
                                "run-1",
                                "sess-1",
                                "completed",
                                "저녁은 김치찌개가 좋겠어요.",
                                "example-model-large",
                                "anthropic",
                                new TokenUsage(120L, 80L, 40L, 160L)));
        stub().willReportSessionRuntime(new SessionRuntime("example-model-large", "anthropic"));
        stub().beforeAwait(() ->
                assertThat(executions.findByUserIdOrderByIdDesc(dad.id(), PageRequest.of(0, 10)))
                        .singleElement()
                        .satisfies(execution -> {
                            assertThat(execution.status()).isEqualTo(ExecutionStatus.RUNNING);
                            assertThat(execution.hermesRunId()).isEqualTo("run-1");
                        }));

        ChatTurn turn = chat.send(dad, null, "오늘 저녁 뭐 먹을까?", "dad");

        assertThat(executions.count()).isOne();

        assertThat(stub().received()).singleElement().satisfies(command -> {
            assertThat(command.profileName()).isEqualTo("dad");
            assertThat(command.apiBaseUrl()).isEqualTo("http://agent-runtime.test/p/dad");
            // 사용자가 쓴 글 앞에 결과물 폴더 단락이 매 turn 붙는다.
            assertThat(command.input())
                    .isEqualTo(artifactService.agentPreamble(conversations.findById(turn.conversationId()).orElseThrow()) + "오늘 저녁 뭐 먹을까?");
            // Memory 가 없어도 묻는 형식 안내는 늘 붙는다.
            assertThat(command.instructions()).isEqualTo(AskFormat.GUIDE);
            // 새 대화도 Control Plane 이 정한 session 으로 첫 turn 을 보낸다.
            assertThat(command.sessionId()).startsWith("fos-");
        });
        assertThat(turn.assistantText()).isEqualTo("저녁은 김치찌개가 좋겠어요.");

        AgentExecution execution = executions.findById(turn.executionId()).orElseThrow();
        assertThat(execution.userId()).isEqualTo(dad.id());
        assertThat(execution.profileName()).isEqualTo("dad");
        assertThat(execution.provider()).isEqualTo("anthropic");
        assertThat(execution.model()).isEqualTo("example-model-large");
        assertThat(execution.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(execution.inputTokens()).isEqualTo(120);
        assertThat(execution.cachedInputTokens()).isEqualTo(80);
        assertThat(execution.outputTokens()).isEqualTo(40);
        assertThat(execution.totalTokens()).isEqualTo(160);
        assertThat(execution.costMode()).isEqualTo(CostMode.SUBSCRIPTION);
        assertThat(execution.estimatedCostMicros()).isNull();
        assertThat(execution.latencyMs()).isGreaterThanOrEqualTo(0);
        assertThat(execution.contextChars()).isZero();

        assertThat(messages.findByConversationIdOrderByIdAsc(turn.conversationId()))
                .satisfiesExactly(
                        message -> {
                            assertThat(message.role()).isEqualTo(MessageRole.USER);
                            assertThat(message.senderUserId()).isEqualTo(dad.id());
                        },
                        message -> {
                            assertThat(message.role()).isEqualTo(MessageRole.ASSISTANT);
                            assertThat(message.senderUserId()).isNull();
                        });
    }

    @Test
    void 조립한_Memory를_Hermes에_보내고_실행_기록에도_길이를_남긴다() {
        CurrentUser dad = member("dad@example.com", "dad");
        memories.create(dad, MemoryScope.USER, "선호", "국수는 맵지 않게 먹는다", true);
        stub().willReturn(
                HermesRunResult.of("run-1", "sess-1", "completed", "네", "dad", null, TokenUsage.empty()));

        ChatTurn turn = chat.send(dad, null, "저녁 메뉴", "dad");

        String instructions = stub().received().getFirst().instructions();
        assertThat(instructions).contains("국수는 맵지 않게 먹는다").endsWith("\n\n" + AskFormat.GUIDE);
        // 실행 기록의 길이는 Memory 몫만 센다. 형식 안내는 Memory 상한과 무관하게 붙는다.
        assertThat(executions.findById(turn.executionId()).orElseThrow().contextChars())
                .isEqualTo((long) (instructions.length() - ("\n\n" + AskFormat.GUIDE).length()));
    }

    @Test
    void message_history_includes_the_user_display_name_only_on_user_messages() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub()
                .willReturn(
                        HermesRunResult.of(
                                "run-1", "sess-1", "completed", "반가워요", "m", "p", TokenUsage.empty()));
        ChatTurn turn = chat.send(dad, null, "안녕", "dad");
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(dad, null));

        assertThat(controller.messages(turn.conversationPublicId()))
                .satisfiesExactly(
                        message -> {
                            assertThat(message.role()).isEqualTo("USER");
                            assertThat(message.senderName()).isEqualTo("dad@example.com");
                        },
                        message -> {
                            assertThat(message.role()).isEqualTo("ASSISTANT");
                            assertThat(message.senderName()).isNull();
                        });
    }

    /**
     * 압축 교체로 Hermes 가 보낸 것과 다른 session 을 돌려준 경우다.
     *
     * <p>다음 turn 은 돌려받은 session 을 보내지만, 대화의 뿌리와 실행 줄에는 처음 정한 session 이 남는다.
     */
    @Test
    void continues_the_same_hermes_session_on_the_next_turn() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub()
                .willReturn(
                        HermesRunResult.of(
                                "run-1", "sess-1", "completed", "네", "m", "p", TokenUsage.empty()));
        ChatTurn first = chat.send(dad, null, "안녕", "dad");

        stub()
                .willReturn(
                        HermesRunResult.of(
                                "run-2", "sess-1", "completed", "네", "m", "p", TokenUsage.empty()));
        ChatTurn second = chat.send(dad, first.conversationId(), "하나 더", "mom");

        String root = stub().received().get(0).sessionId();
        assertThat(root).startsWith("fos-");
        assertThat(stub().received().get(1).sessionId()).isEqualTo("sess-1");
        var conversation = conversations.findById(first.conversationId()).orElseThrow();
        assertThat(conversation.hermesSessionId()).as("다음에 보낼 session").isEqualTo("sess-1");
        assertThat(conversation.hermesRootSessionId()).as("뿌리 session").isEqualTo(root);
        assertThat(executions.findById(second.executionId()).orElseThrow().hermesSessionId())
                .as("둘째 실행 줄의 session").isEqualTo(root);
    }

    /** Hermes 가 받은 session 을 그대로 돌려주게 한다. 실제 Hermes 가 모르는 id 를 받았을 때와 같다. */
    private void hermesEchoesSession() {
        stub().willAnswer(command -> HermesRunResult.of(
                "run-" + stub().received().size(), command.sessionId(), "completed", "네", "m", "p",
                TokenUsage.empty()));
    }

    @Test
    void 새_대화의_첫_turn은_정한_session을_보내고_대화의_두_칸과_실행_줄에_제출_전에_적는다() {
        CurrentUser dad = member("dad@example.com", "dad");
        AtomicReference<String> recordedAtSubmit = new AtomicReference<>();
        stub().willAnswer(command -> {
            // 제출하는 순간 이미 실행 줄에 session 이 적혀 있어야 한다.
            recordedAtSubmit.set(executions.findByUserIdOrderByIdDesc(dad.id(), PageRequest.of(0, 10))
                    .getFirst().hermesSessionId());
            return HermesRunResult.of(
                    "run-1", command.sessionId(), "completed", "네", "m", "p", TokenUsage.empty());
        });

        ChatTurn turn = chat.send(dad, null, "안녕", "dad");

        String sent = stub().received().getFirst().sessionId();
        assertThat(sent).startsWith("fos-");
        assertThat(recordedAtSubmit.get()).as("제출할 때 실행 줄의 session").isEqualTo(sent);
        var conversation = conversations.findById(turn.conversationId()).orElseThrow();
        assertThat(conversation.hermesSessionId()).isEqualTo(sent);
        assertThat(conversation.hermesRootSessionId()).isEqualTo(sent);
        assertThat(executions.findById(turn.executionId()).orElseThrow().hermesSessionId()).isEqualTo(sent);
    }

    @Test
    void 둘째_turn은_같은_session을_보내고_새로_만들지_않는다() {
        CurrentUser dad = member("dad@example.com", "dad");
        hermesEchoesSession();

        ChatTurn first = chat.send(dad, null, "안녕", "dad");
        ChatTurn second = chat.send(dad, first.conversationId(), "하나 더", "dad");

        String sent = stub().received().get(0).sessionId();
        assertThat(stub().received().get(1).sessionId()).isEqualTo(sent);
        assertThat(conversations.findById(first.conversationId()).orElseThrow().hermesRootSessionId())
                .isEqualTo(sent);
        assertThat(executions.findById(second.executionId()).orElseThrow().hermesSessionId()).isEqualTo(sent);
    }

    @Test
    void 뿌리가_없는_옛_대화는_Hermes가_정한_session을_보내고_실행_줄에도_그_값을_적는다() {
        CurrentUser dad = member("dad@example.com", "dad");
        Long agentId = agents.findByCode("dad").orElseThrow().id();
        Conversation legacy = conversations.save(Conversation.startedBy(dad.id(), "옛 대화", agentId));
        conversations.touchSession(legacy.id(), "hermes-made-session", Instant.now());
        hermesEchoesSession();

        ChatTurn turn = chat.send(dad, legacy.id(), "이어서", "dad");

        assertThat(stub().received().getFirst().sessionId()).isEqualTo("hermes-made-session");
        assertThat(executions.findById(turn.executionId()).orElseThrow().hermesSessionId())
                .isEqualTo("hermes-made-session");
        assertThat(conversations.findById(legacy.id()).orElseThrow().hermesRootSessionId()).isNull();
    }

    @Test
    void 기본값으로_보냈고_세션이_답하지_못하면_provider와_모델이_비어_있다() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub()
                .willReturn(
                        HermesRunResult.of(
                                "run-1", "sess-1", "completed", "네", "dad", null, TokenUsage.empty()));

        ChatTurn turn = chat.send(dad, null, "안녕", "dad");

        AgentExecution execution = executions.findById(turn.executionId()).orElseThrow();
        assertThat(execution.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(execution.model()).as("model").isNull();
        assertThat(execution.provider()).as("provider").isNull();
    }

    @Test
    void refuses_a_member_with_no_profile_bound_and_never_calls_the_runtime() {
        CurrentUser kid = member("kid@example.com", null);

        assertThatThrownBy(() -> chat.send(kid, null, "숙제 도와줘", "missing"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.AGENT_NOT_FOUND);
        assertThat(stub().received()).isEmpty();
    }

    @Test
    void refuses_to_read_another_member_conversation() {
        CurrentUser dad = member("dad@example.com", "dad");
        CurrentUser mom = member("mom@example.com", "mom");
        stub()
                .willReturn(
                        HermesRunResult.of(
                                "run-1", "sess-1", "completed", "네", "m", "p", TokenUsage.empty()));
        ChatTurn dadTurn = chat.send(dad, null, "비밀 얘기", "dad");

        assertThatThrownBy(() -> chat.history(mom, dadTurn.conversationId()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.CONVERSATION_NOT_FOUND);
    }

    @Test
    void records_a_failed_run_so_the_usage_view_still_shows_it() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willFail(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "down"));

        assertThatThrownBy(() -> chat.send(dad, null, "안녕", "dad")).isInstanceOf(ApiException.class);

        var recorded = executions.findByUserIdOrderByIdDesc(dad.id(), PageRequest.of(0, 10));
        assertThat(recorded).singleElement().satisfies(execution -> {
            assertThat(execution.status()).isEqualTo(ExecutionStatus.FAILED);
            assertThat(execution.errorCode()).isEqualTo("HERMES_UNAVAILABLE");
        });
    }

    @Test
    void Hermes가_실패_결과를_돌려줘도_실행_줄_하나를_FAILED로_갱신한다() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(
                HermesRunResult.of("run-1", "sess-1", "failed", null, "dad", null, TokenUsage.empty()));

        assertThatThrownBy(() -> chat.send(dad, null, "안녕", "dad"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.HERMES_RUN_FAILED);

        assertThat(executions.findByUserIdOrderByIdDesc(dad.id(), PageRequest.of(0, 10)))
                .singleElement()
                .satisfies(execution -> {
                    assertThat(execution.status()).isEqualTo(ExecutionStatus.FAILED);
                    assertThat(execution.hermesRunId()).isEqualTo("run-1");
                    assertThat(execution.errorCode()).isEqualTo("FAILED");
                });
    }

    /**
     * 모델이 {@code skill_view} 로 스킬을 읽으면 그 실행에 {@code MODEL} 호출 이력이 남는다. 같은 스킬을 참고
     * 파일까지 두 번 읽어도 한 줄이다. 도구 사건 자체는 다른 도구와 같이 남는다.
     */
    @Test
    void 스트림에서_skill_view_도구_사건이_오면_그_실행에_MODEL_이력이_하나_생긴다() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(
                HermesRunResult.of("run-1", "sess-1", "completed", "장을 봤어요", "dad", null, TokenUsage.empty()));
        hermesStreams(
                new RunEvent("tool.started", null, "skill_view", "shopping", null, null),
                new RunEvent("tool.completed", null, "skill_view", null, 50L, false),
                new RunEvent("tool.started", null, "skill_view", "shopping → references/list.md", null, null),
                new RunEvent("tool.completed", null, "skill_view", null, 50L, false),
                new RunEvent("run.completed", null, null, null, null, null));

        List<ChatEvent> relayed = new ArrayList<>();
        chat.stream(dad, null, "장보기 스킬을 써 줘", "dad", relayed::add);

        Long executionId = relayed.getLast().executionId();
        assertThat(skillUses.findByExecutionIdInOrderByExecutionIdAscSkillNameAsc(List.of(executionId)))
                .singleElement()
                .satisfies(use -> {
                    assertThat(use.skillName()).isEqualTo("shopping");
                    assertThat(use.source()).isEqualTo(SkillUseSource.MODEL);
                });
        assertThat(typesOf(eventsOf(executionId)))
                .containsExactly(
                        ExecutionEventType.RUN_STARTED,
                        ExecutionEventType.TOOL_STARTED,
                        ExecutionEventType.TOOL_COMPLETED,
                        ExecutionEventType.TOOL_STARTED,
                        ExecutionEventType.TOOL_COMPLETED,
                        ExecutionEventType.RUN_COMPLETED);
    }

    @Test
    void 스트리밍_한_번이_RUN_STARTED로_시작해_RUN_COMPLETED로_끝나는_사건을_남긴다() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(
                HermesRunResult.of("run-1", "sess-1", "completed", "네", "dad", null, TokenUsage.empty()));
        hermesStreams(
                new RunEvent("message.delta", "조각", null, null, null, null),
                new RunEvent("tool.started", null, "web_search", "started", null, null),
                new RunEvent("tool.completed", null, "web_search", null, 1500L, false),
                new RunEvent("subagent.start", null, "researcher", "찾는다", null, null),
                new RunEvent("subagent.complete", null, "researcher", "찾았다", 2000L, false),
                new RunEvent("run.completed", null, null, null, null, null));

        List<ChatEvent> relayed = new ArrayList<>();
        chat.stream(dad, null, "도구를 써 줘", "dad", relayed::add);

        Long executionId = relayed.getLast().executionId();
        List<ExecutionEvent> recorded = eventsOf(executionId);
        assertThat(typesOf(recorded))
                .containsExactly(
                        ExecutionEventType.RUN_STARTED,
                        ExecutionEventType.TOOL_STARTED,
                        ExecutionEventType.TOOL_COMPLETED,
                        ExecutionEventType.SUBAGENT_STARTED,
                        ExecutionEventType.SUBAGENT_COMPLETED,
                        ExecutionEventType.RUN_COMPLETED);
        assertThat(recorded).extracting(ExecutionEvent::sequence).containsExactly(1, 2, 3, 4, 5, 6);
        assertThat(recorded.get(2).toolName()).isEqualTo("web_search");
        assertThat(recorded.get(2).durationMs()).isEqualTo(1500L);
        assertThat(recorded.get(3).subagentName()).isEqualTo("researcher");
        assertThat(recorded.get(3).toolName()).isNull();
        assertThat(relayed.stream().filter(it -> "tool".equals(it.type())).toList())
                .extracting(ChatEvent::phase).containsExactly("started", "completed");
        assertThat(relayed.stream().filter(it -> "subagent".equals(it.type())).toList())
                .extracting(ChatEvent::phase).containsExactly("started", "completed");
        var summary = chat.activitySummaries(chat.history(dad, access.requireOwnId(dad, relayed.getLast().conversationId()))).get(executionId);
        assertThat(summary.toolCount()).isEqualTo(1);
        assertThat(summary.subagentCount()).isEqualTo(1);
        assertThat(summary.durationMs()).isNotNull();
    }

    @Test
    void 사건이_없는_자손은_작업_과정에_세지_않는다() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(HermesRunResult.of("run-1", "sess-1", "completed", "네", "dad", null, TokenUsage.empty()));
        ChatTurn turn = chat.send(dad, null, "안녕", "dad");
        AgentExecution root = executions.findById(turn.executionId()).orElseThrow();
        executions.save(AgentExecution.builder()
                .userId(dad.id()).conversationId(turn.conversationId()).agentId(root.agentId())
                .parentExecutionId(root.id()).rootExecutionId(root.id()).profileName(root.profileName())
                .costMode(root.costMode()).status(ExecutionStatus.RUNNING).startedAt(root.startedAt()).build());

        assertThat(chat.activitySummaries(chat.history(dad, turn.conversationId()))).isEmpty();
    }

    /**
     * Hermes 도 실행이 끝났다는 사건을 보내므로 두 줄이 되기 쉽다. 이 검사가 그것을 막는다. 실행의 끝을
     * 적는 자리는 {@code ChatService} 하나여야 한다.
     */
    @Test
    void 스트리밍_한_번이_RUN_COMPLETED를_한_줄만_남긴다() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(
                HermesRunResult.of("run-1", "sess-1", "completed", "네", "dad", null, TokenUsage.empty()));
        hermesStreams(
                new RunEvent("tool.started", null, "web_search", "started", null, null),
                new RunEvent("run.completed", null, null, null, null, null));

        List<ChatEvent> relayed = new ArrayList<>();
        chat.stream(dad, null, "안녕", "dad", relayed::add);

        assertThat(typesOf(eventsOf(relayed.getLast().executionId())))
                .filteredOn(ExecutionEventType.RUN_COMPLETED::equals)
                .hasSize(1);
    }

    @Test
    void 글자_조각은_사건으로_저장하지_않는다() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(
                HermesRunResult.of("run-1", "sess-1", "completed", "네", "dad", null, TokenUsage.empty()));
        hermesStreams(
                new RunEvent("message.delta", "조각 하나", null, null, null, null),
                new RunEvent("message.delta", "조각 둘", null, null, null, null));

        List<ChatEvent> relayed = new ArrayList<>();
        chat.stream(dad, null, "안녕", "dad", relayed::add);

        assertThat(relayed).filteredOn(event -> event.type().equals("delta")).hasSize(2);
        assertThat(typesOf(eventsOf(relayed.getLast().executionId())))
                .containsExactly(ExecutionEventType.RUN_STARTED, ExecutionEventType.RUN_COMPLETED);
    }

    @Test
    void 사건_저장이_예외를_던져도_대화는_성공하고_중계도_이어진다() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(
                HermesRunResult.of("run-1", "sess-1", "completed", "저녁은 김치찌개", "dad", null, TokenUsage.empty()));
        hermesStreams(
                new RunEvent("message.delta", "저녁은 ", null, null, null, null),
                new RunEvent("tool.started", null, "web_search", "started", null, null));
        doThrow(new org.springframework.dao.DataIntegrityViolationException("사건을 저장할 수 없다"))
                .when(executionEvents)
                .save(any(ExecutionEvent.class));

        List<ChatEvent> relayed = new ArrayList<>();
        chat.stream(dad, null, "오늘 저녁 뭐 먹을까?", "dad", relayed::add);

        ChatEvent done = relayed.getLast();
        assertThat(done.type()).isEqualTo("done");
        assertThat(relayed).extracting(ChatEvent::type).containsExactly("started", "delta", "tool", "done");
        assertThat(executions.findById(done.executionId()).orElseThrow().status())
                .isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(messages.findByConversationIdOrderByIdAsc(access.requireOwnId(dad, done.conversationId())))
                .last()
                .satisfies(message -> assertThat(message.content()).isEqualTo("저녁은 김치찌개"));
    }

    @Test
    void 한_번에_받는_경로는_RUN_STARTED와_RUN_COMPLETED_둘만_남긴다() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(
                HermesRunResult.of("run-1", "sess-1", "completed", "네", "dad", null, TokenUsage.empty()));

        ChatTurn turn = chat.send(dad, null, "안녕", "dad");

        List<ExecutionEvent> recorded = eventsOf(turn.executionId());
        assertThat(typesOf(recorded))
                .containsExactly(ExecutionEventType.RUN_STARTED, ExecutionEventType.RUN_COMPLETED);
        assertThat(recorded).extracting(ExecutionEvent::sequence).containsExactly(1, 2);
    }

    @Test
    void 막히면_넘기지_않고_PROVIDER_BLOCKED_로_실패한다() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturnInOrder(
                new HermesRunResult("run-blocked", "sess-1", "failed", null, null, null,
                        HermesRunResult.PROVIDER_AUTH_FAILED_PREFIX + " no account", TokenUsage.empty()),
                HermesRunResult.of("run-answer", "sess-1", "completed", "답", null, null, TokenUsage.empty()));

        List<ChatEvent> relayed = new ArrayList<>();
        assertThatThrownBy(() -> chat.stream(dad, null, "찾아 줘", "dad", relayed::add))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.PROVIDER_BLOCKED);

        assertThat(stub().received()).as("Hermes 를 부른 횟수").hasSize(1);
        assertThat(relayed).extracting(ChatEvent::type).doesNotContain("switched", "reset");
        assertThat(executions.findByUserIdOrderByIdDesc(dad.id(), PageRequest.of(0, 10)))
                .singleElement()
                .satisfies(execution -> {
                    assertThat(execution.status()).isEqualTo(ExecutionStatus.FAILED);
                    assertThat(execution.errorCode()).isEqualTo("PROVIDER_BLOCKED");
                });
    }

    @Test
    void Hermes가_실패로_끝나면_RUN_FAILED가_남고_예외는_그대로_올라간다() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willReturn(
                HermesRunResult.of("run-1", "sess-1", "failed", null, "dad", null, TokenUsage.empty()));

        assertThatThrownBy(() -> chat.send(dad, null, "안녕", "dad"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.HERMES_RUN_FAILED);

        Long executionId =
                executions.findByUserIdOrderByIdDesc(dad.id(), PageRequest.of(0, 10)).getFirst().id();
        assertThat(eventsOf(executionId))
                .satisfiesExactly(
                        started -> assertThat(started.eventType()).isEqualTo(ExecutionEventType.RUN_STARTED),
                        failed -> {
                            assertThat(failed.eventType()).isEqualTo(ExecutionEventType.RUN_FAILED);
                            assertThat(failed.detail()).isEqualTo("FAILED");
                            assertThat(failed.sequence()).isEqualTo(2);
                        });
    }

    @Test
    void 제출이_실패하면_RUN_FAILED_하나만_남는다() {
        CurrentUser dad = member("dad@example.com", "dad");
        stub().willFail(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "down"));

        assertThatThrownBy(() -> chat.send(dad, null, "안녕", "dad")).isInstanceOf(ApiException.class);

        Long executionId =
                executions.findByUserIdOrderByIdDesc(dad.id(), PageRequest.of(0, 10)).getFirst().id();
        assertThat(eventsOf(executionId))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.eventType()).isEqualTo(ExecutionEventType.RUN_FAILED);
                    assertThat(event.detail()).isEqualTo("HERMES_UNAVAILABLE");
                    assertThat(event.sequence()).isEqualTo(1);
                });
    }
}
