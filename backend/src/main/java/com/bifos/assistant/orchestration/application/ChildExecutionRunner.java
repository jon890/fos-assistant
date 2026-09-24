package com.bifos.assistant.orchestration.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.orchestration.domain.ChildResult;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.domain.AgentExecution;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
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
 *   <li>에이전트는 {@link AgentService#requireReadable} 를 지난 것만 쓴다
 *   <li>Memory 는 {@code ContextAssembler} 로 다시 조립한다. 부모 것을 복사하지 않는다
 *   <li>{@code parentExecutionId} 는 부모의 번호이고 {@code rootExecutionId} 는 부모의 뿌리다
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
     * <p>자식은 부모의 Hermes session 을 잇지 않는다. 중간 산출물이 대화 session 에 쌓이면 다음 turn
     * 이 그것을 함께 읽는다.
     *
     * @param user 부모 실행의 주인. 자식도 같은 사용자의 것이다
     * @param conversation 부모 실행이 속한 대화
     * @param parent 이 실행을 부른 실행
     * @param agentCode 자식이 쓸 에이전트. 요청자가 쓸 수 있는 것만 통과한다
     * @param task 이 자식에게만 주는 지시
     */
    public ChildResult run(
            CurrentUser user,
            Conversation conversation,
            AgentExecution parent,
            String agentCode,
            String task) {
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
        requireNotAChild(parent);
        Agent agent = agents.requireReadable(user, agentCode);
        return runner.run(
                        user,
                        conversation,
                        agent,
                        task,
                        parent.id(),
                        rootOf(parent),
                        null,
                        execution -> {},
                        onSubmitted,
                        cancelled)
                .result();
    }

    /**
     * 깊이를 1로 제한한다.
     *
     * <p>부모의 {@code rootExecutionId} 가 이미 채워져 있으면 그 부모가 자식이다. 자식이 다시 자식을
     * 부르지 않게 한다. 더 깊은 나무가 필요해지는 시점은 흐름 하나를 돌려 보고 정한다.
     */
    private static void requireNotAChild(AgentExecution parent) {
        if (parent.rootExecutionId() != null) {
            throw new ApiException(
                    ErrorCode.ORCHESTRATION_DEPTH_EXCEEDED, "a child cannot spawn another child");
        }
    }

    /**
     * 자식이 속할 나무의 뿌리를 정한다.
     *
     * <p>부모의 {@code rootExecutionId} 가 있으면 그것을 쓰고, 없으면 부모의 번호를 쓴다. 부모가
     * 뿌리이면 자기 {@code rootExecutionId} 는 비어 있기 때문이다.
     */
    private static Long rootOf(AgentExecution parent) {
        return parent.rootExecutionId() == null ? parent.id() : parent.rootExecutionId();
    }
}
