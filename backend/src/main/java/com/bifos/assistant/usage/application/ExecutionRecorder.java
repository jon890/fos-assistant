package com.bifos.assistant.usage.application;

import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.EstimatedCost;
import com.bifos.assistant.usage.domain.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 에이전트 turn 하나를 한 줄로 남긴다. 그래야 사용량을 사용자, 모델, provider 로 되짚을 수 있다.
 *
 * <p>비용은 실행이 끝나는 이 자리에서 환산하고 쓴 가격표와 함께 저장한다. 조회할 때 다시 계산하면
 * 가격이 바뀔 때 지난달 합계가 따라 움직인다.
 */
@Service
@RequiredArgsConstructor
public class ExecutionRecorder {

    private final AgentExecutionRepository executions;
    private final CostEstimator costs;

    public AgentExecution recordSuccess(
            CurrentUser user,
            Conversation conversation,
            Agent agent,
            HermesRunResult result,
            Instant startedAt) {
        TokenUsage usage = result.usage() == null ? TokenUsage.empty() : result.usage();
        String provider = firstNonBlank(result.provider(), agent.provider());
        String model = modelOf(result, agent);
        return executions.save(
                base(user, conversation, agent, startedAt)
                        .hermesRunId(result.runId())
                        .provider(provider)
                        .model(model)
                        .status(ExecutionStatus.SUCCEEDED)
                        .cost(costs.estimate(provider, model, usage))
                        .tokens(
                                usage.inputTokens(),
                                usage.cachedInputTokens(),
                                usage.outputTokens(),
                                usage.totalTokens())
                        .build());
    }

    public AgentExecution recordFailure(
            CurrentUser user,
            Conversation conversation,
            Agent agent,
            String errorCode,
            Instant startedAt) {
        return recordFailure(user, conversation, agent, errorCode, startedAt, null);
    }

    public AgentExecution recordFailure(
            CurrentUser user,
            Conversation conversation,
            Agent agent,
            String errorCode,
            Instant startedAt,
            String hermesRunId) {
        return executions.save(
                base(user, conversation, agent, startedAt)
                        .hermesRunId(hermesRunId)
                        .provider(agent.provider())
                        .model(agent.model())
                        .status(ExecutionStatus.FAILED)
                        .errorCode(errorCode)
                        // 실패한 실행은 토큰 수를 보고하지 않으므로 환산할 것이 없다.
                        .cost(EstimatedCost.unknown())
                        .build());
    }

    private AgentExecution.Builder base(
            CurrentUser user, Conversation conversation, Agent agent, Instant startedAt) {
        return AgentExecution.builder()
                .userId(user.id())
                .conversationId(conversation.id())
                .workspaceId(conversation.workspaceId())
                .agentId(agent.id())
                .profileName(agent.hermesProfile())
                .costMode(agent.costMode())
                .timing(startedAt, Instant.now());
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    /**
     * Picks the model to record.
     *
     * <p>A run's {@code model} is the API server's model name, which defaults to the profile name.
     * A real Hermes run on profile {@code bifos} reports {@code "model": "bifos"}, so taking it at
     * face value would label every execution with the member's name instead of the model they used.
     * When the run just echoes the profile, the binding's configured model is the truthful answer.
     */
    private static String modelOf(HermesRunResult result, Agent agent) {
        String reported = result.model();
        if (reported == null || reported.isBlank() || reported.equals(agent.hermesProfile())) {
            return agent.model();
        }
        return reported;
    }
}
