package com.bifos.assistant.usage;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.context.AssembledContext;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.usage.application.ExecutionContextSnapshot;
import com.bifos.assistant.usage.application.ExecutionRecorder;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionStatus;
import com.bifos.assistant.usage.domain.MonthlyCost;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.UserRole;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** 실행 시작과 종료가 하나의 기록을 상태 전이하는지 확인한다. */
@SpringBootTest
@ActiveProfiles("test")
class ExecutionLifecycleTest {

    private static final Long USER_ID = 4_102L;

    /** 실행 줄 어디에도 남으면 안 되는 개인 기록을 흉내낸 문자열이다. */
    private static final String SECRET_MEMORY = "아빠는 매주 목요일에 병원에 간다";

    @Autowired ExecutionRecorder recorder;
    @Autowired AgentExecutionRepository executions;
    @Autowired ConversationRepository conversations;

    private Conversation conversation;

    @BeforeEach
    void 준비한다() {
        executions.deleteAll();
        conversation = conversations.save(Conversation.startedBy(USER_ID, "실행", null));
    }

    @Test
    void 시작한_실행은_종료_정보_없이_RUNNING이고_부모와_뿌리를_저장한다() {
        AgentExecution execution = recorder.start(user(), conversation, agent(), 12L, 3L, 0L);

        assertThat(execution.status()).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(execution.finishedAt()).isNull();
        assertThat(execution.latencyMs()).isNull();
        assertThat(execution.parentExecutionId()).isEqualTo(12L);
        assertThat(execution.rootExecutionId()).isEqualTo(3L);
    }

    @Test
    void 완료는_같은_줄에_토큰과_금액을_갱신한다() {
        AgentExecution execution = recorder.start(user(), conversation, agent(), null, null, 0L);
        Long id = execution.id();

        AgentExecution completed = recorder.complete(execution, agent(), result());

        assertThat(completed.id()).isEqualTo(id);
        assertThat(executions.count()).isOne();
        assertThat(executions.findById(id).orElseThrow()).satisfies(saved -> {
            assertThat(saved.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
            assertThat(saved.inputTokens()).isEqualTo(120L);
            assertThat(saved.estimatedCostMicros()).isNull();
            assertThat(saved.finishedAt()).isNotNull();
            assertThat(saved.latencyMs()).isNotNull();
        });
    }

    @Test
    void 실패와_run_번호_연결은_같은_줄을_갱신한다() {
        AgentExecution execution = recorder.start(user(), conversation, agent(), null, null, 0L);
        Long id = execution.id();

        recorder.attachRunId(execution, "run-1");
        AgentExecution failed = recorder.fail(execution, "HERMES_UNAVAILABLE");

        assertThat(failed.id()).isEqualTo(id);
        assertThat(executions.count()).isOne();
        assertThat(executions.findById(id).orElseThrow()).satisfies(saved -> {
            assertThat(saved.hermesRunId()).isEqualTo("run-1");
            assertThat(saved.status()).isEqualTo(ExecutionStatus.FAILED);
            assertThat(saved.errorCode()).isEqualTo("HERMES_UNAVAILABLE");
        });
    }

    @Test
    void 같은_instructions_는_같은_해시로_적히고_본문은_어디에도_저장되지_않는다() {
        AssembledContext context = contextOf(SECRET_MEMORY);

        AgentExecution execution = recorder.start(user(), conversation, agent(), null, null,
                new ExecutionContextSnapshot(context.chars(), null, context.instructionsHash()));

        AgentExecution saved = executions.findById(execution.id()).orElseThrow();
        assertThat(saved.instructionsHash())
                .isEqualTo(contextOf(SECRET_MEMORY).instructionsHash())
                .hasSize(32);
        assertThat(stringColumnsOf(saved))
                .noneSatisfy(value -> assertThat(value).contains(SECRET_MEMORY));
    }

    @Test
    void 다른_instructions_는_다른_해시로_적힌다() {
        AssembledContext one = contextOf(SECRET_MEMORY);
        AssembledContext other = contextOf(SECRET_MEMORY + " 그리고 하나 더");

        assertThat(one.instructionsHash()).isNotEqualTo(other.instructionsHash());
    }

    @Test
    void 문맥이_없으면_해시_칸도_비운다() {
        assertThat(AssembledContext.empty().instructionsHash()).isNull();
        assertThat(new AssembledContext("", 0).instructionsHash()).isNull();

        AgentExecution execution = recorder.start(user(), conversation, agent(), null, null,
                new ExecutionContextSnapshot(0L, null, AssembledContext.empty().instructionsHash()));

        assertThat(executions.findById(execution.id()).orElseThrow().instructionsHash()).isNull();
    }

    @Test
    void 설정_지문은_읽는_경로가_없어_비어_있다() {
        AssembledContext context = contextOf(SECRET_MEMORY);

        AgentExecution execution = recorder.start(user(), conversation, agent(), null, null,
                new ExecutionContextSnapshot(context.chars(), null, context.instructionsHash()));

        assertThat(executions.findById(execution.id()).orElseThrow().runtimeFingerprint()).isNull();
    }

    @Test
    void 실행_중인_줄은_가격_미확인_실행으로_세지_않는다() {
        recorder.start(user(), conversation, agent(), null, null, 0L);

        MonthlyCost cost = executions.sumCostBetween(
                USER_ID, Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));

        assertThat(cost.totalMicros()).isZero();
        assertThat(cost.pricedExecutions()).isZero();
        assertThat(cost.unpricedExecutions()).isZero();
    }

    private static AssembledContext contextOf(String memoryContent) {
        String instructions = "# 지금 묻는 사람에 대해 아는 것\n\n- " + memoryContent;
        return new AssembledContext(instructions, instructions.length());
    }

    /**
     * 저장된 실행 줄의 문자열 칸을 모두 모은다. 어느 칸에도 본문이 남지 않은 것을 확인하기 위해서다.
     *
     * <p>{@code AgentExecution} 에 문자열 칸을 더하면 여기도 더한다. 빠뜨리면 그 칸에 본문이 남아도
     * 이 단언이 보지 못한 채 테스트가 통과한다.
     */
    private static List<String> stringColumnsOf(AgentExecution execution) {
        return Stream.of(
                        execution.profileName(),
                        execution.hermesRunId(),
                        execution.provider(),
                        execution.model(),
                        execution.costMode().name(),
                        execution.status().name(),
                        execution.errorCode(),
                        execution.runtimeFingerprint(),
                        execution.instructionsHash(),
                        execution.costCurrency(),
                        execution.pricingVersion())
                .filter(Objects::nonNull)
                .toList();
    }

    private static CurrentUser user() {
        return new CurrentUser(USER_ID, "dad@example.com", "dad", 1L, UserRole.ADMIN);
    }

    private static Agent agent() {
        return Agent.of("dad", "Dad", "dad", "http://127.0.0.1:1/p/dad", "anthropic", "claude-opus-5",
                CostMode.SUBSCRIPTION, CredentialScope.SHARED_HOUSEHOLD, AgentVisibility.PRIVATE, USER_ID);
    }

    private static HermesRunResult result() {
        return new HermesRunResult("run-1", "session-1", "completed", "끝", "claude-opus-5", "anthropic",
                new TokenUsage(120L, 80L, 40L, 160L));
    }
}
