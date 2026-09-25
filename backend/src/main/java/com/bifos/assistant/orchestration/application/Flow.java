package com.bifos.assistant.orchestration.application;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.application.ChatEvent;
import com.bifos.assistant.chat.application.ChatTurn;
import com.bifos.assistant.chat.application.TurnIntent;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.usage.domain.AgentExecution;
import java.util.function.Consumer;

/**
 * 요청 하나를 여러 실행으로 나눠 돌리고 답 하나를 만든다.
 *
 * <p>지금 흐름은 {@link ResearchAndBuildFlow} 하나다. 범용 workflow 엔진이 아니라, 흐름 하나가
 * 끝까지 도는 것을 먼저 보고 무엇이 어려운지 안 뒤에 일반화하기 위해 둔 자리다.
 */
public interface Flow {

    /** {@code agent.flow} 에 적는 이름이다. */
    String name();

    /**
     * 요청 하나를 끝까지 돌린다.
     *
     * @param onRootStarted 뿌리 실행 줄을 만든 직후 받는다. 대화 turn 과 실행 번호를 연결한다
     * @param onEvent 단계가 시작하고 끝날 때마다 받는다. 한 번에 받는 경로에서는 아무것도 하지 않는다
     */
    ChatTurn run(
            CurrentUser user,
            Conversation conversation,
            Agent agent,
            String text,
            String input,
            TurnIntent intent,
            Consumer<AgentExecution> onRootStarted,
            Consumer<ChatEvent> onEvent);
}
