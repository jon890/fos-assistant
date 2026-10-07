package com.bifos.assistant.usage.application;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.SessionRuntime;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.model.domain.ModelChoice;
import com.bifos.assistant.model.domain.type.ModelTier;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.usage.application.model.ExecutionAdmission;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.DelegationKey;
import com.bifos.assistant.usage.domain.ExecutionConversation;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.domain.type.ReasoningEffortSource;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 에이전트 turn 하나를 한 줄로 남긴다. 그래야 사용량을 사용자, 모델, provider 로 되짚을 수 있다.
 *
 * <p>비용은 실행이 끝나는 이 자리에서 환산하고 쓴 가격표와 함께 저장한다. 조회할 때 다시 계산하면
 * 가격이 바뀔 때 지난달 합계가 따라 움직인다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ExecutionRecorder {
    private final Clock clock;

    private final AgentExecutionRepository executions;
    private final CostEstimator costs;
    private final HermesRunsClient hermes;
    private final UserExecutionLimiter limiter;
    private final ExecutionContextSourceWriter contextSources;

    /**
     * 실행을 RUNNING 으로 만들어 돌려준다. 부모가 없으면 parent 와 root 는 null 이다.
     *
     * <p>사용자 실행 한도에 닿으면 {@code USER_BUSY} 를 던지고 줄을 만들지 않는다. 트랜잭션 밖에서 부른다.
     */
    public AgentExecution start(
            CurrentUser user,
            ExecutionConversation conversation,
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

    /**
     * 실행 당시의 상태를 함께 적으며 RUNNING 으로 만들어 돌려준다.
     *
     * <p>사용자 실행 한도에 닿으면 {@code USER_BUSY} 를 던지고 줄을 만들지 않는다. 트랜잭션 밖에서 부른다.
     */
    public AgentExecution start(
            CurrentUser user,
            ExecutionConversation conversation,
            Agent agent,
            Long parentExecutionId,
            Long rootExecutionId,
            ExecutionContextSnapshot context) {
        return start(user, conversation, agent, parentExecutionId, rootExecutionId, context, null, null, null);
    }

    /**
     * 대화가 고른 모델과 effort 까지 적으며 RUNNING 으로 만들어 돌려준다.
     *
     * <p>{@code requested} 를 시작할 때 적어 두면 실패로 끝난 실행도 어느 provider 로 시도한 것인지
     * 남는다. 끝나면 세션에서 읽은 실제 값으로 덮인다. 기본값으로 보냈으면 provider 와 모델을 비워 두고
     * effort 만 적는다.
     *
     * <p>사용자 실행 한도에 닿으면 {@code USER_BUSY} 를 던지고 줄을 만들지 않는다. 트랜잭션 밖에서 부른다.
     *
     * @param requested 대화가 고른 provider, 모델, effort. null 이면 기본값으로 본다
     * @param retryOfExecutionId 지금은 늘 null 이다. provider 가 막히면 다른 모델로 넘기던 때 채우던 칸이고,
     *     넘김이 없어진 뒤로는 채우지 않는다. 그 전에 남은 실행 기록을 읽으려고 칸과 인자를 남겨 둔다
     * @param hermesSessionId 이 실행 줄에 적을 Hermes session. 제출하기 전에 적힌다. 대화 turn 은 그 대화의
     *     루트 session 이라 Hermes 에 보내는 값과 다를 수 있다(ADR-031). 없으면 null 이다
     */
    public AgentExecution start(
            CurrentUser user,
            ExecutionConversation conversation,
            Agent agent,
            Long parentExecutionId,
            Long rootExecutionId,
            ExecutionContextSnapshot context,
            ModelChoice requested,
            Long retryOfExecutionId,
            String hermesSessionId) {
        return start(
                user,
                conversation,
                agent,
                parentExecutionId,
                rootExecutionId,
                context,
                requested,
                retryOfExecutionId,
                hermesSessionId,
                null);
    }

    /**
     * 다른 에이전트에게 맡긴 실행을 {@code delegation_key} 와 함께 RUNNING 으로 만들어 돌려준다.
     *
     * <p>키를 처음 만들 때 함께 적어야 유일 제약이 같은 호출의 두 번째 줄을 막는다. 뒤에 붙이면 두 요청이 모두
     * 줄을 만든 뒤에야 걸린다. 같은 키의 줄이 이미 있으면 저장이 {@code DataIntegrityViolationException} 으로
     * 실패하고, 부르는 쪽이 그 키로 먼저 저장된 줄을 다시 읽는다.
     *
     * <p>사용자 실행 한도에 닿으면 {@code USER_BUSY} 를 던지고 줄을 만들지 않는다. 트랜잭션 밖에서 부른다.
     *
     * @param delegationKey 위임이 아니면 null 이다
     */
    public AgentExecution start(
            CurrentUser user,
            ExecutionConversation conversation,
            Agent agent,
            Long parentExecutionId,
            Long rootExecutionId,
            ExecutionContextSnapshot context,
            ModelChoice requested,
            Long retryOfExecutionId,
            String hermesSessionId,
            DelegationKey delegationKey) {
        return start(
                user,
                conversation,
                agent,
                parentExecutionId,
                rootExecutionId,
                context,
                requested,
                retryOfExecutionId,
                hermesSessionId,
                delegationKey,
                null,
                null);
    }

    /**
     * 보낸 effort 를 누가 정했는지다.
     *
     * <p>단계도 대화도 effort 를 정하지 않았는데 보낸 값이 있으면 에이전트 기본값에서 온 것이다(ADR-054).
     * 단계를 거친 실행은 그 단계의 mapping 이 비어 에이전트 기본값으로 돌았어도 {@code REQUESTED} 로 적는다.
     */
    private static ReasoningEffortSource effortSource(
            ExecutionConversation conversation, ModelChoice requested, ModelTier modelTier) {
        if (requested == null || requested.reasoningEffort() == null) {
            return ReasoningEffortSource.UNKNOWN;
        }
        boolean chosenByConversation = conversation != null && conversation.reasoningEffort() != null;
        return modelTier == null && !chosenByConversation
                ? ReasoningEffortSource.AGENT_DEFAULT
                : ReasoningEffortSource.REQUESTED;
    }

    /**
     * 요청을 받은 시각과 대화가 고른 단계를 실행 줄에 복사한다.
     *
     * <p>부모가 있으면 흐름 단계나 위임 자식으로, 대화만 있으면 대화 turn 의 루트로, 둘 다 없으면 백그라운드 실행으로
     * 사용자 실행 한도를 본다(ADR-069). 사용자 실행 한도에 닿으면 {@code USER_BUSY} 를 던지고 줄을 만들지 않는다. 트랜잭션 밖에서 부른다.
     */
    public AgentExecution start(
            CurrentUser user,
            ExecutionConversation conversation,
            Agent agent,
            Long parentExecutionId,
            Long rootExecutionId,
            ExecutionContextSnapshot context,
            ModelChoice requested,
            Long retryOfExecutionId,
            String hermesSessionId,
            DelegationKey delegationKey,
            ModelTier modelTier,
            Instant requestReceivedAt) {
        return record(
                user,
                conversation,
                agent,
                parentExecutionId,
                rootExecutionId,
                context,
                requested,
                retryOfExecutionId,
                hermesSessionId,
                delegationKey,
                modelTier,
                requestReceivedAt,
                effortSource(conversation, requested, modelTier),
                admissionOf(conversation, parentExecutionId, rootExecutionId));
    }

    /**
     * 원래 실행에서 이어지는 실행을 RUNNING 으로 만들어 돌려준다. Memory 제안처럼 같은 대화에서 원래 실행의
     * 값을 이어받아 도는 실행이 쓴다.
     *
     * <p>단계는 원래 실행 줄에서 복사하고, effort 출처도 원래 실행 줄에서 읽는다. 대화에서 다시 계산하면 그
     * 사이 바뀐 대화의 선택이 섞인다. 보낸 effort 가 없으면 출처는 {@code UNKNOWN} 이다. 그래야 완료 뒤
     * 보완이 그 줄을 찾는다.
     *
     * <p>사용자 실행 한도는 백그라운드 실행으로 본다. 사용자 실행 한도에 닿으면 {@code USER_BUSY} 를 던지고 줄을 만들지 않는다. 트랜잭션 밖에서 부른다.
     *
     * @param parent 원래 실행. 부모와 루트 실행 번호가 모두 이 실행이다
     * @param requested 원래 실행이 Hermes 에 보낸 provider, 모델, effort. null 이면 기본값으로 본다
     */
    public AgentExecution startInheriting(
            CurrentUser user,
            ExecutionConversation conversation,
            Agent agent,
            AgentExecution parent,
            ModelChoice requested) {
        ModelChoice sent = requested == null ? ModelChoice.defaults() : requested;
        ReasoningEffortSource source;
        if (sent.reasoningEffort() == null) {
            source = ReasoningEffortSource.UNKNOWN;
        } else if (parent.reasoningEffortSource() == ReasoningEffortSource.AGENT_DEFAULT) {
            source = ReasoningEffortSource.AGENT_DEFAULT;
        } else {
            source = ReasoningEffortSource.REQUESTED;
        }
        return record(
                user,
                conversation,
                agent,
                parent.id(),
                parent.id(),
                ExecutionContextSnapshot.ofChars(0L),
                sent,
                null,
                null,
                null,
                parent.modelTier(),
                null,
                source,
                ExecutionAdmission.BACKGROUND);
    }

    /** 부모가 있으면 자식, 대화만 있으면 대화 turn 의 루트, 둘 다 없으면 백그라운드 실행이다. */
    private ExecutionAdmission admissionOf(
            ExecutionConversation conversation, Long parentExecutionId, Long rootExecutionId) {
        if (parentExecutionId != null) {
            Long rootId = rootExecutionId == null ? parentExecutionId : rootExecutionId;
            Long rootConversationId = executions
                    .findById(rootId)
                    .map(AgentExecution::conversationId)
                    .orElse(null);
            if (limiter.isBackgroundConversation(rootConversationId)) {
                return ExecutionAdmission.BACKGROUND_CHILD;
            }
            return ExecutionAdmission.CHILD;
        }
        return conversation != null ? ExecutionAdmission.TURN_ROOT : ExecutionAdmission.BACKGROUND;
    }

    private AgentExecution record(
            CurrentUser user,
            ExecutionConversation conversation,
            Agent agent,
            Long parentExecutionId,
            Long rootExecutionId,
            ExecutionContextSnapshot context,
            ModelChoice requested,
            Long retryOfExecutionId,
            String hermesSessionId,
            DelegationKey delegationKey,
            ModelTier modelTier,
            Instant requestReceivedAt,
            ReasoningEffortSource effortSource,
            ExecutionAdmission admission) {
        AgentExecution execution = limiter.admit(
                user.id(),
                admission,
                () -> executions.save(base(user, conversation, agent)
                        .hermesSessionId(hermesSessionId)
                        .delegationKey(delegationKey == null ? null : delegationKey.value())
                        .parentExecutionId(parentExecutionId)
                        .rootExecutionId(rootExecutionId)
                        .retryOfExecutionId(retryOfExecutionId)
                        .provider(requested == null ? null : requested.provider())
                        .model(requested == null ? null : requested.model())
                        .reasoningEffort(requested == null ? null : requested.reasoningEffort())
                        .reasoningEffortSource(effortSource)
                        .modelTier(modelTier)
                        .requestReceivedAt(requestReceivedAt)
                        .contextChars(context.contextChars())
                        .contextOmittedItems(context.contextOmittedItems())
                        .runtimeFingerprint(context.runtimeFingerprint())
                        .instructionsHash(context.instructionsHash())
                        .status(ExecutionStatus.RUNNING)
                        .build()));
        contextSources.write(execution.id(), context.sources());
        return execution;
    }

    /**
     * 속한 대화 없이 도는 실행을 RUNNING 으로 만들어 돌려준다.
     *
     * <p>추천 질문을 만드는 실행처럼 turn 이 아닌 실행이 쓴다. 대화, 부모, 루트, session 을 비우고 문맥 글자
     * 수는 0 으로 적는다. 보낸 모델 선택은 {@code requested} 로 적고, effort 가 있으면 출처는 에이전트
     * 기본값이다.
     *
     * <p>사용자 실행 한도는 백그라운드 실행으로 본다. 사용자 실행 한도에 닿으면 {@code USER_BUSY} 를 던지고 줄을 만들지 않는다. 트랜잭션 밖에서 부른다.
     *
     * @param requested Hermes 에 보낸 provider, 모델, effort
     */
    public AgentExecution startDetached(CurrentUser user, Agent agent, ModelChoice requested) {
        return start(user, null, agent, null, null, ExecutionContextSnapshot.ofChars(0L), requested, null, null);
    }

    /** 설치 설정의 시스템 profile 로 판단만 한다. 사용자 에이전트 줄을 만들지 않으며 요청자의 백그라운드 한도로 센다. */
    public AgentExecution startSystem(CurrentUser user, String profileName, CostMode costMode, ModelChoice requested) {
        return limiter.admit(
                user.id(),
                ExecutionAdmission.BACKGROUND,
                () -> executions.save(AgentExecution.builder()
                        .userId(user.id())
                        .profileName(profileName)
                        .costMode(costMode)
                        .provider(requested.provider())
                        .model(requested.model())
                        .reasoningEffort(requested.reasoningEffort())
                        .reasoningEffortSource(
                                requested.reasoningEffort() == null
                                        ? ReasoningEffortSource.UNKNOWN
                                        : ReasoningEffortSource.REQUESTED)
                        .contextChars(0L)
                        .status(ExecutionStatus.RUNNING)
                        .startedAt(clock.instant())
                        .build()));
    }

    /** 시스템 판단 실행의 실제 모델과 토큰을 남긴다. 실제 모델을 모르면 요청 모델로 대신하지 않는다. */
    public AgentExecution completeSystem(AgentExecution execution, HermesRunResult result, String apiBaseUrl) {
        return recordSystem(execution, result, apiBaseUrl, null);
    }

    /** 실패한 시스템 판단도 실제 모델과 사용량을 보존한다. 실패 응답 본문은 남기지 않는다. */
    public AgentExecution failSystem(AgentExecution execution, HermesRunResult result, String apiBaseUrl, String errorCode) {
        return recordSystem(execution, result, apiBaseUrl, errorCode);
    }

    private AgentExecution recordSystem(AgentExecution execution, HermesRunResult result, String apiBaseUrl, String errorCode) {
        SessionRuntime actual = result.runtime();
        if (actual == null || isBlank(actual.provider()) || isBlank(actual.model())) {
            actual = hermes.readSessionRuntime(apiBaseUrl, execution.profileName(), result.sessionId());
        }
        String provider = actual == null ? null : actual.provider();
        String model = actual == null ? null : actual.model();
        TokenUsage usage = result.usage() == null ? TokenUsage.empty() : result.usage();
        execution.attachRunId(result.runId());
        var cost = costs.estimate(provider, model, usage, execution.costMode());
        if (errorCode == null) {
            execution.markSucceeded(provider, model, usage, cost, clock.instant());
        } else {
            execution.markFailed(provider, model, usage, cost, errorCode, clock.instant());
        }
        return executions.save(execution);
    }

    /** 제출 직후 run 번호를 붙인다. */
    public void attachRunId(AgentExecution execution, String hermesRunId) {
        execution.attachRunId(hermesRunId);
        executions.save(execution);
    }

    /** Hermes 제출 직전 시각을 실행 줄에 남긴다. */
    public void markSubmitted(AgentExecution execution) {
        execution.markSubmitted(clock.instant());
        executions.save(execution);
    }

    /** Flow 루트에는 실행기 진입보다 앞선 원래 요청 수신 시각을 남긴다. */
    public void markRequestReceived(AgentExecution execution, Instant at) {
        execution.markRequestReceived(at);
        executions.save(execution);
    }

    /** 최초 assistant delta 수신 시각만 남긴다. */
    public void markFirstDelta(AgentExecution execution) {
        if (execution.markFirstDelta(clock.instant())) {
            executions.save(execution);
        }
    }

    /**
     * 끝난 실행을 SUCCEEDED 로 갱신한다.
     *
     * <p>기록할 provider 와 모델은 {@link #served} 가 고른다. v0.21.5 의 실행 {@code runtime} 이 짝을 주면 그것을,
     * 아니면 세션 조회와 대화가 고른 값으로 채운다. 모두 읽지 못해도 실행은 성공으로 남긴다. 모델 이름을 모르는
     * 것이 답을 버릴 이유가 되지 않는다.
     *
     * @param requested 대화가 고른 provider, 모델, effort. null 이면 기본값으로 본다
     */
    public AgentExecution complete(
            AgentExecution execution, Agent agent, HermesRunResult result, ModelChoice requested) {
        return complete(execution, agent, result, requested, null);
    }

    /**
     * 끝난 실행을 SUCCEEDED 로 갱신하며 답을 같은 저장에서 적는다.
     *
     * <p>다른 에이전트에게 맡긴 실행이 쓴다. 답을 따로 저장하면 그 사이 {@code agent_status} 가 답 없는 SUCCEEDED 를
     * 읽는다. 길이를 자르는 것은 부르는 쪽이 한다.
     *
     * @param outputText 실행 줄의 {@code output_text} 에 적을 답. null 이면 적지 않는다
     */
    public AgentExecution complete(
            AgentExecution execution, Agent agent, HermesRunResult result, ModelChoice requested, String outputText) {
        TokenUsage usage = result.usage() == null ? TokenUsage.empty() : result.usage();
        SessionRuntime served = served(agent, result, requested);
        String provider = served.provider();
        String model = served.model();
        execution.attachRunId(result.runId());
        execution.markSucceeded(
                provider, model, usage, costs.estimate(provider, model, usage, agent.costMode()), clock.instant());
        if (outputText != null) {
            execution.recordOutput(outputText);
        }
        return executions.save(execution);
    }

    /**
     * 이 번호들 중 자식을 가진 것만 낸다.
     *
     * <p>대화 이력이 어느 답에 실행 트리로 가는 길을 붙일지 정하는 데 쓴다. 실행마다 세지 않고 한 번에
     * 읽는다.
     */
    public List<Long> idsHavingChildren(Collection<Long> executionIds) {
        return executions.findParentIdsHavingChildren(executionIds);
    }

    /** 최종 결과를 받지 못한 실행을 FAILED 로 갱신한다. 이미 적힌 사용량은 보존한다. */
    public AgentExecution fail(AgentExecution execution, String errorCode) {
        execution.markFailed(errorCode, clock.instant());
        return executions.save(execution);
    }

    /** 최종 실패 결과의 사용량과 실제 모델을 보존하며 FAILED 와 오류 코드를 함께 남긴다. */
    public AgentExecution fail(
            AgentExecution execution, Agent agent, HermesRunResult result, ModelChoice requested, String errorCode) {
        if (result == null) {
            return fail(execution, errorCode);
        }
        TokenUsage usage = result.usage() == null ? TokenUsage.empty() : result.usage();
        SessionRuntime served = served(agent, result, requested);
        String provider = served.provider();
        String model = served.model();
        execution.attachRunId(result.runId());
        execution.markFailed(
                provider,
                model,
                usage,
                costs.estimate(provider, model, usage, agent.costMode()),
                errorCode,
                clock.instant());
        return executions.save(execution);
    }

    /** Hermes 가 돌려준 사용량을 보존하며 실행을 취소로 남긴다. */
    public AgentExecution cancel(AgentExecution execution, Agent agent, HermesRunResult result, ModelChoice requested) {
        return cancel(execution, agent, result, requested, null);
    }

    /**
     * Hermes 가 돌려준 사용량을 보존하며 실행을 취소로 남기고, 멈춘 자리까지의 답을 같은 저장에서 적는다.
     *
     * <p>다른 에이전트에게 맡긴 실행이 쓴다. 답을 따로 저장하면 그 사이 {@code agent_status} 가 답 없는 CANCELLED 를
     * 읽는다. 길이를 자르는 것은 부르는 쪽이 한다.
     *
     * @param outputText 실행 줄의 {@code output_text} 에 적을 답. null 이면 적지 않는다
     */
    public AgentExecution cancel(
            AgentExecution execution, Agent agent, HermesRunResult result, ModelChoice requested, String outputText) {
        if (result == null) {
            return cancel(execution);
        }
        TokenUsage usage = result.usage() == null ? TokenUsage.empty() : result.usage();
        SessionRuntime served = served(agent, result, requested);
        String provider = served.provider();
        String model = served.model();
        execution.attachRunId(result.runId());
        execution.markCancelled(
                provider, model, usage, costs.estimate(provider, model, usage, agent.costMode()), clock.instant());
        if (outputText != null) {
            execution.recordOutput(outputText);
        }
        return executions.save(execution);
    }

    /** 이미 적은 토큰을 보존하며 실행을 취소로 남긴다. */
    public AgentExecution cancel(AgentExecution execution) {
        execution.markCancelled(clock.instant());
        return executions.save(execution);
    }

    /**
     * 기록할 provider 와 모델의 짝을 고른다.
     *
     * <p>v0.21.5 실행 조회의 {@code runtime} 에 둘 다 있으면 그 짝을 통째로 쓴다. 출처가 다른 두 값을 한 짝으로
     * 적으면 금액도 틀린 짝으로 계산된다. 없으면 세션 조회, {@code runtime}, 대화가 고른 값 순서로 칸마다 채운다.
     */
    private SessionRuntime served(Agent agent, HermesRunResult result, ModelChoice requested) {
        SessionRuntime runtime = result.runtime();
        if (runtime != null && !isBlank(runtime.provider()) && !isBlank(runtime.model())) {
            return runtime;
        }
        SessionRuntime actual = readActualRuntime(agent, result);
        String provider = firstNonBlank(
                actual == null ? null : actual.provider(),
                firstNonBlank(
                        runtime == null ? null : runtime.provider(), requested == null ? null : requested.provider()));
        String model = firstNonBlank(
                actual == null ? null : actual.model(),
                firstNonBlank(runtime == null ? null : runtime.model(), requested == null ? null : requested.model()));
        return new SessionRuntime(model, provider);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private SessionRuntime readActualRuntime(Agent agent, HermesRunResult result) {
        SessionRuntime actual =
                hermes.readSessionRuntime(agent.apiBaseUrl(), agent.hermesProfile(), result.sessionId());
        if (actual == null) {
            log.info(
                    "실제로 돈 모델을 읽지 못해 대화가 고른 값을 적는다 profile={} sessionId={}", agent.hermesProfile(), result.sessionId());
        }
        return actual;
    }

    private AgentExecution.Builder base(CurrentUser user, ExecutionConversation conversation, Agent agent) {
        return AgentExecution.builder()
                .userId(user.id())
                .conversationId(conversation == null ? null : conversation.id())
                .agentId(agent.id())
                .profileName(agent.hermesProfile())
                .costMode(agent.costMode())
                .startedAt(clock.instant());
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }
}
