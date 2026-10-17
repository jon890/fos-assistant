package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.modelchoice.application.ModelTierService;
import com.bifos.assistant.chat.starter.config.StarterProperties;
import com.bifos.assistant.chat.starter.application.dto.StarterStatus;
import com.bifos.assistant.chat.starter.application.StarterSuggestionService;
import com.bifos.assistant.chat.starter.application.dto.StarterSuggestions;
import com.bifos.assistant.chat.turn.application.TurnCancellation;
import com.bifos.assistant.chat.turn.application.TurnHandle;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ChatMessageRepository;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.hermes.application.port.HermesRunsClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.application.dto.HermesRunResult;
import com.bifos.assistant.hermes.domain.TokenUsage;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.OverrideProperties;
import com.bifos.assistant.testsupport.SmallExecutionLimit;
import com.bifos.assistant.usage.execution.application.ExecutionRecorder;
import com.bifos.assistant.usage.execution.application.UserExecutionLimiter;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.execution.infra.ExecutionEventRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

/**
 * 추천 질문이 사용자 실행 한도에 닿으면 실행 줄 없이 건너뛰는지 본다(ADR-069).
 *
 * <p>한도 2 에 예비 자리 1 이다. turn 자리 하나를 쥔 사용자의 백그라운드 실행은 줄을 만든 뒤 남는 자리가 0 이라 돌지
 * 않는다. 추천 서비스는 {@code StarterSuggestionServiceTest} 처럼 시각과 실행기를 바꿔 끼워 직접 만든다.
 */
@BackendIntegrationTest
@SmallExecutionLimit
@OverrideProperties({
    "assistant.user-execution.background-reserve=1",
    "assistant.starters.enabled=true",
    "assistant.starters.retry-after-failure=10m"
})
class UserExecutionLimitBackgroundTest {

    private static final String PROMPTS = "[\"일정 정리해 줘\",\"장보기 목록 만들어 줘\"]";

    @Autowired
    LiveProperties<StarterProperties> starterProperties;

    @Autowired
    AgentService agentService;

    @Autowired
    ModelTierService modelTiers;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    ExecutionRecorder recorder;

    @Autowired
    UserExecutionLimiter limiter;

    @Autowired
    TurnCancellation turns;

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
    MemoryRepository memories;

    @Autowired
    HermesRunsClient hermes;

    private final TrackingExecutor executor = new TrackingExecutor();
    private StarterSuggestionService starters;
    private CurrentUser dad;
    private Agent agent;
    private Conversation conversation;
    private TurnHandle heldTurn;


    private StubHermesRunsClient stub() {
        return (StubHermesRunsClient)hermes;
    }


    @BeforeEach
    void setUp() {
        stub().reset();
        executionEvents.deleteAll();
        executions.deleteAll();
        messages.deleteAll();
        conversations.deleteAll();
        agents.deleteAll();
        memories.deleteAll();
        users.deleteAll();
        AppUser user = users.save(AppUser.of("background@example.com", "dad", 1L, UserRole.MEMBER));
        dad = new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
        agent = agents.save(Agent.of(
            "background-dad",
            "비서",
            "background-dad",
            "http://runtime.test",
            CostMode.API,
            CredentialScope.DEDICATED,
            AgentVisibility.PRIVATE,
            dad.id()));
        conversation = conversations.save(Conversation.startedBy(dad.id(), "대화", agent.id(), Instant.now()));
        starters = new StarterSuggestionService(
            starterProperties,
            agentService,
            conversations,
            messages,
            hermes,
            recorder,
            modelTiers,
            objectMapper,
            limiter,
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
            executor);
        heldTurn = turns.open(dad.id(), conversation.id());
    }


    @AfterEach
    void tearDown() {
        turns.close(heldTurn);
    }


    @Test
    @DisplayName("turn 자리 하나를 쥔 사용자의 추천 질문은 줄 없이 건너뛰고 자리가 빈 뒤 다음 읽기에서 만든다")
    void skipsStarterWithoutRowWhileTurnHeldAndBuildsOnNextReadAfterSlotFrees() {
        stub().willAnswer(command -> HermesRunResult.of("starter-run", null, "completed", PROMPTS, "model", "provider", TokenUsage.empty()));

        starters.read(dad, agent.code());
        executor.awaitAll();

        assertThat(executions.findAll()).as("건너뛴 추천의 실행 줄").isEmpty();
        assertThat(stub().received()).as("Hermes 제출").isEmpty();
        assertThat(starters.read(dad, agent.code()).status())
            .as("실패 시각을 적지 않아 다음 읽기가 다시 만들기를 시작한다")
            .isEqualTo(StarterStatus.GENERATING);
        executor.awaitAll();
        assertThat(stub().received()).as("자리가 차 있는 동안의 Hermes 제출").isEmpty();

        turns.close(heldTurn);
        starters.read(dad, agent.code());
        executor.awaitAll();
        StarterSuggestions ready = starters.read(dad, agent.code());

        assertThat(ready.status()).isEqualTo(StarterStatus.READY);
        assertThat(ready.prompts()).containsExactly("일정 정리해 줘", "장보기 목록 만들어 줘");
        assertThat(stub().received()).as("자리가 빈 뒤의 Hermes 제출").hasSize(1);
    }


    /** 받은 만들기를 가상 스레드에서 돌리고 끝나기를 기다릴 수 있게 모은다. */
    private static final class TrackingExecutor implements Executor {
        private final List<CompletableFuture<Void>> tasks = new CopyOnWriteArrayList<>();


        @Override
        public void execute(Runnable command) {
            tasks.add(CompletableFuture.runAsync(
                command, runnable -> Thread.ofVirtual().start(runnable)));
        }


        void awaitAll() {
            CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new))
                .orTimeout(5, TimeUnit.SECONDS)
                .join();
        }
    }
}
