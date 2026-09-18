package com.bifos.assistant.usage.application;

import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.ModelOption;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.SessionRuntime;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(ExecutionRecorder.class);

    private final AgentExecutionRepository executions;
    private final CostEstimator costs;
    private final HermesRunsClient hermes;

    /** 실행을 RUNNING 으로 만들어 돌려준다. 부모가 없으면 parent 와 root 는 null 이다. */
    public AgentExecution start(
            CurrentUser user,
            Conversation conversation,
            Agent agent,
            Long parentExecutionId,
            Long rootExecutionId,
            Long contextChars) {
        return start(
                user,
                conversation,
                agent,
                parentExecutionId,
                rootExecutionId,
                ExecutionContextSnapshot.ofChars(contextChars));
    }

    /** 실행 당시의 상태를 함께 적으며 RUNNING 으로 만들어 돌려준다. */
    public AgentExecution start(
            CurrentUser user,
            Conversation conversation,
            Agent agent,
            Long parentExecutionId,
            Long rootExecutionId,
            ExecutionContextSnapshot context) {
        return start(user, conversation, agent, parentExecutionId, rootExecutionId, context, null, null);
    }

    /**
     * 요청에 실을 모델과 다시 시도한 직전 실행까지 적으며 RUNNING 으로 만들어 돌려준다.
     *
     * <p>{@code requested} 를 시작할 때 적어 두면 실패로 끝난 실행도 어느 provider 로 시도한 것인지
     * 남는다. 성공하면 실제로 돈 값으로 덮인다.
     *
     * @param requested 이 실행에 실을 provider 와 모델. 고르지 못했으면 null
     * @param retryOfExecutionId 막혀서 넘어오며 대신하는 직전 실행. 첫 시도면 null
     */
    public AgentExecution start(
            CurrentUser user,
            Conversation conversation,
            Agent agent,
            Long parentExecutionId,
            Long rootExecutionId,
            ExecutionContextSnapshot context,
            ModelOption requested,
            Long retryOfExecutionId) {
        return executions.save(
                base(user, conversation, agent)
                        .parentExecutionId(parentExecutionId)
                        .rootExecutionId(rootExecutionId)
                        .retryOfExecutionId(retryOfExecutionId)
                        .provider(requested == null ? null : requested.provider())
                        .model(requested == null ? null : requested.model())
                        .contextChars(context.contextChars())
                        .contextOmittedItems(context.contextOmittedItems())
                        .runtimeFingerprint(context.runtimeFingerprint())
                        .instructionsHash(context.instructionsHash())
                        .status(ExecutionStatus.RUNNING)
                        .build());
    }

    /** 제출 직후 run 번호를 붙인다. */
    public void attachRunId(AgentExecution execution, String hermesRunId) {
        execution.attachRunId(hermesRunId);
        executions.save(execution);
    }

    /**
     * 끝난 실행을 SUCCEEDED 로 갱신한다.
     *
     * <p>기록할 모델은 {@code GET /api/sessions/{session_id}} 가 정한다. 실행 조회의 {@code model} 은
     * 우리가 보낸 값을 되돌려 줄 뿐이라, 넘김이 일어난 실행에서는 실제와 어긋난다. 세션 조회가 실패해도
     * 실행은 성공으로 남기고 요청에 보낸 값을 적는다. 모델 이름을 모르는 것이 답을 버릴 이유가 되지
     * 않는다.
     *
     * @param requested 이 실행에 실어 보낸 provider 와 모델
     */
    public AgentExecution complete(
            AgentExecution execution, Agent agent, HermesRunResult result, ModelOption requested) {
        TokenUsage usage = result.usage() == null ? TokenUsage.empty() : result.usage();
        SessionRuntime actual = readActualRuntime(agent, result);
        String provider = firstNonBlank(
                actual == null ? null : actual.provider(),
                requested == null ? null : requested.provider());
        String model = firstNonBlank(
                actual == null ? null : actual.model(),
                requested == null ? null : requested.model());
        execution.attachRunId(result.runId());
        execution.markSucceeded(
                provider, model, usage, costs.estimate(provider, model, usage, agent.costMode()), Instant.now());
        return executions.save(execution);
    }

    /**
     * 이 번호들 중 자식을 가진 것만 낸다.
     *
     * <p>대화 이력이 어느 답에 실행 나무로 가는 길을 붙일지 정하는 데 쓴다. 실행마다 세지 않고 한 번에
     * 읽는다.
     */
    public List<Long> idsHavingChildren(Collection<Long> executionIds) {
        return executions.findParentIdsHavingChildren(executionIds);
    }

    /** 끝난 실행을 FAILED 로 갱신한다. */
    public AgentExecution fail(AgentExecution execution, String errorCode) {
        execution.markFailed(errorCode, Instant.now());
        return executions.save(execution);
    }

    private SessionRuntime readActualRuntime(Agent agent, HermesRunResult result) {
        SessionRuntime actual =
                hermes.readSessionRuntime(agent.apiBaseUrl(), agent.hermesProfile(), result.sessionId());
        if (actual == null) {
            log.info(
                    "실제로 돈 모델을 읽지 못해 요청에 보낸 값을 적는다 profile={} sessionId={}",
                    agent.hermesProfile(),
                    result.sessionId());
        }
        return actual;
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
}
