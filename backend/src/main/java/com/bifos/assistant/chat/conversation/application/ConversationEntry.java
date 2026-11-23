package com.bifos.assistant.chat.conversation.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.application.PrimaryAgents;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.domain.type.ConversationPurpose;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.Optional;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 사람이 대화에 들어오는 입구를 기본 비서로 모은다(비서 중심 대화 스펙 REQ4, REQ5).
 *
 * <p>새 대화는 늘 요청자의 기본 비서와 시작한다. 요청 본문의 에이전트 코드는 받지 않는다. 비었으면 기본 비서이고, 기본 비서와
 * 다른 코드면 옛 화면이나 `?agent=` 가 고른 것이므로 거절한다. 예약 작업이 결과를 남길 대화처럼 Control Plane 이 여는 대화는
 * 이 판정을 쓰지 않는다.
 *
 * <p>대화의 에이전트가 지금 요청자의 기본 비서가 아닌 대화는 읽기 전용이다(DECISION4, DECISION22). 점검 대화는 이 규칙 밖이다
 * (DECISION16). 사람이 쓰는 길(보내기, 다시 생성, 결과 다시 전달, 대기 메시지)만 막고 자동 turn, 예약 turn, 먼저 살펴보기는
 * 막지 않는다.
 */
@Component
@RequiredArgsConstructor
public class ConversationEntry {

    private final PrimaryAgents primaryAgents;
    private final AgentService agents;
    private final ConversationAccess access;


    /**
     * 새 대화의 에이전트다.
     *
     * @throws ApiException {@code AGENT_NOT_FOUND}(기본 비서가 없다), {@code VALIDATION_FAILED}(다른 에이전트를 골랐다),
     * {@code AGENT_DISABLED}(기본 비서가 꺼졌다)
     */
    public Agent startAgent(CurrentUser user, String agentCode) {
        Agent primary = primaryAgents.of(user)
            .orElseThrow(() -> new ApiException(ErrorCode.AGENT_NOT_FOUND, "no primary assistant"));
        if (agentCode != null && !agentCode.isBlank() && !agentCode.equals(primary.code())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "a new conversation starts with the primary assistant");
        }
        return agents.requireStartable(user, primary.code());
    }


    /** 그 대화에 사람이 이어 쓸 수 있는가. 여러 대화를 볼 때는 {@link #writableFor(CurrentUser)} 로 기본 비서를 한 번만 읽는다. */
    public boolean writable(CurrentUser user, Conversation conversation) {
        return writableFor(user).test(conversation);
    }


    /** 요청자의 기본 비서를 한 번 읽어 대화마다 이어 쓸 수 있는지 답한다. 기본 비서가 없으면 점검 대화만 참이다. */
    public Predicate<Conversation> writableFor(CurrentUser user) {
        Optional<Long> primaryId = primaryAgents.of(user).map(Agent::id);
        return conversation -> conversation.purpose() == ConversationPurpose.CHECK
            || primaryId.filter(id -> id.equals(conversation.agentId())).isPresent();
    }


    /**
     * 사람이 쓰는 요청 앞에서 부른다. 남의 대화와 없는 대화는 {@code CONVERSATION_NOT_FOUND} 다.
     *
     * @throws ApiException {@code VALIDATION_FAILED}(읽기 전용 대화다)
     */
    public void requireWritable(CurrentUser user, Long conversationId) {
        if (!writable(user, access.requireOwn(user, conversationId))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "this conversation is read-only");
        }
    }
}
