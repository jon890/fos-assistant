package com.bifos.assistant.orchestration.application;

import com.bifos.assistant.agent.application.AgentModelSelector;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.ModelOption;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.context.AssembledContext;
import com.bifos.assistant.context.ContextAssembler;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunCommand;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.orchestration.domain.ChildResult;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.application.ExecutionContextSnapshot;
import com.bifos.assistant.usage.application.ExecutionEventRecorder;
import com.bifos.assistant.usage.application.ExecutionRecorder;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionEvent;
import com.bifos.assistant.usage.domain.ExecutionEventType;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 에이전트 하나를 한 번 돌리고 실행 한 줄을 남긴다.
 *
 * <p>흐름의 첫 단계와 그 아래 단계가 같은 경로를 쓰게 하려고 여기 모았다. 부모와 뿌리를 정하는 것은
 * 부르는 쪽이고, 이 클래스는 받은 번호를 그대로 적는다. 요청자를 확인하고 에이전트를 고르는 것도
 * 부르는 쪽이 한다. 경계를 지키는 규칙은 {@link ChildExecutionRunner} 가 갖는다.
 *
 * <p>Memory 는 실행마다 {@link ContextAssembler} 로 다시 조립한다. 부모에게 넣은 문자열을 복사하면
 * 그 사이에 바뀐 권한이 반영되지 않는다.
 *
 * <p>실패를 예외로 올리지 않는다. 실행 줄을 FAILED 로 갱신하고 {@link ChildResult} 로 돌려준다.
 * 나란히 도는 다른 단계를 중간에 끊지 않기 위해서다.
 */
@Service
@RequiredArgsConstructor
public class AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);

    /** 실행이 어떤 이유로 끝났는지 알 수 없을 때 실행 줄에 적는 값이다. */
    private static final String UNKNOWN_ERROR = "ORCHESTRATION_STEP_FAILED";

    private final ContextAssembler contextAssembler;
    private final AgentModelSelector modelSelector;
    private final HermesRunsClient hermes;
    private final ExecutionRecorder executions;
    private final ExecutionEventRecorder eventRecorder;
    private final ExecutionEventRepository executionEvents;

    /**
     * 실행 하나를 끝까지 돌린다.
     *
     * @param user 이 실행의 주인. 부르는 쪽이 부모에게서 그대로 가져온다
     * @param conversation 이 실행이 속한 대화
     * @param agent 실행할 에이전트. 요청자가 쓸 수 있는 것만 여기 들어온다
     * @param task 이 실행에만 주는 지시
     * @param parentExecutionId 이 실행을 부른 실행. 뿌리이면 null
     * @param rootExecutionId 이 실행이 속한 나무의 뿌리. 뿌리 자신이면 null
     * @param sessionId 이어 갈 Hermes session. 새로 시작하면 null
     */
    public Run run(
            CurrentUser user,
            Conversation conversation,
            Agent agent,
            String task,
            Long parentExecutionId,
            Long rootExecutionId,
            String sessionId) {
        AssembledContext context = contextAssembler.assemble(user);
        ExecutionContextSnapshot snapshot =
                new ExecutionContextSnapshot(context.chars(), null, context.instructionsHash());
        // 자식은 자기 에이전트의 1순위를 쓴다. 부모의 것을 물려받지 않는다.
        List<ModelOption> available = modelSelector.availableFor(agent);
        if (available.isEmpty()) {
            AgentExecution empty = executions.start(
                    user, conversation, agent, parentExecutionId, rootExecutionId, snapshot, null, null);
            AgentExecution failed = executions.fail(empty, ErrorCode.NO_MODEL_AVAILABLE.name());
            append(failed, ExecutionEventType.RUN_FAILED, ErrorCode.NO_MODEL_AVAILABLE.name(), 1);
            return new Run(
                    failed, ChildResult.failed(failed.id(), ErrorCode.NO_MODEL_AVAILABLE.name()), null);
        }
        ModelOption option = available.getFirst();
        AgentExecution execution = executions.start(
                user, conversation, agent, parentExecutionId, rootExecutionId, snapshot, option, null);
        HermesRunCommand command = new HermesRunCommand(
                agent.hermesProfile(),
                agent.apiBaseUrl(),
                task,
                context.instructions(),
                sessionId,
                option.provider(),
                option.model());

        String runId;
        try {
            runId = hermes.submit(command);
            executions.attachRunId(execution, runId);
            append(execution, ExecutionEventType.RUN_STARTED, null, 1);
        } catch (RuntimeException ex) {
            return fail(execution, ex, 1);
        }

        HermesRunResult result;
        try {
            result = hermes.awaitCompletion(command, runId);
        } catch (RuntimeException ex) {
            return fail(execution, ex, 2);
        }

        if (!result.succeeded()) {
            String status = result.status() == null ? "UNKNOWN" : result.status().toUpperCase();
            AgentExecution failed = executions.fail(execution, status);
            append(failed, ExecutionEventType.RUN_FAILED, status, 2);
            return new Run(failed, ChildResult.failed(failed.id(), status), null);
        }

        AgentExecution completed = executions.complete(execution, agent, result, option);
        append(completed, ExecutionEventType.RUN_COMPLETED, null, 2);
        return new Run(completed, ChildResult.succeeded(completed.id(), result.output()), result.sessionId());
    }

    /**
     * 돌린 결과다.
     *
     * @param execution 남긴 실행 줄. 부르는 쪽이 이것을 다음 단계의 부모로 쓴다
     * @param result 성공 여부와 답
     * @param sessionId Hermes 가 알려 준 session. 대화를 이어 가려면 부르는 쪽이 기억한다
     */
    public record Run(AgentExecution execution, ChildResult result, String sessionId) {
    }

    private Run fail(AgentExecution execution, RuntimeException ex, int sequence) {
        String code = ex instanceof ApiException api ? api.code().name() : UNKNOWN_ERROR;
        AgentExecution failed = executions.fail(execution, code);
        append(failed, ExecutionEventType.RUN_FAILED, code, sequence);
        log.warn("흐름의 한 단계가 실패했다 executionId={} errorCode={}", failed.id(), code, ex);
        return new Run(failed, ChildResult.failed(failed.id(), code), null);
    }

    /**
     * 사건 하나를 저장한다.
     *
     * <p>저장이 실패해도 실행은 그대로 이어진다. 사건은 관측용이고 그것 때문에 답이 끊기면 안 된다.
     * {@code ChatService} 가 같은 이유로 같은 판단을 한다.
     */
    private void append(
            AgentExecution execution, ExecutionEventType type, String detail, int sequence) {
        try {
            ExecutionEvent event = eventRecorder.record(execution, type, detail, sequence);
            if (event != null) {
                executionEvents.save(event);
            }
        } catch (RuntimeException ex) {
            log.warn("실행 사건을 남기지 못했다 executionId={}", execution.id(), ex);
        }
    }
}
