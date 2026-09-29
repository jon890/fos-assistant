package com.bifos.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.application.StarterProperties;
import com.bifos.assistant.agent.application.StarterStatus;
import com.bifos.assistant.agent.application.StarterSuggestionService;
import com.bifos.assistant.agent.application.StarterSuggestions;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunCommand;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.application.ExecutionRecorder;
import com.bifos.assistant.usage.domain.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.UserRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

/**
 * 추천 질문을 언제 만들고 무엇을 돌려주는지 본다.
 *
 * <p>서비스를 직접 만들어 시각과 실행기를 바꿔 끼운다. 시각을 옮겨 오래됨과 재시도 시간을 만들고, 실행기가 받은
 * 만들기가 끝나기를 기다린 뒤 결과를 읽는다. 다른 검사에서는 추천이 꺼져 있고 이 검사만 켠다.
 */
@SpringBootTest(properties = {
        "assistant.starters.enabled=true",
        "assistant.starters.refresh-after=24h",
        "assistant.starters.retry-after-failure=10m"
})
@ActiveProfiles("test")
@Import(StarterSuggestionServiceTest.StubRuntime.class)
class StarterSuggestionServiceTest {

    @TestConfiguration
    static class StubRuntime {
        @Bean @Primary StubHermesRunsClient stubHermesRunsClient() { return new StubHermesRunsClient(); }
    }

    private static final CurrentUser DAD = new CurrentUser(81L, "dad@example.com", "아빠", 1L, UserRole.MEMBER);
    private static final CurrentUser KID = new CurrentUser(82L, "kid@example.com", "아이", 1L, UserRole.MEMBER);
    private static final List<String> FOUR = List.of("일정 정리해 줘", "장보기 목록 만들어 줘", "날씨 알려 줘", "가계부 요약해 줘");

    @Autowired StarterProperties properties;
    @Autowired AgentService agentService;
    @Autowired AgentRepository agents;
    @Autowired ConversationRepository conversations;
    @Autowired ChatMessageRepository messages;
    @Autowired AgentExecutionRepository executionRows;
    @Autowired ExecutionRecorder executions;
    @Autowired HermesRunsClient hermes;
    @Autowired ObjectMapper objectMapper;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final TrackingExecutor executor = new TrackingExecutor();
    private StarterSuggestionService service;
    private Agent family;

    @BeforeEach
    void 준비한다() {
        messages.deleteAll();
        conversations.deleteAll();
        executionRows.deleteAll();
        agents.deleteAll();
        stub().reset();
        family = agents.save(Agent.of("starter-family", "가족", "starter-family", "http://runtime.test",
                CostMode.API, CredentialScope.DEDICATED, AgentVisibility.GROUP, DAD.id()));
        service = new StarterSuggestionService(properties, agentService, conversations, messages, hermes,
                executions, objectMapper, clock, executor);
    }

    @Test
    void 캐시가_비면_GENERATING_을_주고_만들기가_끝나면_READY_와_넷을_준다() {
        answerWith(json(FOUR));

        StarterSuggestions first = service.read(DAD, "starter-family");
        assertThat(first.status()).isEqualTo(StarterStatus.GENERATING);
        assertThat(first.prompts()).isEmpty();

        executor.awaitAll();

        StarterSuggestions second = service.read(DAD, "starter-family");
        assertThat(second.status()).isEqualTo(StarterStatus.READY);
        assertThat(second.prompts()).containsExactlyElementsOf(FOUR);
        assertThat(stub().received()).hasSize(1);
        assertThat(stub().received().getFirst().profileName()).isEqualTo("starter-family");
    }

    @Test
    void 추천_실행은_대화_없는_실행_줄로_남고_끝에_SUCCEEDED_다() {
        answerWith(json(FOUR));

        service.read(DAD, "starter-family");
        executor.awaitAll();

        assertThat(executionRows.findAll()).singleElement().satisfies(row -> {
            assertThat(row.conversationId()).isNull();
            assertThat(row.parentExecutionId()).isNull();
            assertThat(row.rootExecutionId()).isNull();
            assertThat(row.hermesSessionId()).isNull();
            assertThat(row.userId()).isEqualTo(DAD.id());
            assertThat(row.agentId()).isEqualTo(family.id());
            assertThat(row.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        });
    }

    @Test
    void 이력이_있으면_그_사용자의_지우지_않은_대화마다_첫_질문을_입력에_싣는다() {
        conversationOf(DAD, family, "이번 주 일정 알려 줘", "다음 질문은 싣지 않는다");
        conversationOf(DAD, family, "장보기 목록 정리해 줘");
        conversationOf(KID, family, "다른 사용자의 질문");
        Conversation deleted = conversationOf(DAD, family, "지운 대화의 질문");
        conversations.deleteIfActive(deleted.id(), DAD.id(), Instant.now());
        Agent other = agents.save(Agent.of("starter-other", "다른", "starter-other", "http://runtime.test",
                CostMode.API, CredentialScope.DEDICATED, AgentVisibility.GROUP, DAD.id()));
        conversationOf(DAD, other, "다른 에이전트와 나눈 질문");
        answerWith(json(FOUR));

        service.read(DAD, "starter-family");
        executor.awaitAll();

        String input = onlyInput();
        assertThat(input.lines().findFirst()).contains(StarterSuggestionService.PROMPT_MARK);
        assertThat(input)
                .contains("이번 주 일정 알려 줘", "장보기 목록 정리해 줘", "JSON 문자열 배열")
                .doesNotContain("다른 사용자의 질문", "다음 질문은 싣지 않는다", "지운 대화의 질문",
                        "다른 에이전트와 나눈 질문", "처음 해 볼 만한");
    }

    @Test
    void 이력이_없으면_처음_해_볼_만한_요청을_묻는다() {
        answerWith(json(FOUR));

        service.read(DAD, "starter-family");
        executor.awaitAll();

        assertThat(onlyInput())
                .startsWith(StarterSuggestionService.PROMPT_MARK)
                .contains("처음 해 볼 만한", "JSON 문자열 배열");
    }

    @Test
    void 답이_JSON_이_아니면_NONE_이고_재시도_시간_안에는_다시_만들지_않는다() {
        answerWith("추천을 만들 수 없습니다");

        service.read(DAD, "starter-family");
        executor.awaitAll();

        assertThat(service.read(DAD, "starter-family"))
                .isEqualTo(new StarterSuggestions(List.of(), StarterStatus.NONE));
        clock.advance(Duration.ofMinutes(9));
        assertThat(service.read(DAD, "starter-family").status()).isEqualTo(StarterStatus.NONE);
        assertThat(stub().received()).as("재시도 시간 안의 제출 수").hasSize(1);
        assertThat(executionRows.findAll()).singleElement().satisfies(row ->
                assertThat(row.status()).isEqualTo(ExecutionStatus.FAILED));

        clock.advance(Duration.ofMinutes(2));
        assertThat(service.read(DAD, "starter-family").status()).isEqualTo(StarterStatus.GENERATING);
        executor.awaitAll();
        assertThat(stub().received()).as("재시도 시간이 지난 뒤의 제출 수").hasSize(2);
    }

    @Test
    void 다시_만들다_실패하면_이전_추천이_남는다() {
        answerWith(json(FOUR));
        service.read(DAD, "starter-family");
        executor.awaitAll();

        answerWith("[깨진 JSON");
        clock.advance(Duration.ofHours(25));
        service.refreshIfStale(DAD, family);
        executor.awaitAll();

        assertThat(stub().received()).hasSize(2);
        assertThat(service.read(DAD, "starter-family"))
                .isEqualTo(new StarterSuggestions(FOUR, StarterStatus.READY));
    }

    @Test
    void Hermes_가_실패해도_이전_추천이_남고_실행_줄은_실패로_남는다() {
        answerWith(json(FOUR));
        service.read(DAD, "starter-family");
        executor.awaitAll();

        stub().willFail(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "down"));
        clock.advance(Duration.ofHours(25));
        service.refreshIfStale(DAD, family);
        executor.awaitAll();

        assertThat(service.read(DAD, "starter-family"))
                .isEqualTo(new StarterSuggestions(FOUR, StarterStatus.READY));
        assertThat(executionRows.findAll())
                .extracting(row -> row.status())
                .containsExactlyInAnyOrder(ExecutionStatus.SUCCEEDED, ExecutionStatus.FAILED);
    }

    @Test
    void 코드_펜스를_떼고_120자를_넘는_줄은_버리고_앞의_넷만_쓴다() {
        String limit = "나".repeat(120);
        answerWith("```json\n" + json(List.of("첫째", "가".repeat(121), limit, "셋째", "넷째", "다섯째")) + "\n```");

        service.read(DAD, "starter-family");
        executor.awaitAll();

        assertThat(service.read(DAD, "starter-family").prompts())
                .containsExactly("첫째", limit, "셋째", "넷째");
    }

    @Test
    void 빈_배열은_실패로_본다() {
        answerWith("[]");

        service.read(DAD, "starter-family");
        executor.awaitAll();

        assertThat(service.read(DAD, "starter-family").status()).isEqualTo(StarterStatus.NONE);
    }

    @Test
    void 같은_키를_만드는_동안_다시_읽어도_제출은_한_번이다() throws InterruptedException {
        answerWith(json(FOUR));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        stub().beforeAwait(() -> {
            entered.countDown();
            awaitQuietly(release);
        });
        try {
            assertThat(service.read(DAD, "starter-family").status()).isEqualTo(StarterStatus.GENERATING);
            assertThat(entered.await(5, TimeUnit.SECONDS)).as("첫 만들기가 Hermes 제출에 닿았다").isTrue();

            StarterSuggestions whileGenerating = service.read(DAD, "starter-family");

            assertThat(whileGenerating).isEqualTo(new StarterSuggestions(List.of(), StarterStatus.GENERATING));
            assertThat(stub().received()).hasSize(1);
        } finally {
            release.countDown();
        }
        executor.awaitAll();
        assertThat(service.read(DAD, "starter-family").status()).isEqualTo(StarterStatus.READY);
        assertThat(stub().received()).hasSize(1);
    }

    @Test
    void 대화를_마쳤을_때는_추천이_있고_오래됐을_때만_다시_만든다() {
        answerWith(json(FOUR));

        service.refreshIfStale(DAD, family);
        executor.awaitAll();
        assertThat(stub().received()).as("추천이 없을 때의 제출 수").isEmpty();

        service.read(DAD, "starter-family");
        executor.awaitAll();
        assertThat(stub().received()).hasSize(1);

        clock.advance(Duration.ofHours(23));
        service.refreshIfStale(DAD, family);
        executor.awaitAll();
        assertThat(stub().received()).as("refreshAfter 안의 제출 수").hasSize(1);

        clock.advance(Duration.ofHours(2));
        service.refreshIfStale(DAD, family);
        executor.awaitAll();
        assertThat(stub().received()).as("refreshAfter 가 지난 뒤의 제출 수").hasSize(2);
    }

    @Test
    void 사용자마다_추천을_따로_만든다() {
        answerWith(json(FOUR));

        service.read(DAD, "starter-family");
        executor.awaitAll();

        assertThat(service.read(KID, "starter-family").status()).isEqualTo(StarterStatus.GENERATING);
        executor.awaitAll();
        assertThat(stub().received()).hasSize(2);
    }

    @Test
    void 볼_수_없는_에이전트의_추천은_AGENT_NOT_FOUND_이고_만들지_않는다() {
        agents.save(Agent.of("starter-private", "개인", "starter-private", "http://runtime.test",
                CostMode.API, CredentialScope.DEDICATED, AgentVisibility.PRIVATE, DAD.id()));
        answerWith(json(FOUR));

        assertThatThrownBy(() -> service.read(KID, "starter-private"))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.code()).isEqualTo(ErrorCode.AGENT_NOT_FOUND));
        executor.awaitAll();
        assertThat(stub().received()).isEmpty();
    }

    private Conversation conversationOf(CurrentUser user, Agent agent, String first, String... later) {
        Conversation conversation = conversations.save(Conversation.startedBy(user.id(), first, agent.id()));
        messages.save(ChatMessage.fromUser(conversation.id(), user.id(), first));
        messages.save(ChatMessage.fromAssistant(conversation.id(), "답", null));
        for (String text : later) {
            messages.save(ChatMessage.fromUser(conversation.id(), user.id(), text));
        }
        return conversation;
    }

    private void answerWith(String output) {
        stub().willAnswer(command ->
                HermesRunResult.of("starter-run", null, "completed", output, "model", "provider", TokenUsage.empty()));
    }

    private String json(List<String> prompts) {
        return objectMapper.writeValueAsString(prompts);
    }

    private String onlyInput() {
        assertThat(stub().received()).hasSize(1);
        HermesRunCommand command = stub().received().getFirst();
        return command.input();
    }

    private StubHermesRunsClient stub() {
        return (StubHermesRunsClient) hermes;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /** 받은 만들기를 virtual thread 로 돌리고, 검사가 그것들이 끝나기를 기다리게 한다. */
    private static final class TrackingExecutor implements Executor {
        private final List<CompletableFuture<Void>> tasks = new CopyOnWriteArrayList<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(CompletableFuture.runAsync(command, runnable -> Thread.ofVirtual().start(runnable)));
        }

        void awaitAll() {
            CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).orTimeout(5, TimeUnit.SECONDS).join();
        }
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
