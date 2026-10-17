package com.bifos.assistant.usage.application;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.hermes.application.port.HermesRunsClient;
import com.bifos.assistant.hermes.application.dto.HermesRunResult;
import com.bifos.assistant.hermes.application.dto.SessionRuntime;
import com.bifos.assistant.hermes.domain.TokenUsage;
import com.bifos.assistant.model.domain.ModelChoice;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 실행 종료 때 실제 모델과 사용량, 당시 가격의 비용을 같은 실행 줄에 저장한다.
 *
 * <p>종료 상태와 답을 함께 저장하며 시작과 실행 한도, 문맥 출처 기록은 실행 facade가 맡는다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ExecutionCompletionRecorder {
    private final Clock clock;
    private final AgentExecutionRepository executions;
    private final CostEstimator costs;
    private final HermesRunsClient hermes;


    /** 시스템 판단 실행의 실제 모델과 토큰을 남긴다. 실제 모델을 모르면 요청 모델로 대신하지 않는다. */
    public AgentExecution completeSystem(AgentExecution execution, HermesRunResult result, String apiBaseUrl) {
        return recordSystem(execution, result, apiBaseUrl, null);
    }


    /** 실패한 시스템 판단도 실제 모델과 사용량을 보존한다. 실패 응답 본문은 남기지 않는다. */
    public AgentExecution failSystem(
        AgentExecution execution, HermesRunResult result, String apiBaseUrl, String errorCode) {
        return recordSystem(execution, result, apiBaseUrl, errorCode);
    }


    private AgentExecution recordSystem(
        AgentExecution execution, HermesRunResult result, String apiBaseUrl, String errorCode) {
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
        SessionRuntime actual = hermes.readSessionRuntime(agent.apiBaseUrl(), agent.hermesProfile(), result.sessionId());
        if (actual == null) {
            log.info(
                "실제로 돈 모델을 읽지 못해 대화가 고른 값을 적는다 profile={} sessionId={}", agent.hermesProfile(), result.sessionId());
        }
        return actual;
    }


    private static String firstNonBlank(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }
}
