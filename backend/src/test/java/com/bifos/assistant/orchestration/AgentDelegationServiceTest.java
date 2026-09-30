package com.bifos.assistant.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunCommand;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.orchestration.application.AgentDelegationService;
import com.bifos.assistant.orchestration.application.DelegationResult;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.DelegationKey;
import com.bifos.assistant.usage.domain.ExecutionEventType;
import com.bifos.assistant.usage.domain.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 위임 시작의 제출 대기, 서버 전체 한도, 답 자르기, 같은 호출의 동시 요청을 고정한다(ADR-017).
 *
 * <p>설정값을 짧고 작게 바꿔 띄운다. 도구 경계와 요청자 판정은 {@code McpAgentToolsTest} 가 본다. 위임은 가상 스레드에서
 * 돌므로 각 검사는 자기가 띄운 실행이 끝날 때까지 기다린 뒤 끝난다.
 */
@SpringBootTest(properties = {
    "assistant.delegation.submit-timeout=300ms",
    "assistant.delegation.max-active=2",
    "assistant.delegation.output-max-chars=20"
})
@ActiveProfiles("test")
@Import(AgentDelegationServiceTest.StubRuntime.class)
class AgentDelegationServiceTest {

    private static final String CHIEF_PROFILE = "delegation-chief";
    private static final String WORKER = "delegation-worker";
    private static final String EMAIL = "delegation-a@example.com";
    private static final Duration SUBMIT_TIMEOUT = Duration.ofMillis(300);
    private static final int MAX_ACTIVE = 2;

    @TestConfiguration
    static class StubRuntime {
        @Bean
        @Primary
        StubHermesRunsClient stubHermesRunsClient() {
            return new StubHermesRunsClient();
        }
    }

    @Autowired AgentDelegationService delegations;
    @Autowired AppUserRepository users;
    @Autowired AgentRepository agents;
    @Autowired ConversationRepository conversations;
    @Autowired AgentExecutionRepository executions;
    @Autowired ExecutionEventRepository executionEvents;
    @Autowired JdbcTemplate jdbc;
    @Autowired HermesRunsClient hermes;

    private CurrentUser user;
    private AgentExecution origin;
    private String root;

    private StubHermesRunsClient stub() {
        return (StubHermesRunsClient) hermes;
    }

    /** 이 검사의 profile 과 에이전트와 사용자만 지운다. 같은 H2 를 다른 검사 클래스와 함께 쓴다. */
    @BeforeEach
    void 준비한다() {
        stub().reset();
        for (String profile : List.of(CHIEF_PROFILE, WORKER)) {
            jdbc.update("DELETE FROM agent_execution WHERE profile_name = ?", profile);
        }
        agents.findByCode(WORKER).ifPresent(agents::delete);
        users.findByEmail(EMAIL).ifPresent(users::delete);
        AppUser saved = users.save(AppUser.of(EMAIL, "가", 1L, UserRole.MEMBER));
        user = new CurrentUser(saved.id(), saved.email(), saved.displayName(), saved.groupId(), saved.role());
        Agent worker = agents.save(Agent.of(WORKER, "조사원", WORKER, "http://agent-runtime.test/p/" + WORKER,
                CostMode.API, CredentialScope.DEDICATED, AgentVisibility.PRIVATE, saved.id()));
        Conversation conversation = conversations.save(Conversation.startedBy(saved.id(), "맡기기", worker.id()));
        root = "fos-" + UUID.randomUUID();
        origin = executions.save(AgentExecution.builder()
                .userId(saved.id())
                .conversationId(conversation.id())
                .profileName(CHIEF_PROFILE)
                .hermesSessionId(root)
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.RUNNING)
                .startedAt(Instant.parse("2026-09-30T00:00:00Z"))
                .build());
    }

    @Test
    void 제출을_붙잡으면_제한_시간_뒤_번호와_RUNNING_이_오고_풀면_결과가_그_줄에_적힌다() throws Exception {
        stub().willAnswer(command -> completed(command, "붙잡혔던 답"));
        stub().holdSubmits();

        long before = System.nanoTime();
        DelegationResult result = delegate("붙잡힌다");
        Duration waited = Duration.ofNanos(System.nanoTime() - before);

        assertThat(result.accepted()).as("결과: %s", result).isTrue();
        assertThat(result.status()).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(waited).as("제출을 제한 시간까지 기다린다").isGreaterThanOrEqualTo(SUBMIT_TIMEOUT.minusMillis(20));
        AgentExecution held = executions.findById(result.executionId()).orElseThrow();
        assertThat(held.status()).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(held.hermesRunId()).as("아직 제출하지 않았다").isNull();

        stub().releaseSubmits();

        AgentExecution finished = awaitFinished(result.executionId());
        assertThat(finished.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(finished.outputText()).isEqualTo("붙잡혔던 답");
        assertThat(finished.hermesRunId()).isNotNull();
    }

    @Test
    void 답이_상한을_넘으면_잘리고_잘렸다는_한_줄이_붙는다() throws Exception {
        stub().willAnswer(command -> completed(command, command.input().equals("길다") ? "가".repeat(21) : "나".repeat(20)));

        DelegationResult longer = delegate("길다");
        DelegationResult exact = delegate("딱 맞다");

        assertThat(awaitFinished(longer.executionId()).outputText())
                .isEqualTo("가".repeat(20) + "\n\n[답이 20자를 넘어 뒷부분을 잘랐다]");
        AgentExecution exactRow = awaitFinished(exact.executionId());
        assertThat(exactRow.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(exactRow.outputText()).as("상한과 같은 길이는 그대로 둔다").isEqualTo("나".repeat(20));
    }

    @Test
    void 서버_전체_한도를_채우면_BUSY_이고_끝나면_자리가_돌아온다() throws Exception {
        stub().willAnswer(command -> completed(command, "답"));
        stub().holdSubmits();
        List<Long> started = new ArrayList<>();
        for (int i = 0; i < MAX_ACTIVE; i++) {
            // 앞 검사의 실행 스레드가 자리를 막 돌려주는 중일 수 있어 BUSY 이면 잠시 뒤 다시 부른다.
            started.add(acceptedWithin(Duration.ofSeconds(5), "붙잡힌 일 " + i).executionId());
        }

        DelegationResult busy = delegate("넘친다");

        assertThat(busy.accepted()).isFalse();
        assertThat(busy.failure()).isEqualTo(DelegationResult.Failure.BUSY);
        assertThat(busy.executionId()).isNull();
        stub().releaseSubmits();
        for (Long id : started) {
            assertThat(awaitFinished(id).status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        }
        DelegationResult afterwards = acceptedWithin(Duration.ofSeconds(5), "끝난 뒤");
        assertThat(awaitFinished(afterwards.executionId()).status()).isEqualTo(ExecutionStatus.SUCCEEDED);
    }

    @Test
    void 같은_키로_동시에_두_번_부르면_실행이_하나이고_같은_번호가_온다() throws Exception {
        stub().willAnswer(command -> completed(command, "답"));
        stub().holdSubmits();
        DelegationKey key = DelegationKey.of(CHIEF_PROFILE, root, root, "call_" + UUID.randomUUID());
        Callable<DelegationResult> call = () -> delegations.delegate(user, origin, key, WORKER, "같은 호출");

        List<DelegationResult> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<DelegationResult>> futures = pool.invokeAll(List.of(call, call));
            for (Future<DelegationResult> future : futures) {
                results.add(future.get());
            }
        }
        stub().releaseSubmits();

        assertThat(results).allMatch(DelegationResult::accepted, "둘 다 받아들인다");
        assertThat(results.get(1).executionId()).isEqualTo(results.get(0).executionId());
        assertThat(executions.findByRootExecutionId(origin.id())).hasSize(1);
        awaitFinished(results.get(0).executionId());
        assertThat(stub().received()).hasSize(1);
    }

    private DelegationResult delegate(String task) {
        return delegations.delegate(user, origin, DelegationKey.of(CHIEF_PROFILE, root, root, "call_" + UUID.randomUUID()),
                WORKER, task);
    }

    private DelegationResult acceptedWithin(Duration limit, String task) throws InterruptedException {
        long deadline = System.nanoTime() + limit.toNanos();
        while (true) {
            DelegationResult result = delegate(task);
            if (result.accepted()) return result;
            assertThat(result.failure()).as("받아들이지 않은 까닭").isEqualTo(DelegationResult.Failure.BUSY);
            if (System.nanoTime() > deadline) throw new AssertionError(limit + " 안에 자리가 나지 않았다");
            Thread.sleep(10);
        }
    }

    private static HermesRunResult completed(HermesRunCommand command, String output) {
        return HermesRunResult.of("run-" + UUID.randomUUID(), command.sessionId(), "completed", output,
                "example-model", "example-provider", new TokenUsage(3L, 0L, 2L, 5L));
    }

    /** 위임 실행이 끝나고 끝난 사건까지 적힐 때까지 기다린다. */
    private AgentExecution awaitFinished(Long executionId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (true) {
            AgentExecution execution = executions.findById(executionId).orElseThrow();
            boolean ended = executionEvents.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(List.of(executionId)).stream()
                    .anyMatch(event -> event.eventType() != ExecutionEventType.RUN_STARTED);
            if (execution.status() != ExecutionStatus.RUNNING && ended) return execution;
            if (System.nanoTime() > deadline) {
                throw new AssertionError("실행 " + executionId + " 이 끝나지 않았다. 상태: " + execution.status());
            }
            Thread.sleep(10);
        }
    }
}
