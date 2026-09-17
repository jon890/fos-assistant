package com.bifos.assistant.testsupport;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionCost;
import com.bifos.assistant.usage.domain.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 브라우저 검사에서 사용량 화면의 종료 상태를 준비한다. */
@RestController
@RequestMapping("/api/v1/test-support/usage")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "assistant.test-support.enabled", havingValue = "true")
public class UsageTestSupportController {

    /** 한 번에 지우는 줄 수의 상한이다. 검사가 쌓는 양보다 넘친다. */
    private static final int CLEARED_AT_ONCE = 1_000;

    private final AgentExecutionRepository executions;
    private final AgentRepository agents;
    private final CurrentUserProvider currentUser;

    /**
     * 끝난 실행을 여러 건 심는다.
     *
     * <p>축이 둘 이상으로 갈리는 화면을 만들려면 에이전트와 지문과 시작 시각이 서로 다른 실행이
     * 필요한데, 대화를 돌려서는 그런 실행을 만들 수 없다. 심는 실행은 부르는 사람의 것이 된다.
     */
    @PostMapping("/executions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void seedExecutions(@RequestBody List<SeededExecution> seeds) {
        Long userId = currentUser.require().id();
        for (SeededExecution seed : seeds) {
            Agent agent = agents.findByCode(seed.agentCode()).orElseThrow();
            executions.save(AgentExecution.builder()
                    .userId(userId)
                    .conversationId(1L)
                    .agentId(agent.id())
                    .profileName(agent.hermesProfile())
                    .provider(agent.provider())
                    .model(seed.model() == null ? agent.model() : seed.model())
                    .costMode(agent.costMode())
                    .status(ExecutionStatus.SUCCEEDED)
                    .timing(seed.startedAt(), seed.startedAt().plusMillis(1_200L))
                    .tokens(
                            seed.inputTokens(),
                            null,
                            seed.outputTokens(),
                            seed.inputTokens() + seed.outputTokens())
                    .cost(new ExecutionCost(
                            seed.estimatedCostMicros(), seed.actualCostMicros(), "USD", "test-support"))
                    .contextChars(seed.contextChars())
                    .runtimeFingerprint(seed.runtimeFingerprint())
                    .build());
        }
    }

    /** 부르는 사람의 실행 기록을 지운다. 검사가 심은 것만 남기려면 먼저 비워야 한다. */
    @DeleteMapping("/executions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearExecutions() {
        executions.deleteAll(executions.findByUserIdOrderByIdDesc(
                currentUser.require().id(), PageRequest.of(0, CLEARED_AT_ONCE)));
    }

    /**
     * 심을 실행 하나의 값이다.
     *
     * @param model 비우면 에이전트가 가진 모델을 쓴다
     * @param runtimeFingerprint 비우면 지문 없이 심는다
     * @param actualCostMicros 비우면 구독 경로처럼 실제 청구액이 없는 실행이 된다
     */
    public record SeededExecution(
            String agentCode,
            String model,
            String runtimeFingerprint,
            Instant startedAt,
            Long inputTokens,
            Long outputTokens,
            Long contextChars,
            Long estimatedCostMicros,
            Long actualCostMicros) {
    }

    /** 가장 최근 실행을 고아 실행으로 표시한다. */
    @PostMapping("/last-execution/orphaned")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void orphanLastExecution() {
        AgentExecution execution = executions
                .findAll(PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "id")))
                .stream()
                .findFirst()
                .orElseThrow();
        execution.markFailed("ORPHANED", Instant.now());
        executions.save(execution);
    }
}
