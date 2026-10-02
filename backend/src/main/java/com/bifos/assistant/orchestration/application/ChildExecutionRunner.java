package com.bifos.assistant.orchestration.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.orchestration.domain.ChildResult;
import com.bifos.assistant.chat.domain.RunSession;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.DelegationKey;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 부모 실행 아래에서 다른 에이전트를 한 번 돌린다.
 *
 * <p>자식도 부모와 같은 사용자의 것이다. 요청 본문이나 모델의 출력이 그것을 바꾸지 못한다.
 *
 * <p>경계를 지키는 규칙이 넷이다.
 *
 * <ul>
 *   <li>사용자는 부모의 것이다. 그것을 바꾸는 인자가 없다
 *   <li>에이전트는 {@link AgentService#requireReadable} 를 지난 것만 쓴다. 위임은 켜졌는지까지 보는
 *       {@link AgentService#requireStartable} 을 쓴다
 *   <li>Memory 는 {@code ContextAssembler} 로 다시 조립한다. 부모 것을 복사하지 않는다
 *   <li>{@code parentExecutionId} 는 부모의 번호이고 {@code rootExecutionId} 는 부모의 루트다
 * </ul>
 *
 * <p>자식의 답을 {@code chat_message} 에 넣지 않는다. 사용자가 읽을 답은 마지막에 합친 하나이고,
 * 중간 산출물을 이력에 넣으면 대화 화면이 그것으로 찬다. 자식의 답은 {@link ChildResult} 로 부르는
 * 쪽에 돌아가고, 그 실행 기록과 {@code execution_event} 로 남는다.
 */
@Service
@RequiredArgsConstructor
public class ChildExecutionRunner {

    private final AgentService agents;
    private final AgentRunner runner;

    /**
     * 자식 실행 하나를 끝까지 돌린다.
     *
     * <p>자식은 부모의 Hermes session 을 잇지 않고 새 {@code fos-<uuid>} 를 정해 보내고 실행 줄에 적는다.
     * 중간 산출물이 대화 session 에 쌓이면 다음 turn 이 그것을 함께 읽는다.
     *
     * @param user 부모 실행의 주인. 자식도 같은 사용자의 것이다
     * @param conversation 부모 실행이 속한 대화
     * @param parent 이 실행을 부른 실행
     * @param agentCode 자식이 쓸 에이전트. 요청자가 쓸 수 있는 것만 통과한다
     * @param task 이 자식에게만 주는 지시
     */
    public ChildResult run(
            CurrentUser user, Conversation conversation, AgentExecution parent, String agentCode, String task) {
        return run(user, conversation, parent, agentCode, task, (execution, runId) -> {}, () -> false);
    }

    /** Hermes run 번호를 붙인 직후 부모 흐름에 알린다. */
    public ChildResult run(
            CurrentUser user,
            Conversation conversation,
            AgentExecution parent,
            String agentCode,
            String task,
            BiConsumer<AgentExecution, String> onSubmitted,
            BooleanSupplier cancelled) {
        return run(user, conversation, parent, agentCode, task, onSubmitted, cancelled, null);
    }

    /** turn 에만 적용할 지시는 자식의 문맥 뒤에도 같은 문구로 붙인다. */
    public ChildResult run(
            CurrentUser user,
            Conversation conversation,
            AgentExecution parent,
            String agentCode,
            String task,
            BiConsumer<AgentExecution, String> onSubmitted,
            BooleanSupplier cancelled,
            String instructionAddition) {
        requireNotAChild(parent);
        Agent agent = agents.requireReadable(user, agentCode);
        return runner.run(
                        user,
                        conversation,
                        agent,
                        task,
                        parent.id(),
                        parent.treeRootId(),
                        RunSession.fresh(),
                        execution -> {},
                        onSubmitted,
                        cancelled,
                        instructionAddition)
                .result();
    }

    /**
     * 위임할 에이전트를 확인한다. MCP {@code agent_delegate} 의 요청 스레드에서 쓴다.
     *
     * <p>한도 검사보다 먼저, 기다리지 않고 거절하려고 실행 시작과 나눴다.
     *
     * @throws ApiException 없거나 요청자가 쓸 수 없으면 {@link ErrorCode#AGENT_NOT_FOUND}, 꺼졌으면 {@link ErrorCode#AGENT_DISABLED}
     */
    public Agent startableAgent(CurrentUser user, String agentCode) {
        return agents.requireStartable(user, agentCode);
    }

    /**
     * 다른 에이전트에게 맡긴 실행 하나를 끝까지 돌린다. MCP {@code agent_delegate} 가 가상 스레드에서 쓴다.
     *
     * <p>흐름용 {@link #run} 과 달리 깊이 1 로 막지 않는다. 위임의 깊이는 부르는 쪽이 설정값으로 검사한다.
     * session 은 여기서 새 {@code fos-<uuid>} 로 정하고 인자로 받지 않는다. 부모의 session 을 쓰면 자식의 대화가
     * 부모 session 에 쌓인다. 자식의 답은 {@code chat_message} 가 아니라 실행 줄의 {@code output_text} 에 적힌다.
     *
     * @param user 부모 실행의 주인. 자식도 같은 사용자의 것이다
     * @param conversation 부모 실행이 속한 대화
     * @param parent 이 위임을 부른 실행. 자식의 부모이고, 루트는 그 실행의 루트다
     * @param agent {@link #startableAgent} 를 지난 에이전트
     * @param task 이 자식에게만 주는 지시
     * @param delegationKey 실행 줄을 만들 때 함께 적는 키
     * @param onStarted 실행 줄이 생긴 직후 호출한다
     * @param onSubmitted 실행 줄과 Hermes run 번호가 모두 생긴 직후 호출한다
     * @param cancelled 참이면 실행을 멈춘다. 실행 줄이 생긴 직후에 참이면 제출하지 않고 CANCELLED 로 끝낸다
     */
    public ChildResult delegate(
            CurrentUser user,
            Conversation conversation,
            AgentExecution parent,
            Agent agent,
            String task,
            DelegationKey delegationKey,
            Consumer<AgentExecution> onStarted,
            BiConsumer<AgentExecution, String> onSubmitted,
            BooleanSupplier cancelled) {
        return runner.run(
                        user,
                        conversation,
                        agent,
                        task,
                        parent.id(),
                        parent.treeRootId(),
                        RunSession.fresh(),
                        onStarted,
                        onSubmitted,
                        cancelled,
                        null,
                        delegationKey)
                .result();
    }

    /**
     * 깊이를 1로 제한한다.
     *
     * <p>부모의 {@code rootExecutionId} 가 이미 채워져 있으면 그 부모가 자식이다. 자식이 다시 자식을
     * 부르지 않게 한다. 더 깊은 트리가 필요해지는 시점은 흐름 하나를 돌려 보고 정한다.
     */
    private static void requireNotAChild(AgentExecution parent) {
        if (parent.rootExecutionId() != null) {
            throw new ApiException(ErrorCode.ORCHESTRATION_DEPTH_EXCEEDED, "a child cannot spawn another child");
        }
    }
}
