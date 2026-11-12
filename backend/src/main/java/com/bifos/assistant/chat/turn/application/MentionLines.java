package com.bifos.assistant.chat.turn.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.application.dto.AgentLabel;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.conversation.domain.ChatMessage;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.turn.application.dto.DelegationLine;
import com.bifos.assistant.chat.turn.application.dto.MentionMarks;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 사용자가 {@code @} 로 바로 맡긴 실행을 가리고 읽는다(ADR-20261112 / mention-delegation).
 *
 * <p>맡김 실행은 대화의 실행 가운데 부모가 없고 에이전트가 대화의 에이전트와 다른 실행이다. 대화의 에이전트는 바뀌지 않으므로 보통
 * turn, 자동 turn, 예약 turn, 먼저 살펴보기는 모두 대화의 에이전트로 돈다. 칸을 따로 두지 않는다.
 */
@Component
@RequiredArgsConstructor
class MentionLines {
    private final AgentExecutionRepository executions;
    private final AgentService agents;


    /** 그 대화의 맡김 실행이다. 번호순이다. 대화의 에이전트가 없는 옛 대화는 비어 있다. */
    List<AgentExecution> of(Conversation conversation) {
        if (conversation.agentId() == null) {
            return List.of();
        }
        return executions.findByConversationIdAndParentExecutionIdIsNullAndAgentIdNotOrderByIdAsc(
            conversation.id(), conversation.agentId());
    }


    /**
     * 다시 만들 답이나 질문이 맡김이면 거절한다. 다시 만들기는 대화의 에이전트와 session 으로 돌기 때문이다. 맡김을 다시 하려면
     * 다시 {@code @} 로 보낸다.
     *
     * @param previousAnswer 다시 만들 답. 답 없는 질문을 다시 시도하면 null
     * @throws ApiException {@code VALIDATION_FAILED}
     */
    void requireRegenerable(Conversation conversation, ChatMessage previousAnswer, ChatMessage question) {
        MentionMarks mentions = marks(conversation);
        boolean mentionAnswer = previousAnswer != null
            && previousAnswer.executionId() != null
            && mentions.answers().containsKey(previousAnswer.executionId());
        if (mentionAnswer || mentions.questions().containsKey(question.id())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "a delegated answer cannot be regenerated");
        }
    }


    /** 대화 이력의 질문 줄과 답 줄에 붙일 값이다. 맡김이 없으면 에이전트를 읽지 않는다. */
    MentionMarks marks(Conversation conversation) {
        List<AgentExecution> mentions = of(conversation);
        if (mentions.isEmpty()) {
            return MentionMarks.NONE;
        }
        Map<Long, Agent> found = agents.byIds(mentions.stream().map(AgentExecution::agentId).toList());
        Map<Long, DelegationLine> questions = new HashMap<>();
        Map<Long, AgentLabel> answers = new HashMap<>();
        for (AgentExecution mention : mentions) {
            AgentLabel label = AgentLabel.of(found.get(mention.agentId()));
            answers.put(mention.id(), label);
            if (mention.questionMessageId() != null) {
                questions.put(
                    mention.questionMessageId(), new DelegationLine(mention.id(), null, mention.status(), label));
            }
        }
        return new MentionMarks(questions, answers);
    }
}
