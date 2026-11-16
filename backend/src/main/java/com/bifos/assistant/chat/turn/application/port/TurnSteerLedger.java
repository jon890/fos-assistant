package com.bifos.assistant.chat.turn.application.port;

import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.turn.application.dto.SteerSettlement;
import java.util.List;

/**
 * turn 을 돌리는 쪽이 steer 말풍선을 정리하고 다음 turn 으로 넘길 때 부르는 자리다(ADR-20261116 / run-steer).
 *
 * <p>구현은 {@code chat.nextturn} 이 갖는다. 하위 기능 순서({@code turn} 이 {@code nextturn} 을 부르지 않는다)를 지키려고 포트로
 * 둔다.
 */
public interface TurnSteerLedger {

    /**
     * 끝난 turn 의 「전달됨」 말풍선을 끝난 방식대로 정리한다. 잠금을 풀기 전에 부른다. 실패해도 예외를 올리지 않는다.
     *
     * <p>이 프로세스에 그 실행의 turn 표시가 있으면 그 steer 잠금 안에서 정리한다.
     *
     * @param rootExecutionId 그 turn 의 루트 실행
     */
    void settle(Long rootExecutionId, Long conversationId, SteerSettlement settlement);


    /** 말풍선이 있는 대기 글을 다음 turn 으로 보낼 때 그 말풍선을 「다음 차례로 넘어감」 으로 바꾼다. 부르는 쪽의 트랜잭션 안에서 바꾼다. */
    void carryForward(Long conversationId, List<Long> messageIds);


    /** {@link #carryForward} 로 바꾼 말풍선을 화면에 알린다. 트랜잭션이 끝난 뒤 부른다. */
    void announce(Conversation conversation, List<Long> messageIds);
}
