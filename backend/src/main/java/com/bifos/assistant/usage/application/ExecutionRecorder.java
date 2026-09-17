package com.bifos.assistant.usage.application;

import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.usage.domain.AgentExecution;
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

    /** 실행을 RUNNING 으로 만들어 돌려준다. 부모가 없으면 parent 와 root 는 null 이다. */
    public AgentExecution start(
            CurrentUser user,
            Conversation conversation,
            Agent agent,
            Long parentExecutionId,
            Long rootExecutionId,
            Long contextChars) {
        return executions.save(
                base(user, conversation, agent)
                        .parentExecutionId(parentExecutionId)
                        .rootExecutionId(rootExecutionId)
                        .contextChars(contextChars)
                        .status(ExecutionStatus.RUNNING)
                        .build());
    }

    /** 제출 직후 run 번호를 붙인다. */
    public void attachRunId(AgentExecution execution, String hermesRunId) {
        execution.attachRunId(hermesRunId);
        executions.save(execution);
    }

    /** 끝난 실행을 SUCCEEDED 로 갱신한다. */
    public AgentExecution complete(AgentExecution execution, Agent agent, HermesRunResult result) {
        TokenUsage usage = result.usage() == null ? TokenUsage.empty() : result.usage();
        String provider = firstNonBlank(result.provider(), agent.provider());
        String model = modelOf(result, agent);
        execution.attachRunId(result.runId());
        execution.markSucceeded(
                provider, model, usage, costs.estimate(provider, model, usage, agent.costMode()), Instant.now());
        return executions.save(execution);
    }

    /** 끝난 실행을 FAILED 로 갱신한다. */
    public AgentExecution fail(AgentExecution execution, String errorCode) {
        execution.markFailed(errorCode, Instant.now());
        return executions.save(execution);
    }

    private AgentExecution.Builder base(CurrentUser user, Conversation conversation, Agent agent) {
        return AgentExecution.builder()
                .userId(user.id())
                .conversationId(conversation.id())
                .agentId(agent.id())
                .profileName(agent.hermesProfile())
                .costMode(agent.costMode())
                .startedAt(Instant.now());
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    /**
     * 기록할 모델을 고른다.
     *
     * <p>실행의 {@code model}은 API server가 보고한 모델 이름이며, 기본값은 profile 이름이다.
     * 실제 Hermes 실행에서 profile {@code bifos}는 {@code "model": "bifos"}를 보고하므로,
     * 이를 그대로 쓰면 사용한 모델이 아니라 구성원 이름으로 실행을 표시하게 된다.
     * 실행이 profile만 되풀이하면 바인딩에 설정한 모델이 정확한 값이다.
     */
    private static String modelOf(HermesRunResult result, Agent agent) {
        String reported = result.model();
        if (reported == null || reported.isBlank() || reported.equals(agent.hermesProfile())) {
            return agent.model();
        }
        return reported;
    }
}
